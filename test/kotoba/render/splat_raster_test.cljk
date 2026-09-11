(ns kotoba.render.splat-raster-test
  "Enough to trust a picture as evidence: things land where they should, the
   nearer of two wins, and nothing is drawn from behind the camera."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.splat :as splat]
            [kotoba.render.splat-raster :as raster]))

(defn- splat-at [pos dc & {:keys [opacity scale] :or {opacity 10.0 scale -2.0}}]
  {:position pos :opacity opacity :scale [scale scale scale]
   :rotation [1.0 0.0 0.0 0.0] :sh-dc dc})

(defn- cloud [& ss] (assoc (splat/new-cloud) :splats (vec ss)))

(defn- px [{:keys [data width]} x y]
  (let [o (* 3 (+ (* y width) x))] [(nth data o) (nth data (+ o 1)) (nth data (+ o 2))]))

;; f_dc is premultiplied by the zeroth SH coefficient, so this is the dc that
;; means "red": (0.5 + C0*dc) = 1 at C0 = 0.28209479...
(def ^:private dc-red [1.7724539 -1.7724539 -1.7724539])
(def ^:private dc-blue [-1.7724539 -1.7724539 1.7724539])

(def ^:private cam
  (raster/camera {:eye [0.0 0.0 0.0] :target [0.0 0.0 1.0] :fov-deg 90.0}))

(deftest a-splat-on-the-axis-lands-in-the-middle
  (let [img (raster/render (cloud (splat-at [0.0 0.0 2.0] dc-red)) cam
                           {:width 64 :height 64})
        [r g b] (px img 32 32)]
    (is (= 64 (:width img)))
    (is (> r 200) "red channel is lit")
    (is (< g 60) "and the others are not -- f_dc is not the colour, 0.5+C0*dc is")
    (is (< b 60))
    (is (= [0 0 0] (px img 2 2)) "the corner keeps the background")
    (is (zero? (:behind-camera img)))))

(deftest the-nearer-splat-wins
  (testing "painter order is by depth, not by position in the vector"
    ;; Far one first, then near one.
    (let [a (raster/render (cloud (splat-at [0.0 0.0 4.0] dc-blue)
                                  (splat-at [0.0 0.0 2.0] dc-red)) cam {:width 32 :height 32})
          ;; Same two, listed the other way round. The picture must not change.
          b (raster/render (cloud (splat-at [0.0 0.0 2.0] dc-red)
                                  (splat-at [0.0 0.0 4.0] dc-blue)) cam {:width 32 :height 32})]
      (is (= (:data a) (:data b)) "input order cannot change the image")
      (let [[r _ bl] (px a 16 16)]
        (is (> r bl) "the near red one is in front of the far blue one")))))

(deftest what-is-behind-the-camera-is-not-drawn
  (let [img (raster/render (cloud (splat-at [0.0 0.0 -2.0] dc-red)) cam {:width 16 :height 16})]
    (is (= 1 (:behind-camera img)))
    (is (zero? (:splats-drawn img)))
    (is (every? zero? (:data img)))))

(deftest a-dropped-splat-is-reported-not-hidden
  (let [c (cloud (splat-at [0.0 0.0 2.0] dc-red {:opacity 10.0})
                 (splat-at [0.3 0.0 2.0] dc-blue {:opacity -10.0}))
        img (raster/render c cam {:width 32 :height 32 :max-splats 1})]
    (is (= 1 (:splats-drawn img)))
    (is (= 1 (:splats-dropped img))
        "drawing part of a cloud and calling it the cloud is how a picture lies")))

(deftest the-background-shows-through-where-nothing-was-drawn
  (let [img (raster/render (cloud) cam {:width 8 :height 8 :background [10 20 30]})]
    (is (= [10 20 30] (px img 4 4)))
    (is (zero? (:coverage img)))))

(deftest inside-views-look-outward-from-the-middle
  (let [c (cloud (splat-at [-1.0 0.0 0.0] dc-red) (splat-at [1.0 0.0 0.0] dc-blue)
                 (splat-at [0.0 0.0 -1.0] dc-red) (splat-at [0.0 0.0 1.0] dc-blue))
        views (raster/inside-views c)]
    (is (= 4 (count views)))
    (doseq [v views]
      (let [img (raster/render c v {:width 32 :height 32})]
        ;; One wall ahead, one behind: from the centre exactly one is visible.
        (is (= 1 (:splats-drawn img)) "each view sees the wall it faces")))))
