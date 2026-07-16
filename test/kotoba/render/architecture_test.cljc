(ns kotoba.render.architecture-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.architecture :as architecture]))

(deftest detail-budgets-are-bounded-and-monotonic
  (let [spec {:variant :depot :width 11.0 :depth 8.0 :height 6.5}
        high (architecture/building-parts spec :high)
        medium (architecture/building-parts spec :medium)
        low (architecture/building-parts spec :low)]
    (is (> (count high) (count medium) (count low)))
    (is (<= (count high) 32))
    (is (<= (count medium) 12))
    (is (= {:draws 2 :triangles 24 :roles {:wall 1 :roof 1}}
           (architecture/budget low)))
    (is (some #(= :window (:role %)) high))
    (is (some #(= :utility (:role %)) high))
    (let [window-offsets (map :offset (filter #(= :window (:role %)) high))]
      (is (some #(neg? (first %)) window-offsets) "left facade is populated")
      (is (some #(pos? (first %)) window-offsets) "right facade is populated")
      (is (some #(neg? (nth % 2)) window-offsets) "rear facade is populated")
      (is (some #(pos? (nth % 2)) window-offsets) "front facade is populated"))))

(deftest parts-are-portable-render-data
  (doseq [variant architecture/variants
          detail architecture/details
          part (architecture/building-parts
                {:variant variant :width 8.0 :depth 5.0 :height 5.5} detail)]
    (is (= :box (:geo part)))
    (is (= 3 (count (:offset part))))
    (is (every? pos? (:size part)))
    (is (map? (:material part)))
    (is (contains? (:material part) :roughness))))

(deftest invalid-authoring-is-rejected
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (architecture/building-parts {:variant :castle} :high)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (architecture/building-parts {:variant :depot :width 0 :depth 2 :height 3} :high)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (architecture/building-parts {:variant :depot} :cinematic))))
