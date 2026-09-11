(ns kotoba.render.building-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.building :as building]
            [kotoba.render.lod :as lod]
            [kotoba.render.mesh :as mesh]))

(def tower {:variant :stepped-tower :width 12.0 :depth 10.0 :height 40.0
            :seed 2654435769})
(def factory {:variant :industrial-block :width 24.0 :depth 16.0 :height 12.0
              :seed 42})

(deftest building-mesh-is-deterministic-and-valid
  (doseq [spec [tower factory]]
    (let [[positions normals uvs indices :as a] (building/building-mesh spec :high)
          b (building/building-mesh spec :high)]
      (is (= a b))
      (is (= (count positions) (count normals)))
      (is (= (quot (count positions) 3) (quot (count uvs) 2)))
      (is (every? #(< % (quot (count positions) 3)) indices))
      (is (every? #(<= 0 %) indices)))))

(deftest generated-mesh-fits-loaded-and-tangent-pipelines
  (let [[positions normals uvs indices] (building/building-mesh factory :high)
        loaded (mesh/loaded-mesh positions normals uvs indices)
        tangents (mesh/compute-tangents positions normals uvs indices)
        interleaved (mesh/interleave-with-tangents positions normals uvs tangents)]
    (is (= (:vertex-count loaded) (quot (count positions) 3)))
    (is (= (* (:vertex-count loaded) 4) (count tangents)))
    (is (= (* (:vertex-count loaded) 12) (count interleaved)))
    (is (every? #(#{-1.0 1.0} %)
                (take-nth 4 (drop 3 tangents))))))

(deftest lods-reduce-topology-and-select-by-screen-size
  (doseq [spec [tower factory]]
    (let [levels (building/building-lods spec)
          triangles (mapv :triangle-count levels)]
      (is (= [:high :medium :low] (mapv :id levels)))
      (is (apply > triangles))
      (is (= :high (:id (lod/select-level levels 120.0))))
      (is (= :medium (:id (lod/select-level levels 48.0))))
      (is (= :low (:id (lod/select-level levels 8.0)))))))

(deftest variants-have-recognizable-silhouette-components
  (is (= [48 24 12]
         (mapv :triangle-count (building/building-lods tower))))
  (is (= [48 24 12]
         (mapv :triangle-count (building/building-lods factory))))
  (let [[positions] (building/building-mesh tower :high)
        ys (map second (partition 3 positions))]
    (is (= 0.0 (apply min ys)))
    (is (= 40.0 (apply max ys)))))

(deftest seed-varies-proportions-with-stable-topology
  (let [[a _ _ ai] (building/building-mesh tower :high)
        [b _ _ bi] (building/building-mesh (assoc tower :seed 7) :high)]
    (is (not= a b))
    (is (= ai bi))))

(deftest rejects-invalid-building-contract
  (doseq [[spec detail] [[(assoc tower :variant :castle) :high]
                         [(assoc tower :width 0) :high]
                         [(assoc tower :seed -1) :high]
                         [tower :ultra]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (building/building-mesh spec detail)))))
