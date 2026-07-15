(ns kotoba.render.terrain-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.lod :as lod]
            [kotoba.render.mesh :as mesh]
            [kotoba.render.terrain :as terrain]
            [kotoba.render.terrain-biome :as terrain-biome]))

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
    (is (every? #(= :mesh (:type %)) (vals registry)))
    (doseq [{registered :mesh} (vals registry)
            :let [{:keys [positions normals uvs indices]} registered
                  flat-pos (vec (mapcat identity positions))
                  flat-normal (vec (mapcat identity normals))
                  flat-uv (vec (mapcat identity uvs))]]
      (is (= (count positions) (count uvs)))
      (is (= (* 4 (count positions))
             (count (mesh/compute-tangents flat-pos flat-normal flat-uv indices)))))))

(deftest rejects-invalid-terrain-contract
  (doseq [[spec detail] [[(assoc base :patch [0.5 0]) :high]
                         [(assoc base :base-segments 10) :high]
                         [(assoc base :amplitude -1) :high]
                         [(assoc base :seed -1) :high]
                         [base :ultra]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (terrain/terrain-mesh spec detail)))))

(deftest registered-meshes-carry-default-biomes-through-every-lod-and-skirt
  (let [registration-spec (assoc base :base-segments 8)
        registry (terrain/webgpu-registration :island registration-spec)]
    (doseq [[detail divisor] terrain/detail-divisor
            :let [mesh (get-in registry [(keyword (str "island-" (name detail))) :mesh])
                  vertex-count (count (:positions mesh))
                  segments (quot (:base-segments registration-spec) divisor)
                  surface-count (* (inc segments) (inc segments))]]
      (is (> vertex-count surface-count) "registration includes lowered skirt vertices")
      (is (= vertex-count (count (:normals mesh)) (count (:uvs mesh))
             (count (:biome-weights mesh)) (count (:biome-layer-indices mesh))))
      (is (every? #(= [2 1 3] %) (:biome-layer-indices mesh)))
      (is (every? #(and (= 3 (count %))
                         (< (#?(:clj Math/abs :cljs js/Math.abs)
                             (- 1.0 (reduce + %)))
                            1.0e-9))
                  (:biome-weights mesh))))))

(deftest registered-meshes-repeat-custom-data-driven-layer-indices
  (let [custom-biome (assoc terrain-biome/default-biome :layers
                            (mapv #(assoc %1 :texture-layer %2)
                                  (:layers terrain-biome/default-biome) [4 0 7]))
        registry (terrain/webgpu-registration
                  :custom (assoc base :base-segments 8 :biome custom-biome))]
    (doseq [detail terrain/details
            :let [mesh (get-in registry [(keyword (str "custom-" (name detail))) :mesh])]]
      (is (= (count (:positions mesh)) (count (:biome-weights mesh))
             (count (:biome-layer-indices mesh))))
      (is (every? #(= [4 0 7] %) (:biome-layer-indices mesh)))
      (is (every? #(< (#?(:clj Math/abs :cljs js/Math.abs)
                         (- 1.0 (reduce + %)))
                      1.0e-9)
                  (:biome-weights mesh))))))
