(ns kotoba.render.pipeline-specs-test
  "`kotoba.render.pipeline-specs` loads a JVM classpath resource, so this
   test is JVM-only (`.clj`, not `.cljc`) — see that ns's docstring."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.pipeline-specs :as ps]))

(deftest has-eight-specs
  (is (= (count (ps/pipeline-specs)) 8)))

(deftest terrain-spec-matches-rust-source
  (let [s (ps/by-name "terrain")]
    (is (= (:shader s) "scene_terrain"))
    (is (= (:cull s) :back))
    (is (true? (:depth-write s)))
    (is (= (:depth-compare s) "less"))
    (is (= (:blend s) :none))))

(deftest sky-spec-uses-less-equal-depth
  (let [s (ps/by-name "sky")]
    (is (= (:depth-compare s) "less-equal"))))
