(ns kotoba.render.foreground-density-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.foreground-density :as density]))

(def base {:family :stylized :entity-id :junction-foreground :seed 8128
           :origin [4.0 0.0 -3.0] :radius 11.0 :ground-y 0.0})

(deftest actual-geometry-is-normalized-and-bounded
  (let [resolved (density/foreground-kit base)]
    (is (= {:min [-0.5 0.0 -0.5] :max [0.5 1.0 0.5]}
           (get-in resolved [:geometry-contract :bounds])))
    (is (true? (get-in resolved [:geometry-contract :double-scale-forbidden?])))
    (doseq [[_ geometry] (:geometry-library resolved)
            :let [[positions _ _ indices] (:mesh geometry)]]
      (is (= :normalized-unit (:geometry-space geometry)))
      (is (= {:min [-0.5 0.0 -0.5] :max [0.5 1.0 0.5]}
             (:normalized-bounds geometry)))
      (is (every? #(<= -0.5000001 % 1.0000001) positions))
      (is (seq indices)))))

(deftest transforms-own-world-size-and-are-grounded
  (let [resolved (density/foreground-kit base)
        descriptors (mapcat second (:camera-zones resolved))]
    (is (every? #(= :normalized-unit (:geometry-space %)) descriptors))
    (is (every? #(= :world-size (get-in % [:transform :scale-mode])) descriptors))
    (is (every? #(zero? (get-in % [:transform :offset 1])) descriptors))
    (is (every? #(true? (get-in % [:transform :grounded?])) descriptors))))

(deftest deterministic-density-tiers-have-exact-counts-and-budgets
  (doseq [tier [:hero :mid :background]
          :let [resolved (density/foreground-kit (assoc base :tier tier))
                policy (density/tier-policy tier)]]
    (is (= resolved (density/foreground-kit (assoc base :tier tier))))
    (is (= (:foreground-count policy) (count (get-in resolved [:camera-zones :foreground]))))
    (is (= (:midground-count policy) (count (get-in resolved [:camera-zones :midground]))))
    (is (= (:instance-budget policy)
           (+ (count (get-in resolved [:camera-zones :foreground]))
              (count (get-in resolved [:camera-zones :midground]))
              (count (:material-layers resolved)))))
    (is (= (:instance-budget policy) (get-in resolved [:budget :instances])))
    (is (true? (get-in resolved [:budget :within-budget?])))))

(deftest foreground-and-midground-have-balanced-left-right-composition
  (doseq [tier [:hero :mid]
          :let [resolved (density/foreground-kit (assoc base :tier tier))]
          zone [:foreground :midground]
          :let [descriptors (get-in resolved [:camera-zones zone])
                counts (frequencies (map :composition-region descriptors))
                left (get counts (keyword (str (name zone) "-left")) 0)
                right (get counts (keyword (str (name zone) "-right")) 0)]]
    (is (pos? left))
    (is (pos? right))
    (is (<= (#?(:clj Math/abs :cljs js/Math.abs) (- left right)) 1))
    (is (every? (fn [{:keys [composition-region screen-side]}]
                  (= composition-region
                     (keyword (str (name zone) "-" (name screen-side)))))
                descriptors))
    (is (every? #(zero? (get-in % [:transform :offset 1])) descriptors))))

(deftest layering-is-renderer-consumable-not-metadata-only
  (let [layers (:material-layers (density/foreground-kit base))]
    (is (= #{:road-edge-wear :road-patch :road-decal
             :facade-base :facade-trim :facade-window}
           (set (map :material-role layers))))
    (is (every? map? (map :material layers)))
    (is (every? keyword? (map :geometry-ref layers)))
    (is (every? #(= :world-size (get-in % [:transform :scale-mode])) layers))
    (is (= #{[:road-surface :neighborhood-world]
             [:building-facade :facade-local]}
           (set (map (juxt #(get-in % [:attachment :target])
                           #(get-in % [:attachment :space])) layers))))
    (is (= #{:base :trim-band :window-bay}
           (set (map #(get-in % [:attachment :anchor])
                     (filter #(= :building-facade (get-in % [:attachment :target])) layers)))))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %)) layers))))

(deftest photoreal-boundary-is-future
  (let [resolved (density/foreground-kit (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unsupported-future (:quality-claim resolved)))
    (is (empty? (:geometry-library resolved)))
    (is (empty? (:material-layers resolved)))))
