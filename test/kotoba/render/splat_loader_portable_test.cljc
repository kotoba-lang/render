(ns kotoba.render.splat-loader-portable-test
  "Binary PLY on both runtimes, against the property layout real files use.

  `splat_loader_test.clj` covers more, and it is `.clj`, which is how the
  loader could return garbage under ClojureScript for as long as it did: the
  header needle was built with `(int c)` over a string, which is 0 there, so
  the eleven-byte `end_header\\n` needle became eleven zeroes and matched the
  first run of zeroes in the body instead. Every real splat PLY has such a run,
  because `nx ny nz` are written as 0.0.

  So the fixture here writes those normals -- it is not decoration. Without
  them this file would pass on both runtimes with the bug in place."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.splat-loader :as sl]))

(defn- f32-le
  "One float as four little-endian bytes."
  [x]
  #?(:clj (let [b (java.nio.ByteBuffer/allocate 4)]
            (.order b java.nio.ByteOrder/LITTLE_ENDIAN)
            (.putFloat b (float x))
            (mapv #(bit-and % 0xff) (.array b)))
     :cljs (let [buf (js/ArrayBuffer. 4)
                 f (js/Float32Array. buf)
                 u (js/Uint8Array. buf)]
             (aset f 0 x)
             (vec (array-seq u)))))

(defn- ascii [s] (mapv #?(:clj int :cljs #(.charCodeAt % 0)) s))

;; The layout TRELLIS writes: position, NORMALS, colour, opacity, scale,
;; rotation. The loader's own docstring lists the properties without the
;; normals, which is why a fixture written from the docstring would not have
;; caught this.
(def ^:private header
  (str "ply\n"
       "format binary_little_endian 1.0\n"
       "element vertex 2\n"
       "property float x\nproperty float y\nproperty float z\n"
       "property float nx\nproperty float ny\nproperty float nz\n"
       "property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n"
       "property float opacity\n"
       "property float scale_0\nproperty float scale_1\nproperty float scale_2\n"
       "property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n"
       "end_header\n"))

(defn- vertex [[x y z] dc opacity scale]
  (into [] cat (concat (map f32-le [x y z])
                       (map f32-le [0.0 0.0 0.0])       ; nx ny nz — the zero run
                       (map f32-le dc)
                       [(f32-le opacity)]
                       (map f32-le scale)
                       (map f32-le [1.0 0.0 0.0 0.0]))))

(def ^:private fixture
  (into (ascii header)
        cat
        [(vertex [-0.25 0.5 1.5]  [0.1 0.2 0.3] -1.5 [-2.0 -2.5 -3.0])
         (vertex [4.0 -8.0 16.0]  [0.4 0.5 0.6]  2.5 [-1.0 -1.5 -2.0])]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-6))

(deftest binary-ply-loads-every-vertex-with-the-right-values
  (let [cloud (sl/load-ply fixture)
        [a b] (:splats cloud)]
    (testing "both vertices, not one short"
      ;; The bug lost exactly one: the body offset was too far in, so the loop
      ;; ran out of bytes before the last vertex.
      (is (= 2 (count (:splats cloud)))))
    (testing "and the values are the ones that were written"
      (is (every? true? (map close? [-0.25 0.5 1.5] (:position a))))
      (is (every? true? (map close? [4.0 -8.0 16.0] (:position b))))
      (is (close? -1.5 (:opacity a)))
      (is (close? 2.5 (:opacity b)))
      (is (every? true? (map close? [-2.0 -2.5 -3.0] (:scale a))))
      (is (every? true? (map close? [0.1 0.2 0.3] (:sh-dc a))))
      (is (every? true? (map close? [1.0 0.0 0.0 0.0] (:rotation a)))))
    (testing "normals are skipped rather than read as colour"
      ;; If the property index lookup were positional instead of by name, the
      ;; zeroed normals would land in :sh-dc and this would be [0 0 0].
      (is (not (every? zero? (:sh-dc a)))))))

(deftest a-ply-without-a-header-is-refused
  (is (thrown? #?(:clj Exception :cljs js/Error) (sl/load-ply (ascii "not a ply at all")))))
