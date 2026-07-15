(ns kotoba.render.environment-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.environment :as env]
            [kotoba.render.texture :as texture]))

(defn faces [size pixel]
  (into {} (for [face env/cube-faces]
             [face (vec (mapcat identity (repeat (* size size) pixel)))])))

(deftest split-sum-ibl-contract
  (let [irradiance (env/cube-rgba8 [(env/cube-level 1 (faces 1 [32 40 56 255]))] :linear)
        specular (env/cube-rgba8 [(env/cube-level 2 (faces 2 [64 72 88 255]))
                                  (env/cube-level 1 (faces 1 [32 36 44 255]))]
                                 :linear)
        lut (texture/rgba8 1 1 [128 128 0 255] :linear)
        ibl (env/pbr-environment {:irradiance irradiance
                                  :prefiltered-specular specular
                                  :brdf-lut lut})]
    (is (= :kotoba.render/pbr-environment-v1 (:schema ibl)))
    (is (= 2 (count (get-in ibl [:prefiltered-specular :levels])))))
  (testing "runtime upload cannot silently substitute an incomplete specular chain"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (env/pbr-environment
                  {:irradiance (env/cube-rgba8
                                [(env/cube-level 1 (faces 1 [0 0 0 255]))] :linear)
                   :prefiltered-specular (env/cube-rgba8
                                          [(env/cube-level 4 (faces 4 [0 0 0 255]))]
                                          :linear)
                   :brdf-lut (texture/rgba8 1 1 [0 0 0 255] :linear)})))))
