(ns kotoba.render.foreground-density-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
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
  (doseq [tier [:hero :mid :background]
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

(deftest selection-intent-has-bands-extents-and-mixed-foreground-clusters
  (let [extent-ranges {:shrub [0.06 0.16] :grass [0.025 0.11]
                       :crate [0.04 0.10] :bollard [0.018 0.05]
                       :rock [0.04 0.11] :debris [0.025 0.075]}]
    (doseq [tier [:hero :mid :background]
          :let [resolved (density/foreground-kit (assoc base :tier tier))]
          zone [:foreground :midground]
          :let [descriptors (get-in resolved [:camera-zones zone])]]
      (is (every? #(= (if (= zone :foreground) [0.58 0.90] [0.42 0.72])
                      (:ground-contact-screen-y-range %))
                  descriptors))
      (is (every? #(= (extent-ranges (:kind %)) (:screen-extent-range %)) descriptors))
      (is (every? keyword? (map :cluster-id descriptors)))
      (is (every? #{:vegetation :solid-prop} (map :cluster-role descriptors)))))
  (doseq [tier [:hero :mid]
          :let [foreground (get-in (density/foreground-kit (assoc base :tier tier))
                                   [:camera-zones :foreground])]
          side [:left :right]
          :let [side-descriptors (filter #(= side (:screen-side %)) foreground)]]
    (is (= #{:vegetation :solid-prop} (set (map :cluster-role side-descriptors))))
    (is (every? #(= #{:vegetation :solid-prop} (set (map :cluster-role %)))
                (vals (group-by :cluster-id side-descriptors))))
    (is (some #{:shrub :grass} (map :kind side-descriptors)))
    (is (some #{:crate :bollard :rock :debris} (map :kind side-descriptors)))))

(deftest foreground-vegetation-is-authored-in-camera-facing-depth
  (doseq [tier [:hero :mid]
          :let [resolved (density/foreground-kit (assoc base :tier tier
                                                        :camera-facing-direction [0.0 -4.0]))
                vegetation (filter #(= :vegetation (:cluster-role %))
                                   (get-in resolved [:camera-zones :foreground]))]
          side [:left :right]
          :let [side-candidates (filter #(= side (:screen-side %)) vegetation)]]
    (is (seq side-candidates))
    (is (every? #(<= (get-in % [:transform :offset 2])
                     (- (nth (:origin base) 2) (* (:radius base) 0.62 0.18)))
                side-candidates))
    (is (every? #(= [0.58 0.90] (:ground-contact-screen-y-range %))
                side-candidates))
    (is (every? #(= (if (= :grass (:kind %)) [0.025 0.11] [0.06 0.16])
                    (:screen-extent-range %))
                side-candidates))))

(deftest camera-facing-direction-is-generic-normalized-and-optional
  (let [fallback (density/foreground-kit base)
        plus-x (density/foreground-kit (assoc base :camera-facing-direction [8.0 0.0]))
        vegetation (filter #(= :vegetation (:cluster-role %))
                           (get-in plus-x [:camera-zones :foreground]))
        minimum-facing-depth (* (:radius base) 0.62 0.18)]
    (is (= [1.0 0.0] (get-in plus-x [:placement-contract :camera-facing-direction])))
    (is (= :preserve-radial-layout (get-in fallback [:placement-contract :fallback])))
    (is (nil? (get-in fallback [:placement-contract :camera-facing-direction])))
    (is (every? #(>= (+ 1.0e-9 (- (get-in % [:transform :offset 0])
                                  (nth (:origin base) 0)))
                     minimum-facing-depth)
                vegetation))
    (is (every? #(= [1.0 0.0] (:camera-facing-direction %)) vegetation))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (density/foreground-kit (assoc base :camera-facing-direction [0.0 0.0]))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (density/foreground-kit (assoc base :camera-facing-direction [1.0]))))))

(deftest layering-is-renderer-consumable-not-metadata-only
  (let [layers (:material-layers (density/foreground-kit base))]
    (is (= #{:road-edge-wear :road-patch :facade-base :facade-trim :facade-window
             :facade-door :facade-roof}
           (set (map :material-role layers))))
    (is (every? map? (map :material layers)))
    (is (every? keyword? (map :geometry-ref layers)))
    (is (every? #(= :world-size (get-in % [:transform :scale-mode])) layers))
    (is (= #{[:road-surface :neighborhood-world]
             [:building-facade :facade-local]}
           (set (map (juxt #(get-in % [:attachment :target])
                           #(get-in % [:attachment :space])) layers))))
    (is (= #{:base :trim-band :window-bay :door-bay :roof-line}
           (set (map #(get-in % [:attachment :anchor])
                     (filter #(= :building-facade (get-in % [:attachment :target])) layers)))))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %)) layers))))

(deftest geometry-variants-are-real-diverse-and-context-decorrelated
  (let [a (density/foreground-kit (assoc base :tier :hero))
        b (density/foreground-kit (assoc base :tier :hero :entity-id :other-junction))
        descriptors (mapcat second (:camera-zones a))
        variants (group-by :kind descriptors)]
    (is (every? keyword? (map :geometry-variant descriptors)))
    (is (every? #(contains? (:geometry-library a) (:geometry-ref %)) descriptors))
    (is (<= 2 (count (set (map :geometry-variant (get variants :shrub))))))
    (is (not= (mapv (juxt :kind :geometry-variant) descriptors)
              (mapv (juxt :kind :geometry-variant) (mapcat second (:camera-zones b)))))
    (doseq [kind [:grass :shrub]
            [_ geometry] (filter (fn [[ref _]] (str/starts-with? (name ref) (name kind)))
                                 (:geometry-library a))
            :let [{[min-x _ min-z] :min [max-x _ max-z] :max} (:source-bounds geometry)
                  width (- max-x min-x) depth (- max-z min-z)]]
      (is (> width 0.45))
      (is (> depth 0.40))
      (is (<= 0.55 (/ width depth) 1.8)))
    (let [rock-scales (map #(get-in % [:transform :scale]) (get variants :rock))]
      (is (every? #(<= (first %) 0.78) rock-scales)))))

(deftest production-layers-have-facade-separation-and-safe-road-eligibility
  (let [resolved (density/foreground-kit base)
        layers (:material-layers resolved)
        by-role (into {} (map (juxt :material-role identity) layers))
        roads (filter #(#{:road-edge-wear :road-patch} (:material-role %)) layers)
        facade (remove #(#{:road-edge-wear :road-patch} (:material-role %)) layers)
        value (fn [role] (/ (reduce + (take 3 (get-in by-role [role :material :base-color]))) 3.0))]
    (is (= #{:road-edge-wear :road-patch} (set (map :material-role roads))))
    (is (= 2 (count (set (map :geometry-ref roads)))))
    (is (every? #(true? (get-in % [:attachment-eligibility :subject-exclusion-required?])) roads))
    (is (every? #(= #{:junction-center}
                    (get-in % [:attachment-eligibility :eligible-regions])) roads))
    (is (every? #(true? (get-in % [:feature :center-safe?])) roads))
    (is (every? #(= :final-world (:bounds-space %)) roads))
    (is (every? #(= 3 (count (:min (:bounds %)))) roads))
    (is (= #{7 4} (set (map #(count (:bounds-set %)) roads))))
    (is (every? #(<= 2 (count (:bounds-set %))) roads))
    (is (every? (fn [road]
                  (every? #(= #{:min :max} (set (keys %))) (:bounds-set road)))
                roads))
    (doseq [road roads
            :let [[positions _ _ _] (get-in resolved
                                             [:geometry-library (:geometry-ref road) :mesh])
                  components (partition (* 24 3) positions)
                  [ox oy oz] (get-in road [:transform :offset])
                  [sx sy sz] (get-in road [:transform :scale])]
            [component piece-bounds] (map vector components (:bounds-set road))
            [x y z] (partition 3 component)
            :let [world [(+ ox (* sx x)) (+ oy (* sy y)) (+ oz (* sz z))]]]
      (is (every? true? (map <= (:min piece-bounds) world (:max piece-bounds)))))
    (doseq [road roads
            piece (:bounds-set road)]
      (is (every? true? (map <= (get-in road [:bounds :min]) (:min piece))))
      (is (every? true? (map <= (:max piece) (get-in road [:bounds :max])))))
    (let [[left right] (sort-by #(get-in % [:bounds :min 0]) roads)]
      (is (< (get-in left [:bounds :max 0]) (get-in right [:bounds :min 0]))))
    (is (<= 3 (count facade)))
    (is (every? #(= (:attachment %) (:attachment-eligibility %)) facade))
    (is (every? #(= :facade-local-to-building (:bounds-space %)) facade))
    (is (every? #(= 3 (count (:min (:facade-layer-bounds %)))) facade))
    (is (pos? (get-in by-role [:facade-window :feature :recess-depth])))
    (is (<= 3 (get-in by-role [:facade-window :feature :panes])))
    (is (< (value :facade-window) (value :facade-base) (value :facade-trim)))
    (is (= :stepped-roof (get-in by-role [:facade-roof :feature :silhouette])))
    (is (every? #(contains? (:geometry-library resolved) (:geometry-ref %)) layers))
    (is (= 84 (quot (count (get-in resolved [:geometry-library :road-breakup-islands :mesh 3])) 3)))
    (is (= 48 (quot (count (get-in resolved [:geometry-library :road-patch-fragments :mesh 3])) 3)))
    (is (= 36 (quot (count (get-in resolved [:geometry-library :facade-window-bank :mesh 3])) 3)))
    (let [all (concat (mapcat second (:camera-zones resolved)) layers)
          actual-triangles (reduce + (map #(quot (count (get-in resolved
                                                                 [:geometry-library (:geometry-ref %) :mesh 3]))
                                                  3)
                                          all))]
      (is (= actual-triangles (get-in resolved [:budget :triangles]))))))

(deftest photoreal-boundary-is-future
  (let [resolved (density/foreground-kit (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unsupported-future (:quality-claim resolved)))
    (is (empty? (:geometry-library resolved)))
    (is (empty? (:material-layers resolved)))))
