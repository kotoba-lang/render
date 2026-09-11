(ns kotoba.render.environment-bake-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.environment-bake :as bake]
            [kotoba.render.texture :as texture]))

(def tiny-config
  {:irradiance-size 2 :specular-size 4 :brdf-size 4
   :irradiance-samples 8 :specular-samples 8 :brdf-samples 16})

(deftest production-profile-meets-runtime-quality-floor
  (is (>= (:irradiance-size bake/production-config) 32))
  (is (>= (:specular-size bake/production-config) 128))
  (is (>= (:brdf-size bake/production-config) 128)))

(deftest daylight-source-has-bounded-energy-and-warm-cool-separation
  (let [sky (bake/studio-radiance [0.0 1.0 0.0])
        ground (bake/studio-radiance [0.0 -1.0 0.0])
        directions [[1.0 0.0 0.0] [-1.0 0.0 0.0]
                    [0.0 1.0 0.0] [0.0 -1.0 0.0]
                    [0.0 0.0 1.0] [0.0 0.0 -1.0]]]
    (is (every? true? (map #(< (Math/abs (- %1 %2)) 1.0e-12)
                           sky [0.1925 0.2625 0.42]))
        "cool daylight sky is a stable golden")
    (is (every? true? (map #(< (Math/abs (- %1 %2)) 1.0e-12)
                           ground [0.315 0.2275 0.1575]))
        "warm bounce is a stable golden")
    (is (> (nth sky 2) (nth sky 0)) "sky remains cool")
    (is (> (first ground) (nth ground 2)) "ground remains warm")
    (is (every? #(every? (fn [channel] (<= 0.0 channel 1.0))
                         (bake/studio-radiance %))
                directions))))

(deftest deterministic-bake-emits-existing-contract
  (let [a (bake/bake-environment tiny-config)
        b (bake/bake-environment tiny-config)]
    (is (= a b))
    (is (= :kotoba.render/pbr-environment-v1 (:schema a)))
    (is (= [4 2 1] (mapv :size (get-in a [:prefiltered-specular :levels]))))
    (is (= (texture/mip-level-count 4 4)
           (count (get-in a [:prefiltered-specular :levels]))))
    (is (= [4 4] ((juxt :width :height) (:brdf-lut a))))
    (is (not= (get-in a [:irradiance :levels 0 :faces :+y])
              (get-in a [:irradiance :levels 0 :faces :-y]))
        "analytic sky survives diffuse convolution")))

(deftest gzip-artifact-round-trips-without-source-literals
  (let [file (java.io.File/createTempFile "kotoba-ibl-" ".edn.gz")
        environment (bake/bake-environment tiny-config)]
    (try
      (bake/write-baked! file environment)
      (is (= environment (bake/read-baked file)))
      (is (< (.length file) 10000))
      (finally (.delete file)))))
