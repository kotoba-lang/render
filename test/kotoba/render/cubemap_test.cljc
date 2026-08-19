(ns kotoba.render.cubemap-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.cubemap :as cubemap]
            [kotoba.render.environment :as env]
            [kotoba.render.texture :as texture]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))
(defn- close-v? [a b] (every? true? (map close? a b)))

;; ---------------------------------------------------------------------------
;; An independent oracle: the D3D/GL cube face-selection table.
;;
;; `cubemap/direction` says which way a face pixel points. This says, for a
;; direction, which face pixel a GPU cube sampler would read. They must be
;; inverses. Writing the table out rather than reusing `direction`'s own case
;; is the point -- a projector that mirrors a face still looks self-consistent
;; to itself, and mirroring only some faces is exactly the defect this catches.
;; ---------------------------------------------------------------------------

(defn- lookup [[rx ry rz] size]
  (let [ax (abs rx) ay (abs ry) az (abs rz)
        [face sc tc ma] (cond
                          (and (>= ax ay) (>= ax az))
                          (if (pos? rx) [:+x (- rz) (- ry) ax] [:-x rz (- ry) ax])
                          (>= ay az)
                          (if (pos? ry) [:+y rx rz ay] [:-y rx (- rz) ay])
                          :else
                          (if (pos? rz) [:+z rx (- ry) az] [:-z (- rx) (- ry) az]))
        s (* 0.5 (+ 1.0 (/ sc ma)))
        t (* 0.5 (+ 1.0 (/ tc ma)))]
    [face
     (max 0 (min (dec size) (int (* s size))))
     (max 0 (min (dec size) (int (* t size))))]))

(deftest face-directions-invert-the-cube-lookup
  (let [size 8]
    (doseq [face cubemap/faces
            y (range size)
            x (range size)]
      (is (= [face x y] (lookup (cubemap/direction face size x y) size))
          (str "face " face " pixel " x "," y
               " does not come back from the cube lookup table")))))

(deftest face-centres-point-down-their-axis
  (let [size 3 c 1]                     ; odd size -> pixel 1,1 is dead centre
    (is (close-v? [1.0 0.0 0.0] (cubemap/direction :+x size c c)))
    (is (close-v? [-1.0 0.0 0.0] (cubemap/direction :-x size c c)))
    (is (close-v? [0.0 1.0 0.0] (cubemap/direction :+y size c c)))
    (is (close-v? [0.0 -1.0 0.0] (cubemap/direction :-y size c c)))
    (is (close-v? [0.0 0.0 1.0] (cubemap/direction :+z size c c)))
    (is (close-v? [0.0 0.0 -1.0] (cubemap/direction :-z size c c)))))

(deftest face-order-matches-the-ibl-contract
  (is (= env/cube-faces cubemap/faces)
      "cube face order must not drift from the environment asset contract"))

(deftest equirect-poles-and-centre
  (testing "zenith is the top row, not the bottom -- v = 0 at +Y"
    (is (close? 0.0 (second (cubemap/equirect-uv [0.0 1.0 0.0]))))
    (is (close? 1.0 (second (cubemap/equirect-uv [0.0 -1.0 0.0]))))
    ;; Only the row is meaningful at a pole: azimuth is degenerate there, so
    ;; the column it lands in is arbitrary as long as it is inside the image.
    (is (= 0 (second (cubemap/equirect-pixel 64 32 [0.0 1.0 0.0]))))
    (is (= 31 (second (cubemap/equirect-pixel 64 32 [0.0 -1.0 0.0]))))
    (is (every? #(<= 0 % 63)
                (map #(first (cubemap/equirect-pixel 64 32 %))
                     [[0.0 1.0 0.0] [0.0 -1.0 0.0]]))))
  (testing "the image centre looks at -Z, and +X is a quarter turn along +u"
    (is (close-v? [0.5 0.5] (cubemap/equirect-uv [0.0 0.0 -1.0])))
    (is (close-v? [0.75 0.5] (cubemap/equirect-uv [1.0 0.0 0.0])))
    (is (close-v? [0.25 0.5] (cubemap/equirect-uv [-1.0 0.0 0.0])))))

(deftest source-indices-stay-inside-the-panorama
  (let [w 37 h 19 size 12]              ; deliberately not powers of two
    (doseq [face cubemap/faces]
      (let [idx (cubemap/face-source-indices face size w h)]
        (is (= (* size size) (count idx)))
        (is (every? #(and (nat-int? %) (< % (* w h))) idx)
            (str "face " face " samples outside the panorama"))))))

;; ---------------------------------------------------------------------------
;; Projection, against a panorama that encodes its own coordinates: red is the
;; column, green is the row. Any flip, mirror or transpose shows up as pixels
;; carrying the wrong coordinate.
;; ---------------------------------------------------------------------------

(def ^:private pano-w 64)
(def ^:private pano-h 32)

(def ^:private coordinate-panorama
  (texture/rgba8 pano-w pano-h
                 (vec (for [y (range pano-h) x (range pano-w)
                            c [(* x 4) (* y 8) 0 255]] c))
                 :srgb))

(defn- channel [face-bytes n]
  (map #(nth face-bytes (+ (* 4 %) n)) (range (quot (count face-bytes) 4))))

(deftest projection-keeps-the-sky-at-the-top
  (let [size 16
        faces (cubemap/project coordinate-panorama size)
        rows (fn [face] (channel (get faces face) 1))]
    (testing "+Y reads the top of the panorama and -Y the bottom"
      ;; The inverted mapping this replaces put the zenith at row 31 of 32:
      ;; +Y would read green >= 176 here, and -Y would read green <= 72.
      (is (< (apply max (rows :+y)) 128))
      (is (> (apply min (rows :-y)) 128)))
    (testing "the side faces span the horizon, not one hemisphere"
      (doseq [face [:+x :-x :+z :-z]]
        (is (< (apply min (rows face)) 128))
        (is (> (apply max (rows face)) 128))))))

(deftest projected-faces-are-a-valid-cube-level
  (let [size 8
        level (env/cube-level size (cubemap/project coordinate-panorama size))]
    (is (= size (:size level)))
    (is (every? #(= (* size size 4) (count %)) (vals (:faces level))))
    (is (every? #(<= 0 % 255) (mapcat identity (vals (:faces level)))))))

(deftest projection-is-deterministic
  (is (= (cubemap/project coordinate-panorama 8)
         (cubemap/project coordinate-panorama 8))))

(deftest projection-refuses-a-panorama-it-cannot-read
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (cubemap/project-face {:width 4 :height 2 :data (vec (repeat 8 0))}
                                     :+x 4))
      "a truncated panorama must fail rather than sample whatever is there")
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (cubemap/project-face coordinate-panorama :+x 0))))
