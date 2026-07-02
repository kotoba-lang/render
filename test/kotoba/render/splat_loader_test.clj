(ns kotoba.render.splat-loader-test
  "Parity tests ported from `kami-render/src/splat_loader.rs`'s
   `#[cfg(test)] mod tests` — including the bit-exact SPZ/PLY fixtures.
   JVM-only (`.clj`, not `.cljc`) — the fixture builders use
   `java.util.zip.GZIPOutputStream`/`java.nio.ByteBuffer`."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.bits :as bits]
            [kotoba.render.splat :as splat]
            [kotoba.render.splat-loader :as sl]))

(defn- close? [a b eps] (< (Math/abs (double (- a b))) eps))

(defn- gzip [raw-bytes]
  (let [ba (byte-array (map unchecked-byte raw-bytes))
        baos (java.io.ByteArrayOutputStream.)
        gz (java.util.zip.GZIPOutputStream. baos)]
    (.write gz ba)
    (.finish gz)
    (bits/bytes->vec (.toByteArray baos))))

(defn- f32-le-bytes [f]
  (vec (map #(bit-and % 0xff) (-> (java.nio.ByteBuffer/allocate 4)
                                   (.order java.nio.ByteOrder/LITTLE_ENDIAN)
                                   (.putFloat (float f))
                                   .array))))

(defn- u32-le-bytes [n]
  [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)
   (bit-and (bit-shift-right n 16) 0xff) (bit-and (bit-shift-right n 24) 0xff)])

(defn- p24 [v]
  [(bit-and v 0xff) (bit-and (bit-shift-right v 8) 0xff) (bit-and (bit-shift-right v 16) 0xff)])

;; ---------------------------------------------------------------------------
;; .splat
;; ---------------------------------------------------------------------------

(deftest load-splat-empty
  (is (= (splat/count-splats (sl/load-splat [])) 0)))

(deftest load-splat-one
  (let [data (vec (concat (f32-le-bytes 1.0) (f32-le-bytes 2.0) (f32-le-bytes 3.0)
                           (f32-le-bytes 0.1) (f32-le-bytes 0.1) (f32-le-bytes 0.1)
                           [255 128 0 200]
                           [128 128 128 128]))
        cloud (sl/load-splat data)]
    (is (= (splat/count-splats cloud) 1))
    (let [[px py pz] (:position (first (:splats cloud)))]
      (is (close? px 1.0 0.01))
      (is (close? py 2.0 0.01))
      (is (close? pz 3.0 0.01)))))

;; ---------------------------------------------------------------------------
;; PLY
;; ---------------------------------------------------------------------------

(deftest load-ply-ascii
  (let [ply (map int "ply\nformat ascii 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\nend_header\n1.0 2.0 3.0\n4.0 5.0 6.0\n")
        cloud (sl/load-ply (vec ply))]
    (is (= (splat/count-splats cloud) 2))
    (is (close? (nth (:position (nth (:splats cloud) 0)) 0) 1.0 0.01))
    (is (close? (nth (:position (nth (:splats cloud) 1)) 0) 4.0 0.01))))

(deftest load-ply-with-opacity
  (let [ply (map int "ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nproperty float opacity\nend_header\n1.0 2.0 3.0 0.5\n")
        cloud (sl/load-ply (vec ply))]
    (is (= (splat/count-splats cloud) 1))
    (is (close? (:opacity (first (:splats cloud))) 0.5 0.01))))

(defn- ply-header [props]
  (str "ply\nformat binary_little_endian 1.0\nelement vertex 1\n"
       (apply str (map #(str "property float " % "\n") props))
       "end_header\n"))

(deftest load-ply-binary-le-with-non-utf8-body
  (let [props ["x" "y" "z" "opacity" "scale_0" "scale_1" "scale_2"
               "rot_0" "rot_1" "rot_2" "rot_3" "f_dc_0" "f_dc_1" "f_dc_2"]
        hdr (map int (ply-header props))
        vals [1.0 2.0 3.0 0.5 0.1 0.1 0.1 1.0 0.0 0.0 0.0 0.5 0.5 0.5]
        body (mapcat f32-le-bytes vals)
        bytes (vec (concat hdr body))
        cloud (sl/load-ply bytes)]
    (is (= (splat/count-splats cloud) 1))
    (let [s (first (:splats cloud))]
      (is (close? (nth (:position s) 0) 1.0 1e-4))
      (is (close? (nth (:position s) 1) 2.0 1e-4))
      (is (close? (nth (:position s) 2) 3.0 1e-4))
      (is (close? (:opacity s) 0.5 1e-4)))))

(deftest load-ply-binary-with-f-rest-degree-1
  (let [props (concat ["x" "y" "z" "opacity" "scale_0" "scale_1" "scale_2"
                        "rot_0" "rot_1" "rot_2" "rot_3" "f_dc_0" "f_dc_1" "f_dc_2"]
                       (map #(str "f_rest_" %) (range 9)))
        hdr (map int (ply-header props))
        base-vals [1.0 2.0 3.0 0.5 -2.0 -2.0 -2.0 1.0 0.0 0.0 0.0 0.1 0.2 0.3]
        rest-vals [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0]
        body (mapcat f32-le-bytes (concat base-vals rest-vals))
        bytes (vec (concat hdr body))
        cloud (sl/load-ply bytes)]
    (is (= (splat/count-splats cloud) 1))
    (is (= (:sh-degree cloud) 1))
    (is (= (count (:sh-rest cloud)) 3))
    (is (= (nth (:sh-rest cloud) 0) [1.0 4.0 7.0]))
    (is (= (nth (:sh-rest cloud) 1) [2.0 5.0 8.0]))
    (is (= (nth (:sh-rest cloud) 2) [3.0 6.0 9.0]))))

(deftest load-ply-binary-tolerates-truncated-body
  (let [props ["x" "y" "z" "opacity" "scale_0" "scale_1" "scale_2"
               "rot_0" "rot_1" "rot_2" "rot_3" "f_dc_0" "f_dc_1" "f_dc_2"]
        hdr (map int (str "ply\nformat binary_little_endian 1.0\nelement vertex 100\n"
                           (apply str (map #(str "property float " % "\n") props))
                           "end_header\n"))
        body (mapcat (fn [i] (mapcat f32-le-bytes [i 0.0 0.0 0.5 -2.0 -2.0 -2.0 1.0 0.0 0.0 0.0 0.0 0.0 0.0]))
                      (range 30))
        bytes (vec (concat hdr body))
        cloud (sl/load-ply bytes)]
    (is (= (splat/count-splats cloud) 30))
    (is (close? (nth (:position (nth (:splats cloud) 29)) 0) 29.0 1e-4))))

;; ---------------------------------------------------------------------------
;; SPZ
;; ---------------------------------------------------------------------------

(deftest load-spz-v2-one-point
  (let [frac-bits 12
        raw (vec (concat (u32-le-bytes sl/spz-magic)
                          (u32-le-bytes 2) ;; version
                          (u32-le-bytes 1) ;; numPoints
                          [0 frac-bits 0 0] ;; shDegree, fracBits, flags, reserved
                          (p24 4096) (p24 8192) (p24 0) ;; position
                          [230] ;; alpha
                          [128 128 128] ;; color
                          [160 160 160] ;; scale
                          [191 128 128])) ;; rotation first-three
        cloud (sl/load-spz (gzip raw))]
    (is (= (splat/count-splats cloud) 1))
    (let [s (first (:splats cloud))]
      (is (close? (nth (:position s) 0) 1.0 0.01))
      (is (close? (nth (:position s) 1) 2.0 0.01))
      (is (< (Math/abs (double (nth (:position s) 2))) 0.01))
      (is (every? #(< (Math/abs (double %)) 0.01) (:scale s)))
      (is (> (:opacity s) 1.0))
      (is (close? (nth (:rotation s) 0) 0.867 0.02))
      (is (close? (nth (:rotation s) 1) 0.498 0.02))
      (is (= (:sh-degree cloud) 0))
      (is (empty? (:sh-rest cloud))))))

(deftest load-spz-with-sh-degree-1
  (let [raw (vec (concat (u32-le-bytes sl/spz-magic) (u32-le-bytes 2) (u32-le-bytes 1)
                          [1 12 0 0]
                          (repeat 9 0) ;; pos
                          [200] ;; alpha
                          [128 128 128] ;; color
                          [160 160 160] ;; scale
                          [128 128 128] ;; rot (3B)
                          [192 128 128 128 128 128 128 128 128])) ;; sh
        cloud (sl/load-spz (gzip raw))]
    (is (= (:sh-degree cloud) 1))
    (is (= (count (:sh-rest cloud)) 3))
    (is (close? (nth (first (:sh-rest cloud)) 0) 0.5 0.01))
    (is (< (Math/abs (double (nth (first (:sh-rest cloud)) 1))) 0.01))))

(deftest load-spz-rejects-non-spz
  (let [bad (gzip (vec (map int "this is not an spz file at all, just text padding............")))]
    (is (thrown? clojure.lang.ExceptionInfo (sl/load-spz bad)))
    (try (sl/load-spz bad) (catch clojure.lang.ExceptionInfo e
                              (is (= (:type (ex-data e)) :bad-magic))))))
