(ns kotoba.render.vegetation-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.lod :as lod]
            [kotoba.render.mesh :as mesh]
            [kotoba.render.vegetation :as vegetation]))

(def specs
  [{:variant :broadleaf :width 5.0 :depth 5.0 :height 9.0 :seed 11}
   {:variant :conifer :width 4.0 :depth 4.0 :height 11.0 :seed 22}
   {:variant :shrub :width 3.0 :depth 2.5 :height 1.8 :seed 33}])

(deftest variants-are-deterministic-and-well-formed
  (doseq [spec specs detail [:high :low]]
    (let [[positions normals uvs indices :as a] (vegetation/vegetation-mesh spec detail)]
      (is (= a (vegetation/vegetation-mesh spec detail)))
      (is (= (count positions) (count normals)))
      (is (= (quot (count positions) 3) (quot (count uvs) 2)))
      (is (zero? (mod (count indices) 3)))
      (is (every? #(< -1 % (quot (count positions) 3)) indices)))))

(deftest low-lod-reduces-triangles-and-selects-by-screen-size
  (doseq [spec specs]
    (let [levels (vegetation/vegetation-lods spec)]
      (is (= [:high :low] (mapv :id levels)))
      (is (apply > (map :triangle-count levels)))
      (is (= :high (:id (lod/select-level levels 80.0))))
      (is (= :low (:id (lod/select-level levels 20.0)))))))

(defn- axis-extent [positions axis]
  (let [values (map #(nth % axis) (partition 3 positions))]
    (- (apply max values) (apply min values))))

(deftest silhouettes-use-the-authored-footprint-and-retain-low-lod-character
  (doseq [{:keys [width depth height] :as spec} specs
          detail [:high :low]
          :let [[positions] (vegetation/vegetation-mesh spec detail)]]
    ;; Prevent the old box/pole failure mode: foliage must occupy most of the
    ;; authored horizontal footprint and a useful fraction of its full height.
    (is (> (axis-extent positions 0) (* width 0.72)) [(:variant spec) detail :width])
    (is (> (axis-extent positions 2) (* depth 0.68)) [(:variant spec) detail :depth])
    (is (> (axis-extent positions 1) (* height 0.72)) [(:variant spec) detail :height])))

(deftest seeds-change-crown-silhouette-without-changing-topology
  (doseq [spec specs
          :let [[positions-a _ _ indices-a] (vegetation/vegetation-mesh spec :high)
                [positions-b _ _ indices-b] (vegetation/vegetation-mesh (update spec :seed inc) :high)]]
    (is (not= positions-a positions-b) (:variant spec))
    (is (= indices-a indices-b) (:variant spec))))

(deftest output-fits-loaded-and-tangent-pipelines
  (let [[positions normals uvs indices] (vegetation/vegetation-mesh (first specs) :high)
        loaded (mesh/loaded-mesh positions normals uvs indices)
        tangents (mesh/compute-tangents positions normals uvs indices)]
    (is (= (:vertex-count loaded) (quot (count positions) 3)))
    (is (= (* 4 (:vertex-count loaded)) (count tangents)))
    (is (= (* 12 (:vertex-count loaded))
           (count (mesh/interleave-with-tangents positions normals uvs tangents))))))

(deftest bounds-and-webgpu-registration-are-explicit
  (let [spec (first specs)
        registry (vegetation/webgpu-registration :park-tree spec)]
    (is (= {:min [-2.5 0.0 -2.5] :max [2.5 9.0 2.5]} (vegetation/bounds spec)))
    (is (= #{:park-tree-high :park-tree-low} (set (keys registry))))
    (is (every? #(= :mesh (:type %)) (vals registry)))
    (is (every? #(seq (get-in % [:mesh :positions])) (vals registry)))))

(deftest rejects-invalid-specs
  (doseq [[spec detail] [[(assoc (first specs) :variant :palm) :high]
                         [(assoc (first specs) :height 0) :high]
                         [(assoc (first specs) :seed -1) :high]
                         [(first specs) :medium]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (vegetation/vegetation-mesh spec detail)))))
