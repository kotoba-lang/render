(ns kotoba.render.splat-test
  "Ported from kami-render/src/splat.rs's #[cfg(test)] mod tests (data-size
   assertions dropped — no #[repr(C)] byte layout in CLJC data)."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.splat :as splat]))

(defn- gs [pos]
  {:position pos :opacity 1.0 :scale [0.1 0.1 0.1] :rotation [1.0 0.0 0.0 0.0] :sh-dc [0.5 0.5 0.5]})

(deftest cloud-empty
  (let [c (splat/new-cloud)]
    (is (= (splat/count-splats c) 0))
    (is (= (splat/bounds c) [[0.0 0.0 0.0] [0.0 0.0 0.0]]))))

(deftest cloud-cull
  (let [c (update (splat/new-cloud) :splats conj (gs [0.0 0.0 0.0]) (gs [100.0 0.0 0.0]))
        visible (splat/cull-indices c [0.0 0.0 0.0] 50.0)]
    (is (= visible [0]))))

(deftest cloud-bounds
  (let [c (update (splat/new-cloud) :splats conj
                   (assoc (gs [-1.0 2.0 3.0]) :opacity 0.0 :scale [0.0 0.0 0.0])
                   (assoc (gs [5.0 -1.0 0.0]) :opacity 0.0 :scale [0.0 0.0 0.0]))
        [mn mx] (splat/bounds c)]
    (is (= mn [-1.0 -1.0 0.0]))
    (is (= mx [5.0 2.0 3.0]))))

(deftest sh-coef-count-defaults-to-degree-0
  (is (= (splat/sh-coef-count (splat/new-cloud)) 1)))
