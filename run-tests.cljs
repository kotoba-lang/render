#!/usr/bin/env nbb
;; nbb --classpath src:test run-tests.cljs   (from the repository root)
;;
;; The ClojureScript side. Not the whole suite: what runs here is what has
;; been checked to run here. `splat_loader_test.clj` and the bakers are `.clj`
;; -- and that is exactly how `splat-loader` came to return garbage on this
;; runtime while 245 JVM tests passed, so anything added to `test/` as `.cljc`
;; belongs in this list.
(ns run-tests
  (:require [cljs.test :refer [run-tests]]
            [kotoba.render.cubemap-test]
            [kotoba.render.splat-loader-portable-test]
            [kotoba.render.splat-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (when-not (cljs.test/successful? m)
    (js/process.exit 1)))

(run-tests 'kotoba.render.cubemap-test
           'kotoba.render.splat-loader-portable-test
           'kotoba.render.splat-test)
