(ns kotoba.render.world-presets-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.world-presets :as presets]))

(deftest covers-every-requested-environment-role
  (is (= #{:foliage :trunk :grass}
         (set (map :role (presets/resolve-domain
                          {:family :stylized :domain :vegetation :entity-id "grove-7"})))))
  (is (= #{:wall :roof :trim :window :utility}
         (set (map :role (presets/resolve-domain
                          {:family :stylized :domain :architecture :entity-id "depot-2"}))))))

(deftest deterministic-variation-is-stable-and-effective
  (let [request {:family :stylized :domain :vegetation :role :foliage :entity-id "tree-42"}
        a (presets/resolve-preset request)
        b (presets/resolve-preset request)
        c (presets/resolve-preset (assoc request :entity-id "tree-43"))]
    (is (= a b))
    (is (not= (get-in a [:material :base-color]) (get-in c [:material :base-color])))
    (is (= :entity-id (get-in a [:variation :seed-source])))
    (is (integer? (get-in a [:variation :resolved-seed])))))

(deftest stylized-and-photoreal-share-the-envelope-not-the-shader-model
  (doseq [domain [:vegetation :architecture]
          role (get presets/domain-roles domain)]
    (let [base {:domain domain :role role :entity-id "same-object"}
          stylized (presets/resolve-preset (assoc base :family :stylized))
          photoreal (presets/resolve-preset (assoc base :family :photoreal))]
      (is (= :kotoba.render/material-preset-v1 (:contract stylized)))
      (is (= (dissoc stylized :family :material :preset-id)
             (dissoc photoreal :family :material :preset-id)))
      (is (= :toon-pbr (get-in stylized [:material :model])))
      (is (= :pbr (get-in photoreal [:material :model])))
      (is (contains? (:material stylized) :shade-color))
      (is (contains? (:material stylized) :rim-color))
      (is (contains? (:material stylized) :highlight))
      (is (not (contains? (:material photoreal) :shade-color))))))

(deftest lod-and-silhouette-policy-is-executable-data
  (let [veg (presets/resolve-preset {:family :stylized :domain :vegetation
                                     :role :grass :entity-id 9})
        arch (presets/resolve-preset {:family :stylized :domain :architecture
                                      :role :trim :entity-id 4})]
    (is (= [:high :low] (mapv :id (get-in veg [:lod-policy :levels]))))
    (is (= [:high :medium :low] (mapv :id (get-in arch [:lod-policy :levels]))))
    (is (apply > (map :min-pixels (get-in arch [:lod-policy :levels]))))
    (is (= :shell (get-in arch [:lod-policy :levels 2 :silhouette])))
    (is (true? (get-in veg [:outline-policy :participates?])))))

(deftest invalid-boundaries-fail-loudly
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (presets/resolve-preset {:family :cinematic :domain :vegetation :role :grass})))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (presets/resolve-preset {:family :stylized :domain :architecture :role :foliage}))))

(deftest scene-palette-and-resolution-evidence-are-stable
  (let [request {:family :stylized :domain :architecture :entity-id "district-11"}
        resolved (presets/resolve-domain request)
        palette (presets/role-palette request)
        evidence (presets/resolution-evidence resolved)]
    (is (= #{:wall :roof :trim :window :utility} (set (keys palette))))
    (is (= :kotoba.render/material-preset-resolution-v1 (:schema evidence)))
    (is (= 5 (:preset-count evidence)))
    (is (= [:stylized/architecture-roof :stylized/architecture-trim
            :stylized/architecture-utility :stylized/architecture-wall
            :stylized/architecture-window]
           (:preset-ids evidence)))
    (is (true? (:deterministic? evidence)))))
