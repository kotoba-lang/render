(ns kotoba.render.decal-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.decal :as decal]
            [kotoba.render.road :as road]))

(def terrain {:size 64.0 :base-segments 32 :amplitude 7.0
              :seed 2654435769 :skirt-depth 2.0})
(def spec {:center [7.5 -3.25] :size [9.0 3.0] :rotation 0.42
           :depth-bias 0.008 :terrain terrain
           :material {:metallic 0.04 :roughness 0.91 :alpha-mode :mask
                      :alpha-cutoff 0.18}})

(deftest projected-stamp-is-deterministic-indexed-and-grounded
  (doseq [detail decal/details]
    (let [[positions normals uvs indices :as mesh] (decal/decal-mesh spec detail)
          vertices (vec (partition 3 positions))]
      (is (= mesh (decal/decal-mesh spec detail)))
      (is (= (count vertices) (quot (count normals) 3) (quot (count uvs) 2)))
      (is (every? #(< -1 % (count vertices)) indices))
      (is (every? (fn [[x y z]]
                    (let [ground (road/terrain-height terrain x z)]
                      (and (> y ground) (< (- y ground) 0.02))))
                  vertices)))))

(deftest lods-reduce-real-tessellation-not-instance-scale
  (let [lods (decal/decal-lods spec)]
    (is (= decal/details (mapv :id lods)))
    (is (apply > (map :triangle-count lods)))
    (is (= [352 140 36] (mapv :triangle-count lods)))))

(deftest registration-retains-projection-alpha-pbr-and-depth-bias-contract
  (let [registry (decal/webgpu-registration :wear spec)]
    (is (= #{:wear-high :wear-medium :wear-low} (set (keys registry))))
    (is (every? #(= :terrain-following (get-in % [:decal :projection])) (vals registry)))
    (is (every? #(= 0.008 (get-in % [:decal :depth-bias])) (vals registry)))
    (is (every? #(= :mask (get-in % [:decal :alpha-mode])) (vals registry)))
    (is (every? #(= 0.91 (get-in % [:decal :pbr :roughness])) (vals registry)))))

(deftest invalid-material-and-zero-bias-fail-loudly
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (decal/decal-mesh (assoc spec :depth-bias 0.0))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (decal/decal-mesh (assoc-in spec [:material :alpha-mode] :unknown)))))
