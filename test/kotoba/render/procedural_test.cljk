(ns kotoba.render.procedural-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.procedural :as procedural]
            [kotoba.render.texture :as texture]))

(deftest coordinate-hash-has-portable-golden-values
  (is (= [0 1364076727 2138582457 873284859]
         (mapv (fn [[seed x y salt]]
                 (procedural/coordinate-hash seed x y salt))
               [[0 0 0 0] [1 0 0 0] [1 2 3 4] [2654435769 17 29 7]]))))

(deftest bakes-complete-existing-rgba8-contract
  (doseq [kind [:steel :masonry :ground :grass :soil :rock]]
    (let [material (procedural/bake-pbr-material
                    {:kind kind :width 8 :height 4 :seed 42 :scale 4})]
      (is (= #{:albedo :normal :metallic-roughness} (set (keys material))))
      (is (every? #(= :kotoba.render/texture-rgba8-v1 (:schema %))
                  (vals material)))
      (is (= :srgb (get-in material [:albedo :color-space])))
      (is (= [:linear :linear]
             (mapv #(get-in material [% :color-space])
                   [:normal :metallic-roughness])))
      (is (every? #(= 128 (count (:data %))) (vals material)))
      (is (every? #(every? (fn [b] (and (integer? b) (<= 0 b 255))) (:data %))
                  (vals material)))
      (is (= [8 4] ((juxt :width :height) (:albedo material)))))))

(deftest seeded-output-is-reproducible-and-coordinate-local
  (let [options {:kind :ground :width 8 :height 8 :seed 99 :scale 4}
        a (procedural/bake-pbr-material options)
        b (procedural/bake-pbr-material options)
        c (procedural/bake-pbr-material (assoc options :seed 100))]
    (is (= a b))
    (is (not= (get-in a [:albedo :data]) (get-in c [:albedo :data])))
    (is (not= (procedural/coordinate-hash 99 7 3 17)
              (procedural/coordinate-hash 99 8 3 17)))))

(deftest material-presets-have-byte-exact-golden-albedo
  (let [golden
        {:steel [79 88 100 255, 81 91 102 255,
                 87 96 106 255, 166 172 181 255]
         :masonry [113 102 89 255, 111 100 87 255,
                   102 93 81 255, 134 74 41 255]
         :ground [72 101 62 255, 72 79 62 255,
                  72 77 62 255, 72 107 62 255]}]
    (doseq [[kind expected] golden]
      (is (= expected
             (get-in (procedural/bake-pbr-material
                      {:kind kind :width 2 :height 2 :seed 42 :scale 2})
                     [:albedo :data]))))))

(deftest generated-materials-compose-as-texture-array-layers
  (let [materials (mapv #(procedural/bake-pbr-material
                          {:kind % :width 8 :height 8 :seed 7 :scale 4})
                        [:steel :masonry :ground])
        library (texture/pbr-texture-library materials)]
    (is (= materials library))
    (is (= 4 (texture/mip-level-count 8 8)))
    (is (= [4 2 1]
           (mapv :width
                 (texture/generate-mipmaps-cpu
                  (get-in (first library) [:albedo :data]) 8 8 4))))))

(deftest terrain-biome-materials-are-distinct-seamless-complete-pbr-layers
  (let [materials (into {}
                        (for [kind [:grass :soil :rock]]
                          [kind (procedural/bake-pbr-material
                                 {:kind kind :width 8 :height 8 :seed 4242 :scale 4})]))]
    (is (= 3 (count (set (map #(get-in % [:albedo :data]) (vals materials))))))
    (doseq [[_ material] materials
            channel [:albedo :normal :metallic-roughness]
            :let [pixels (vec (partition 4 (get-in material [channel :data])))] ]
      (is (= (mapv #(nth pixels (* % 8)) (range 8))
             (mapv #(nth pixels (+ 7 (* % 8))) (range 8))))
      (is (= (subvec pixels 0 8) (subvec pixels 56 64))))))

(deftest terrain-biome-materials-have-byte-exact-goldens
  (let [golden {:grass [49 100 46 255, 49 100 46 255,
                         49 100 46 255, 49 100 46 255]
                :soil [119 74 52 255, 119 74 52 255,
                       119 74 52 255, 119 74 52 255]
                :rock [104 107 100 255, 104 107 100 255,
                       104 107 100 255, 104 107 100 255]}]
    (doseq [[kind expected] golden]
      (is (= expected
             (get-in (procedural/bake-pbr-material
                      {:kind kind :width 2 :height 2 :seed 42 :scale 2})
                     [:albedo :data]))))))

(deftest decal-materials-have-real-alpha-coverage-and-distinct-pbr-detail
  (let [wear (procedural/bake-pbr-material
              {:kind :decal-wear :width 32 :height 32 :seed 9031 :scale 8})
        impact (procedural/bake-pbr-material
                {:kind :decal-impact :width 32 :height 32 :seed 44021 :scale 8})
        alphas (fn [material] (mapv #(nth % 3) (partition 4 (get-in material [:albedo :data]))))]
    (doseq [material [wear impact]]
      (is (some zero? (alphas material)))
      (is (some pos? (alphas material)))
      (is (> (count (set (alphas material))) 2) "edge coverage is feathered, not binary rectangle"))
    (is (not= (:albedo wear) (:albedo impact)))
    (is (not= (:normal wear) (:normal impact)))))

(deftest rejects-ambiguous-procedural-input
  (doseq [options [{:kind :unknown :width 4 :height 4 :seed 1}
                   {:kind :steel :width 0 :height 4 :seed 1}
                   {:kind :steel :width 4 :height 4 :seed 1 :scale 0}
                   {:kind :steel :width 4 :height 4 :seed 1.5}
                   {:kind :steel :width 4 :height 4 :seed 4294967296}]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (procedural/bake-pbr-material options)))))
