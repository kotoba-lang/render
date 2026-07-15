(ns kotoba.render.texture-test
  "Ported from kami-render/src/texture.rs's #[cfg(test)] mod tests
   (mip_level_calculation) plus new coverage for the CPU box-filter
   downsampler."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.texture :as tex]))

(deftest portable-pbr-texture-contract
  (let [set (tex/pbr-texture-set
             {:albedo (tex/rgba8 1 1 [240 120 60 255] :srgb)})]
    (is (= :kotoba.render/texture-rgba8-v1 (get-in set [:albedo :schema])))
    (is (= tex/normal-pixel (get-in set [:normal :data])))
    (is (= tex/mr-pixel (get-in set [:metallic-roughness :data])))
    (is (= :linear (get-in set [:metallic-roughness :color-space]))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (tex/rgba8 2 2 [0 0 0 0] :linear))))

(deftest mip-level-calculation
  (is (= (tex/mip-level-count 1024 1024) 11))
  (is (= (tex/mip-level-count 4 4) 3))
  (is (= (tex/mip-level-count 1 1) 1)))

(deftest generate-mipmaps-cpu-averages-2x2-block
  ;; 2x2 image, 4 distinct-red pixels -> level 1 is a single averaged pixel.
  (let [data [0 0 0 255,  100 0 0 255
              200 0 0 255, 50 0 0 255]
        mips (tex/generate-mipmaps-cpu data 2 2 2)]
    (is (= (count mips) 1))
    (let [{:keys [level width height data]} (first mips)]
      (is (= level 1))
      (is (= width 1))
      (is (= height 1))
      ;; (0+100+200+50)/4 = 87 (integer division)
      (is (= (nth data 0) 87))
      (is (= (nth data 3) 255)))))

(deftest generate-mipmaps-cpu-no-levels-when-mip-levels-is-1
  (is (empty? (tex/generate-mipmaps-cpu [255 255 255 255] 1 1 1))))
