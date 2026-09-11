(ns kotoba.render.atmosphere-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.atmosphere :as atmosphere]))

(deftest normalized-contract
  (let [a (atmosphere/atmosphere
           {:rayleigh 99 :mie -1 :sun-direction [0 4 0]
            :clouds {:coverage 2 :density -3 :seed 9}})]
    (is (= :kotoba.render/atmosphere-v1 (:schema a)))
    (is (= [0.0 1.0 0.0] (:sun-direction a)))
    (is (= 8.0 (:rayleigh a)))
    (is (= 0.0 (:mie a)))
    (is (= 1.0 (get-in a [:clouds :coverage])))
    (is (= 0.0 (get-in a [:clouds :density])))
    (is (= 9.0 (get-in a [:clouds :seed])))))

(deftest uniform-abi
  (testing "eight aligned vec4s are deterministic"
    (let [a {:sun-direction [0 -2 0] :clouds {:seed 73}}
          first-pack (atmosphere/uniform-values a)]
      (is (= 32 (count first-pack)))
      (is (= first-pack (atmosphere/uniform-values a)))
      (is (= [0.0 -1.0 0.0] (subvec first-pack 12 15)))
      (is (= 73.0 (nth first-pack 24))))))
