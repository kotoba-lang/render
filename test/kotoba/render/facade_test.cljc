(ns kotoba.render.facade-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.facade :as facade]))

(def base {:family :stylized :entity-id :block-a :seed 771
           :width 12.0 :depth 8.0 :height 10.0})

(deftest archetypes-are-deterministic-rhythmic-and-budgeted
  (doseq [archetype facade/archetypes tier [:hero :mid :background]
          :let [resolved (facade/facade-kit (assoc base :archetype archetype :tier tier))]]
    (is (= resolved (facade/facade-kit (assoc base :archetype archetype :tier tier))))
    (is (true? (get-in resolved [:rhythm :no-blank-wall-violation?])))
    (is (true? (get-in resolved [:budget :within-budget?])))
    (is (pos? (get-in resolved [:rhythm :bay-count])))))

(deftest hero-covers-semantic-articulation-and-portable-meshes
  (let [resolved (facade/facade-kit (assoc base :archetype :industrial :tier :hero))
        semantics (set (map :semantic (:parts resolved)))]
    (is (every? semantics [:base :plinth :corner :floor-band :window-bay :window-frame
                           :recess-panel :door :canopy :signage :roof-parapet :vent :pipe]))
    (is (seq (get-in resolved [:mesh-library :box :mesh])))
    (is (seq (get-in resolved [:mesh-library :cylinder :mesh])))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %)) (:parts resolved)))))

(deftest material-and-window-emissive-variation-are-explicit
  (let [windows (filter #(= :window-bay (:semantic %))
                        (:parts (facade/facade-kit (assoc base :tier :hero))))]
    (is (every? #(= :kotoba.render/material-preset-v1
                    (get-in % [:material-ref :contract])) windows))
    (is (> (count (set (map #(get-in % [:material-overrides :emissive-strength]) windows))) 1))))

(deftest tier-detail-descends-and-photoreal-is-honest
  (let [[hero mid background] (facade/facade-lods base)
        photo (facade/facade-kit (assoc base :family :photoreal))]
    (is (> (count (:parts hero)) (count (:parts mid)) (count (:parts background))))
    (is (= :boundary-only (:implementation-status photo)))
    (is (= :unimplemented (:quality-claim photo)))
    (is (empty? (:parts photo)))))

(deftest settlement-instance-adapter-preserves-archetype-and-shell-dimensions
  (let [settlement {:family :stylized :tier :mid}
        instance {:instance/id :building-7 :archetype :utility
                  :collision {:size [5.0 4.5 4.0]}}
        resolved (facade/for-settlement-instance settlement instance 71)]
    (is (= :utility (:archetype resolved)))
    (is (= :building-7 (:entity-id resolved)))
    (is (= :kotoba.render/detail-kit-v1
           (get-in resolved [:detail-kit-link :contract])))))
