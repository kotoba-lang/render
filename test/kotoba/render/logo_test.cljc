(ns kotoba.render.logo-test
  "Ported from kami-render/src/logo.rs's #[cfg(test)] mod tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.render.logo :as logo]))

(deftest splash-lifecycle
  (let [s0 (logo/splash-screen)]
    (is (not (logo/done? s0)))
    (is (< (logo/opacity s0) 0.1))
    (let [s1 (logo/tick s0 0.5)]
      (is (< (Math/abs (- (logo/opacity s1) 1.0)) 0.01))
      (let [s2 (logo/tick s1 1.0)]
        (is (< (Math/abs (- (logo/opacity s2) 1.0)) 0.01))
        (let [s3 (logo/tick s2 0.4)]
          (is (< (logo/opacity s3) 1.0))
          (let [s4 (logo/tick s3 0.2)]
            (is (logo/done? s4))))))))

(deftest logo-svg-valid
  (is (str/includes? logo/logo-svg "KAMI ENGINE"))
  (is (str/includes? logo/logo-svg "<svg")))
