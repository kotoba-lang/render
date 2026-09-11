(ns kotoba.render.instance-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.instance :as instance]))

(deftest uv-transform-contract
  (is (= [1.0 1.0 0.0 0.0] (instance/normalize-uv-transform nil)))
  (is (= [2.0 3.0 -0.25 4.0]
         (instance/normalize-uv-transform [2 3 -0.25 4])))
  (doseq [bad [[1 2 3] [0 1 0 0] [-1 1 0 0]
               [1 1 ##Inf 0] [1 1 ##NaN 0]]]
    (testing (str bad)
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (instance/normalize-uv-transform bad))))))
