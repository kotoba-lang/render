(ns kotoba.render.terrain-biome-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.terrain :as terrain]
            [kotoba.render.terrain-biome :as biome]))

(deftest weights-are-normalized-and-art-directed
  (doseq [[height normal x z] [[0 [0 1 0] 0 0] [14 [0.8 0.2 0] 20 40]
                               [-6 [0 0.92 0.1] -12 7]]
          :let [w (biome/biome-weights biome/default-biome height normal x z)]]
    (is (< (Math/abs (- 1.0 (reduce + (vals w)))) 1.0e-9))
    (is (every? pos? (vals w))))
  (let [flat (biome/biome-weights biome/default-biome 4 [0 1 0] 0 0)
        cliff (biome/biome-weights biome/default-biome 14 [0.9 0.15 0] 0 0)]
    (is (> (:grass flat) (:rock flat)))
    (is (> (:rock cliff) (:grass cliff)))))

(deftest macro-and-pbr-reference-are-deterministic
  (is (= (biome/macro-variation (:macro biome/default-biome) 20 30)
         (biome/macro-variation (:macro biome/default-biome) 20 30)))
  (is (= {:albedo [0.16 0.34 0.12] :roughness 0.92 :metallic 0.0 :normal-strength 0.72}
         (biome/blended-pbr {:grass 1.0 :soil 0.0 :rock 0.0}))))

(deftest webgpu-contract-retains-all-aaa-controls
  (let [contract (biome/webgpu-contract)]
    (is (= :terrain-biome-splat (:type contract)))
    (is (= [:grass :soil :rock] (mapv :id (:layers contract))))
    (is (every? #(every? (set (keys %)) [:texture-layer :roughness :metallic
                                         :normal-strength :uv-scale]) (:layers contract)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (biome/webgpu-contract (assoc biome/default-biome :layers []))))))

(deftest terrain-mesh-carries-normalized-gpu-weight-attribute
  (let [mesh (terrain/terrain-mesh {:base-segments 8 :amplitude 5 :seed 17} :high)
        weights (biome/mesh-weights mesh)]
    (is (= (quot (count (first mesh)) 3) (count weights)))
    (is (every? #(= 3 (count %)) weights))
    (is (every? #(< (Math/abs (- 1.0 (reduce + %))) 1.0e-9) weights))))
