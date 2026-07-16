(ns kotoba.render.foliage-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.foliage :as foliage]
            [kotoba.render.procedural :as procedural]))

(deftest fixed-foliage-instance-abi
  (is (= [0.43 0.28 1.25 1.7]
         (foliage/gpu-instance {:alpha-cutoff 0.43 :wind-strength 0.28
                                :wind-phase 1.25 :wind-frequency 1.7})))
  (is (= -1.0 (first (foliage/gpu-instance {:alpha-mode :opaque}))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (foliage/normalize {:alpha-cutoff 1.1}))))

(deftest deterministic-wind-reference-pins-base-and-moves-tip
  (let [world {:direction [3.0 4.0] :time 0.75 :speed 2.0}
        material {:wind-strength 0.4 :wind-phase 0.25 :wind-frequency 1.3}]
    (is (= [0.0 0.0 0.0] (foliage/wind-offset world material 0.0)))
    (is (= (foliage/wind-offset world material 1.0)
           (foliage/wind-offset world material 1.0)))
    (is (not= [0.0 0.0 0.0] (foliage/wind-offset world material 1.0)))))

(deftest alpha-card-bakers-are-deterministic-and-actually-cut-out
  (doseq [kind [:leaf-card :grass-blade]
          :let [options {:kind kind :width 16 :height 16 :seed 77 :scale 4}
                a (procedural/bake-pbr-material options)
                b (procedural/bake-pbr-material options)
                alpha (map #(nth % 3) (partition 4 (get-in a [:albedo :data])))] ]
    (is (= a b))
    (is (some zero? alpha))
    (is (some #{255} alpha))))
