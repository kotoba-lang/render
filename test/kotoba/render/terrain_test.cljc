(ns kotoba.render.terrain-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.lod :as lod]
            [kotoba.render.mesh :as mesh]
            [kotoba.render.terrain :as terrain]))

(def base {:patch [0 0] :size 64.0 :base-segments 32 :amplitude 9.0
           :seed 2654435769 :skirt-depth 3.0})

(defn- top-grid [mesh segments]
  (take (* (inc segments) (inc segments)) (partition 3 (first mesh))))

(deftest height-and-mesh-are-deterministic
  (is (= (terrain/height-at base 17 -4) (terrain/height-at base 17 -4)))
  (doseq [detail [:high :medium :low]]
    (let [[positions normals uvs indices :as generated] (terrain/terrain-mesh base detail)]
      (is (= generated (terrain/terrain-mesh base detail)))
      (is (= (count positions) (count normals)))
      (is (= (quot (count positions) 3) (quot (count uvs) 2)))
      (is (every? #(< -1 % (quot (count positions) 3)) indices)))))

(deftest adjacent-patches-share-exact-edge-position-and-normal
  (let [left (terrain/terrain-mesh base :high)
        right (terrain/terrain-mesh (assoc base :patch [1 0]) :high)
        left-pos (vec (top-grid left 32)) right-pos (vec (top-grid right 32))
        left-norm (vec (take (* 33 33) (partition 3 (second left))))
        right-norm (vec (take (* 33 33) (partition 3 (second right))))]
    (doseq [z (range 33)]
      (is (= (nth left-pos (+ (* z 33) 32)) (nth right-pos (* z 33))))
      (is (= (nth left-norm (+ (* z 33) 32)) (nth right-norm (* z 33)))))))

(deftest lod-edges-are-subsets-of-the-canonical-grid
  (let [high (vec (top-grid (terrain/terrain-mesh base :high) 32))
        low (vec (top-grid (terrain/terrain-mesh base :low) 8))]
    (doseq [z (range 9) x (range 9)]
      (is (= (nth high (+ (* z 4 33) (* x 4)))
             (nth low (+ (* z 9) x)))))))

(deftest skirts-extend-bounds-and-lods-reduce-topology
  (let [without-skirt (terrain/terrain-mesh (assoc base :skirt-depth 0.0) :high)
        with-skirt (terrain/terrain-mesh base :high)
        levels (terrain/terrain-lods base)]
    (is (< (get-in (terrain/mesh-bounds with-skirt) [:min 1])
           (get-in (terrain/mesh-bounds without-skirt) [:min 1])))
    (is (apply > (map :triangle-count levels)))
    (is (= [:high :medium :low] (mapv :id levels)))
    (is (= :medium (:id (lod/select-level levels 64.0))))))

(deftest output-fits-loaded-tangent-and-registration-contracts
  (let [[positions normals uvs indices] (terrain/terrain-mesh base :low)
        loaded (mesh/loaded-mesh positions normals uvs indices)
        tangents (mesh/compute-tangents positions normals uvs indices)
        registry (terrain/webgpu-registration :island base)]
    (is (= (:vertex-count loaded) (quot (count positions) 3)))
    (is (= (* 4 (:vertex-count loaded)) (count tangents)))
    (is (= #{:island-high :island-medium :island-low} (set (keys registry))))
    (is (every? #(= :mesh (:type %)) (vals registry)))))

(deftest rejects-invalid-terrain-contract
  (doseq [[spec detail] [[(assoc base :patch [0.5 0]) :high]
                         [(assoc base :base-segments 10) :high]
                         [(assoc base :amplitude -1) :high]
                         [(assoc base :seed -1) :high]
                         [base :ultra]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (terrain/terrain-mesh spec detail)))))
