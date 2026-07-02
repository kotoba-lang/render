(ns kotoba.render.mesh-test
  "Parity tests ported from `kami-render/src/mesh.rs`'s `#[cfg(test)] mod tests`."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.mesh :as mesh]))

(deftest cube-counts
  (let [[pos norm uv idx] (mesh/cube)]
    (is (= (count pos) (* 24 3)))
    (is (= (count norm) (* 24 3)))
    (is (= (count uv) (* 24 2)))
    (is (= (count idx) 36))))

(deftest sphere-valid
  (let [[pos norm uv idx] (mesh/sphere 8 16)]
    (is (seq pos))
    (is (= (count pos) (count norm)))
    (is (= (quot (count pos) 3) (quot (count uv) 2)))
    (is (seq idx))
    (doseq [[nx ny nz] (partition 3 norm)]
      (let [len (Math/sqrt (double (+ (* nx nx) (* ny ny) (* nz nz))))]
        (is (< (Math/abs (- len 1.0)) 0.01) (str "normal not unit: " len))))
    (doseq [u uv]
      (is (and (>= u 0.0) (<= u 1.0)) (str "uv out of range: " u)))))

(deftest plane-counts
  (let [[pos norm uv idx] (mesh/plane 10.0 10.0 3)
        segs 4
        verts (* (inc segs) (inc segs))]
    (is (= (count pos) (* verts 3)))
    (is (= (count norm) (* verts 3)))
    (is (= (count uv) (* verts 2)))
    (is (= (count idx) (* segs segs 6)))))

(deftest interleave-stride
  (let [pos [1.0 2.0 3.0 4.0 5.0 6.0]
        norm [0.0 1.0 0.0 0.0 0.0 1.0]
        uv [0.0 0.0 1.0 1.0]
        out (mesh/interleave pos norm uv)]
    (is (= (count out) (* 2 8)))
    (is (= (subvec out 0 3) [1.0 2.0 3.0]))
    (is (= (subvec out 3 6) [0.0 1.0 0.0]))
    (is (= (subvec out 6 8) [0.0 0.0]))))

(deftest loaded-mesh-from-cube
  (let [[pos norm uv idx] (mesh/cube)
        m (mesh/loaded-mesh pos norm uv idx)]
    (is (= (:vertex-count m) 24))
    (is (= (:index-count m) 36))
    (is (= (count (:vertices m)) (* 24 8)))))

(deftest tangent-computation
  ;; Flat quad on XZ plane: tangent should be ~(1,0,0), handedness +1
  (let [pos [0.0 0.0 0.0 1.0 0.0 0.0 1.0 0.0 1.0 0.0 0.0 1.0]
        norm [0.0 1.0 0.0 0.0 1.0 0.0 0.0 1.0 0.0 0.0 1.0 0.0]
        uv [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0]
        idx [0 1 2 0 2 3]
        tangents (mesh/compute-tangents pos norm uv idx)]
    (is (= (count tangents) (* 4 4)))
    (is (< (Math/abs (- (nth tangents 0) 1.0)) 0.1))
    (is (< (Math/abs (nth tangents 1)) 0.1))
    (is (< (Math/abs (nth tangents 2)) 0.1))
    (is (> (Math/abs (nth tangents 3)) 0.5))))

(deftest interleave-with-tangents-stride
  (let [pos [1.0 2.0 3.0]
        norm [0.0 1.0 0.0]
        uv [0.5 0.5]
        tan [1.0 0.0 0.0 1.0]
        out (mesh/interleave-with-tangents pos norm uv tan)]
    (is (= (count out) 12))))

(deftest hex-prism-valid
  (let [[pos norm uv idx] (mesh/hex-prism 1.0 2.0)]
    (is (seq pos))
    (is (= (count pos) (count norm)))
    (is (= (quot (count pos) 3) (quot (count uv) 2)))
    (is (seq idx))
    ;; top(7) + bottom(7) + sides(6*4=24) = 38 vertices
    (is (= (quot (count pos) 3) 38))))

(deftest cylinder-pipe-solid
  (let [[pos norm _uv idx] (mesh/cylinder-pipe 0.5 0.0 2.0 16)]
    (is (seq pos))
    (is (= (count pos) (count norm)))
    (is (seq idx))))

(deftest cylinder-pipe-hollow
  (let [[pos _norm _uv _idx] (mesh/cylinder-pipe 0.5 0.1 2.0 16)
        [pos-solid _ _ _] (mesh/cylinder-pipe 0.5 0.0 2.0 16)]
    (is (seq pos))
    (is (> (count pos) (count pos-solid)))))

(deftest building-extrusion-square
  (let [footprint [[-1.0 -1.0] [1.0 -1.0] [1.0 1.0] [-1.0 1.0]]
        [pos _norm _uv idx] (mesh/building-extrusion footprint 10.0)]
    (is (seq pos))
    (is (= (quot (count pos) 3) 24))
    (is (= (count idx) 36))))

(deftest hex-grid-ring1
  (let [m (mesh/hex-grid 1 1.0 0.2 1.05)]
    (is (pos? (:vertex-count m)))
    (is (pos? (:index-count m)))
    (is (= (:vertex-count m) (* 7 38)))))
