(ns kotoba.render.streaming-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.streaming :as streaming]))

(def policy {:cell-size 10.0 :classes {:prop {:enter 10.0 :exit 12.0 :lod-hysteresis 1.0}}})
(def assets [{:id :a :stream/class :prop :pos [10.0 0.0 0.0] :stream/bytes 40 :stream/draws 1
              :stream/lods [{:id :high :max-distance 5.0 :bytes 40 :draws 1 :triangles 100}
                            {:id :low :max-distance 10.0 :bytes 12 :draws 1 :triangles 20}]}
             {:id :b :stream/class :prop :pos [30.0 0.0 0.0]}])

(deftest residency-and-level-hysteresis
  (let [at-origin (streaming/step policy nil [0 0 0] assets)
        just-outside (streaming/step policy at-origin [-1 0 0] assets)
        evicted (streaming/step policy just-outside [-3 0 0] assets)]
    (is (= [:a] (get-in at-origin [:state :resident-ids])))
    (is (= :low (get-in at-origin [:state :levels :a])))
    (is (= [:a] (get-in just-outside [:state :resident-ids])) "exit radius prevents boundary churn")
    (is (empty? (get-in evicted [:state :resident-ids])))))

(deftest lod-boundary-does-not-flap
  (let [near (streaming/step policy nil [5.2 0 0] assets)
        across (streaming/step policy near [4.8 0 0] assets)
        committed (streaming/step policy across [3.8 0 0] assets)]
    (is (= :high (get-in near [:state :levels :a])))
    (is (= :high (get-in across [:state :levels :a])))
    (is (= :low (get-in committed [:state :levels :a])))))

(deftest teleport-replaces-resident-set-and-reports-budget
  (let [origin (streaming/step policy nil [0 0 0] assets)
        teleported (streaming/step policy origin [30 0 0] assets)]
    (is (= [:b] (get-in teleported [:state :resident-ids])))
    (is (= [3 0] (get-in teleported [:evidence :camera-cell])))
    (is (= {:prop 1} (get-in teleported [:evidence :resident-by-class])))
    (is (= 1 (get-in teleported [:evidence :resident-count])))
    (is (= 1 (get-in teleported [:evidence :culled-count])))
    (is (= 0 (get-in teleported [:evidence :resident-bytes])))
    (is (= 12 (get-in origin [:evidence :resident-bytes])))))

(deftest camera-cell-snaps-floating-point-noise-only
  (is (= [0 0] (streaming/camera-cell {:cell-size 64.0} [0.0 0.0 -2.6e-13])))
  (is (= [0 -1] (streaming/camera-cell {:cell-size 64.0} [0.0 0.0 -1.0e-6])))
  (is (= [1 0] (streaming/camera-cell {:cell-size 64.0} [64.00000000001 0.0 0.0]))))
