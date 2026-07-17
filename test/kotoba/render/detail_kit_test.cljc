(ns kotoba.render.detail-kit-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.detail-kit :as kit]))

(def base {:family :stylized :entity-id :station-a :seed 4242
           :variant :depot :width 12.0 :depth 8.0 :height 7.0})

(deftest tiers-are-deterministic-bounded-and-monotonic
  (let [[hero gameplay crowd] (kit/lod-kits base)]
    (is (= [hero gameplay crowd] (kit/lod-kits base)))
    (is (> (count (:parts hero)) (count (:parts gameplay)) (count (:parts crowd))))
    (is (> (count (:foreground-props hero))
           (count (:foreground-props gameplay))
           (count (:foreground-props crowd))))
    (doseq [resolved [hero gameplay crowd]]
      (is (true? (get-in resolved [:budget :within-budget?])))
      (is (<= (get-in resolved [:budget :triangle-count])
              (get-in resolved [:budget :triangle-budget]))))))

(deftest kit-covers-silhouette-material-and-noncolliding-prop-contracts
  (let [hero (kit/detail-kit (assoc base :tier :hero))
        roles (set (map :role (:parts hero)))]
    (is (every? roles [:wall :roof :trim :window :utility]))
    (is (every? #(= :kotoba.render/material-preset-v1
                    (get-in % [:material-ref :contract]))
                (concat (:parts hero) (:foreground-props hero))))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %))
                (:foreground-props hero)))
    (is (some #(pos? (get-in % [:geometry :bevel :width])) (:parts hero)))
    (is (= [:high :medium :low] (mapv :id (:geometry-lods hero))))))

(deftest variation-is-stable-and-seed-sensitive
  (let [a (kit/detail-kit base)
        b (kit/detail-kit (assoc base :seed 4243))]
    (is (= a (kit/detail-kit base)))
    (is (not= (mapv :offset (:foreground-props a))
              (mapv :offset (:foreground-props b))))))

(deftest photoreal-sibling-is-honest-boundary
  (let [resolved (kit/detail-kit (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unimplemented (:quality-claim resolved)))
    (is (empty? (:parts resolved)))
    (is (= :kotoba.render/material-preset-v1 (:material-contract resolved)))))

(deftest invalid-authoring-is-rejected
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (kit/detail-kit (assoc base :tier :cinematic))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (kit/detail-kit (assoc base :seed -1)))))
