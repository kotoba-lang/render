(ns kotoba.render.raytrace-test
  "Ported from kami-render/src/raytrace.rs's #[cfg(test)] mod tests
   (the byte-layout assertion is dropped — no #[repr(C)] in CLJC data)."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.raytrace :as rt]))

(deftest globals-dims-and-cam-pos
  (let [g (rt/rt-globals (vec (repeat 16 0.0)) [1.0 2.0 3.0] 1280 720)]
    (is (= (nth (:dims g) 0) 1280))
    (is (= (:cam-pos g) [1.0 2.0 3.0 1.0]))))
