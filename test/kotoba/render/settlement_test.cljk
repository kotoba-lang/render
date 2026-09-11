(ns kotoba.render.settlement-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.settlement :as settlement]))

(def base {:family :stylized :entity-id :settlement-a :seed 90210
           :block-size 20.0 :street-width 6.0})

(deftest settlement-is-deterministic-diverse-and-budgeted
  (doseq [tier [:hero :mid :background]
          :let [resolved (settlement/settlement (assoc base :tier tier))]]
    (is (= resolved (settlement/settlement (assoc base :tier tier))))
    (is (true? (get-in resolved [:diversity :no-consecutive-identical-silhouette?])))
    (is (true? (get-in resolved [:diversity :landmark-ratio-within-cap?])))
    (is (true? (get-in resolved [:budget :within-budget?])))
    (is (> (count (set (map :archetype (:instances resolved)))) 2))))

(deftest archetype-prototypes-carry-actual-meshes-and-detail-parts
  (let [resolved (settlement/settlement (assoc base :tier :hero))]
    (is (= (set settlement/archetypes) (set (keys (:prototypes resolved)))))
    (doseq [archetype settlement/archetypes]
      (is (= [:high :medium :low]
             (mapv :id (get-in resolved [:prototypes archetype :building-meshes]))))
      (is (seq (get-in resolved [:prototypes archetype :building-meshes 0 :mesh])))
      (is (seq (get-in resolved [:prototypes archetype :detail-kit :parts]))))))

(deftest collision-shell-and-visual-detail-are-separated
  (doseq [instance (:instances (settlement/settlement base))]
    (is (= :shell (get-in instance [:collision :mode])))
    (is (true? (get-in instance [:collision :navigation?])))
    (is (true? (get-in instance [:detail-ref :visual-only?])))))

(deftest clear-regions-remove-reserved-block-centres
  (let [clear {:shape :circle :center [0.0 0.0] :radius 18.0}
        resolved (settlement/settlement (assoc base :clear-regions [clear]))]
    (is (every? (fn [{[x _ z] :position}]
                  (>= (Math/sqrt (+ (* x x) (* z z))) 18.0))
                (:instances resolved)))
    (is (= [clear] (get-in resolved [:layout :clear-regions])))))

(deftest tiers-descend-and-photoreal-is-honest
  (let [[hero mid background] (settlement/settlement-lods base)
        photo (settlement/settlement (assoc base :family :photoreal))]
    (is (> (count (:instances background)) (count (:instances mid))
           (count (:instances hero))))
    (is (= :boundary-only (:implementation-status photo)))
    (is (= :unimplemented (:quality-claim photo)))
    (is (empty? (:instances photo)))))
