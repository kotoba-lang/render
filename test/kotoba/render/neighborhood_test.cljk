(ns kotoba.render.neighborhood-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.neighborhood :as neighborhood]))

(def base {:family :stylized :entity-id :junction-a :seed 31415
           :road-width 8.0 :sidewalk-width 2.2 :extent 48.0
           :terrain {:size 128.0 :base-segments 32 :amplitude 0.0 :seed 0}})

(deftest junctions-have-coherent-actual-road-and-marking-meshes
  (doseq [junction [:t :cross]
          :let [resolved (neighborhood/neighborhood (assoc base :junction junction))
                arms (get-in resolved [:mesh-library :roads :arms])]]
    (is (= (if (= junction :cross) 4 3) (count arms)))
    (is (seq (get-in resolved [:mesh-library :roads :junction :mesh])))
    (doseq [[_ parts] arms part [:surface :shoulder :marking]]
      (is (seq (get-in parts [part :mesh])))
      (is (number? (get-in parts [part :triangle-count]))))))

(deftest streetscape-shells-facades-and-anchor-zones-are-consumable
  (let [resolved (neighborhood/neighborhood (assoc base :tier :hero))]
    (is (= #{:curb :sidewalk :verge} (set (map :semantic (:streetscape resolved)))))
    (is (every? #(seq (get-in % [:shell :mesh-lods 0 :mesh])) (:buildings resolved)))
    (is (every? #(seq (get-in % [:facade :parts])) (:buildings resolved)))
    (is (= #{:foreground-props :vegetation-cluster}
           (set (map :kind (:anchor-zones resolved)))))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %))
                (:anchor-zones resolved)))))

(deftest grounding-skyline-and-landmark-evidence-fails-closed
  (doseq [junction [:t :cross]
          :let [resolved (neighborhood/neighborhood (assoc base :junction junction :tier :hero))
                evidence (:evidence resolved)]]
    (is (true? (:all-shells-grounded? evidence)))
    (is (true? (:skyline-within-safe-height? evidence)))
    (is (true? (:no-floating-landmark? evidence)))
    (is (true? (:no-clipped-landmark? evidence)))
    (is (true? (get-in evidence [:framing :requires-ground?])))
    (is (true? (get-in evidence [:framing :junction-context-required?])))))

(deftest lod-budgets-are-bounded
  (let [[hero mid background] (neighborhood/neighborhood-lods base)]
    (doseq [resolved [hero mid background]]
      (is (true? (get-in resolved [:budget :within-budget?]))))
    (is (> (get-in hero [:budget :triangles])
           (get-in mid [:budget :triangles])
           (get-in background [:budget :triangles])))))

(deftest photoreal-boundary-is-future
  (let [resolved (neighborhood/neighborhood (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unsupported-future (:quality-claim resolved)))
    (is (empty? (:buildings resolved)))
    (is (empty? (:mesh-library resolved)))))

(deftest safe-height-resolves-actual-shell-mesh-and-facade-output
  (let [resolved (neighborhood/neighborhood (assoc base :tier :hero :safe-height 9.0))]
    (is (every? #(<= (get-in % [:shell :collision :size 1]) 9.0) (:buildings resolved)))
    (is (every? #(<= % 9.0) (get-in resolved [:evidence :resolved-facade-extents])))
    (is (every? #(<= (get-in % [:shell :mesh-lods 0 :bounds :height]) 9.0)
                (:buildings resolved)))
    (is (true? (get-in resolved [:evidence :skyline-within-safe-height?])))
    (is (true? (get-in resolved [:evidence :no-clipped-landmark?])))))

(deftest anchor-zones-carry-directly-renderable-authored-descriptors
  (let [zones (:anchor-zones (neighborhood/neighborhood base))
        descriptors (mapcat :descriptors zones)]
    (is (= 3 (count descriptors)))
    (is (every? #(seq (get-in % [:geometry :mesh])) descriptors))
    (is (every? #(map? (:material %)) descriptors))
    (is (every? #(= 3 (count (get-in % [:transform :offset]))) descriptors))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %)) descriptors))
    (is (= descriptors
           (mapcat :descriptors (:anchor-zones (neighborhood/neighborhood base)))))))
