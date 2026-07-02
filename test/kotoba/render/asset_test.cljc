(ns kotoba.render.asset-test
  "Ported from kami-render/src/asset.rs's #[cfg(test)] mod tests."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.asset :as asset]))

(deftest cache-insert-and-get
  (let [c (-> (asset/new-cache)
              (asset/insert-mesh "cube" 0 36)
              (asset/insert-material "default" 0))]
    (is (= (asset/get-mesh c "cube") [0 36]))
    (is (= (asset/get-material c "default") 0))
    (is (nil? (asset/get-mesh c "missing")))
    (is (asset/has-mesh? c "cube"))
    (is (= (asset/mesh-count c) 1))))
