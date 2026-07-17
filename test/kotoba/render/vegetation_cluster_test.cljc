(ns kotoba.render.vegetation-cluster-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.vegetation-cluster :as cluster]))

(def base {:family :stylized :entity-id :grove-a :seed 4815 :radius 9.0})

(deftest cluster-composition-is-deterministic-complete-and-budgeted
  (doseq [tier [:foreground :midground :background]
          :let [resolved (cluster/vegetation-cluster (assoc base :density-tier tier))]]
    (is (= resolved (cluster/vegetation-cluster (assoc base :density-tier tier))))
    (is (= (set cluster/kinds) (set (map :kind (:instances resolved)))))
    (is (true? (get-in resolved [:budget :within-budget?])))
    (is (= :deterministic-best-candidate (get-in resolved [:placement :method])))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %))
                (:instances resolved)))))

(deftest density-and-mesh-detail-descend
  (let [[foreground midground background] (cluster/density-lods base)]
    (is (> (count (:instances foreground))
           (count (:instances midground))
           (count (:instances background))))
    (is (= [:high :mid :low]
           (mapv #(get-in % [:instances 0 :mesh-ref :lod])
                 [foreground midground background])))
    (doseq [resolved [foreground midground background]
            kind cluster/kinds
            lod [:high :mid :low]]
      (is (seq (get-in resolved [:mesh-library kind lod :mesh])))
      (is (pos? (get-in resolved [:mesh-library kind lod :triangle-count]))))))

(deftest placement-avoids-most-footprint-overlap-and-seed-varies-layout
  (let [a (cluster/vegetation-cluster base)
        b (cluster/vegetation-cluster (update base :seed inc))]
    (is (not= (mapv :offset (:instances a)) (mapv :offset (:instances b))))
    (is (> (/ (count (filter #(>= (:placement-clearance %) -0.15) (:instances a)))
              (double (count (:instances a))))
           0.80))))

(deftest surface-and-material-contracts-are-explicit
  (doseq [instance (:instances (cluster/vegetation-cluster
                                (assoc base :density-tier :foreground)))]
    (is (= :kotoba.render/material-preset-v1
           (get-in instance [:material-ref :contract])))
    (is (contains? #{:foliage :trunk :grass} (get-in instance [:material-ref :role])))
    (is (boolean? (get-in instance [:surface :wind :enabled?])))
    (is (contains? #{:opaque :mask} (get-in instance [:surface :alpha-mode])))
    (is (true? (get-in instance [:surface :outline :participates?])))))

(deftest photoreal-boundary-is-honest
  (let [resolved (cluster/vegetation-cluster (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unimplemented (:quality-claim resolved)))
    (is (empty? (:instances resolved)))
    (is (empty? (:mesh-library resolved)))))
