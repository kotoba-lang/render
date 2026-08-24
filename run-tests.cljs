#!/usr/bin/env nbb
;; nbb --classpath src:test run-tests.cljs   (from the repository root)
;;
;; Every `.cljc` namespace under test/ (39 of them). `.cljc` is a claim that
;; the code runs on both runtimes; this file is where the claim is checked.
;;
;; It used to name four. The header said "anything added to `test/` as `.cljc`
;; belongs in this list" and thirty-five were added without being added here,
;; so the JVM suite was the only thing running them. That hid two real
;; defects, both silent, both found the first time these ran under nbb
;; (2026-08-24):
;;
;;   gltf/base64-decode  returned `[]` for ALL input on ClojureScript --
;;                       `(int \A)` is 0 there, so every byte matched the
;;                       padding test and was skipped. A glTF `data:` URI
;;                       decoded to an empty buffer without throwing.
;;   uastc_test          `(bit-shift-right v 32)` is `v` on ClojureScript
;;                       (JS shifts mod 32), so the test's own KTX2 builder
;;                       wrote the low dword into the high dword.
;;
;; The five `.clj` tests are NOT missing from this list: they are JVM-only on
;; purpose (`environment_bake`, `pipeline_specs`, `probe`, `probe_mesh`, and
;; `splat_loader`, which reads files and is covered here by
;; `splat_loader_portable_test`). Split by which runtime a test can run on,
;; not by subject -- root ADR-2608730000.
;;
;; scripts/verify-cljs-runner-completeness.cljs in the superproject is the
;; ratchet that measures this list against the directory.
(ns run-tests
  (:require [cljs.test :refer [run-tests]]
            [kotoba.render.architecture-test]
            [kotoba.render.asset-test]
            [kotoba.render.atmosphere-test]
            [kotoba.render.building-test]
            [kotoba.render.camera-test]
            [kotoba.render.character-test]
            [kotoba.render.close-character-test]
            [kotoba.render.cubemap-test]
            [kotoba.render.decal-test]
            [kotoba.render.detail-kit-test]
            [kotoba.render.environment-test]
            [kotoba.render.facade-test]
            [kotoba.render.foliage-test]
            [kotoba.render.foreground-density-test]
            [kotoba.render.gltf-test]
            [kotoba.render.grounding-test]
            [kotoba.render.instance-test]
            [kotoba.render.logo-test]
            [kotoba.render.material-readability-test]
            [kotoba.render.mesh-test]
            [kotoba.render.meshopt-test]
            [kotoba.render.neighborhood-test]
            [kotoba.render.procedural-test]
            [kotoba.render.quality-test]
            [kotoba.render.raytrace-test]
            [kotoba.render.road-test]
            [kotoba.render.settlement-test]
            [kotoba.render.sh-test]
            [kotoba.render.splat-loader-portable-test]
            [kotoba.render.splat-raster-test]
            [kotoba.render.splat-test]
            [kotoba.render.streaming-test]
            [kotoba.render.terrain-biome-test]
            [kotoba.render.terrain-test]
            [kotoba.render.texture-test]
            [kotoba.render.uastc-test]
            [kotoba.render.vegetation-cluster-test]
            [kotoba.render.vegetation-test]
            [kotoba.render.world-presets-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println "SUMMARY" (:test m) "tests" (:pass m) "pass" (:fail m) "fail" (:error m) "error")
  (when-not (cljs.test/successful? m)
    (js/process.exit 1)))

(run-tests
 'kotoba.render.architecture-test
 'kotoba.render.asset-test
 'kotoba.render.atmosphere-test
 'kotoba.render.building-test
 'kotoba.render.camera-test
 'kotoba.render.character-test
 'kotoba.render.close-character-test
 'kotoba.render.cubemap-test
 'kotoba.render.decal-test
 'kotoba.render.detail-kit-test
 'kotoba.render.environment-test
 'kotoba.render.facade-test
 'kotoba.render.foliage-test
 'kotoba.render.foreground-density-test
 'kotoba.render.gltf-test
 'kotoba.render.grounding-test
 'kotoba.render.instance-test
 'kotoba.render.logo-test
 'kotoba.render.material-readability-test
 'kotoba.render.mesh-test
 'kotoba.render.meshopt-test
 'kotoba.render.neighborhood-test
 'kotoba.render.procedural-test
 'kotoba.render.quality-test
 'kotoba.render.raytrace-test
 'kotoba.render.road-test
 'kotoba.render.settlement-test
 'kotoba.render.sh-test
 'kotoba.render.splat-loader-portable-test
 'kotoba.render.splat-raster-test
 'kotoba.render.splat-test
 'kotoba.render.streaming-test
 'kotoba.render.terrain-biome-test
 'kotoba.render.terrain-test
 'kotoba.render.texture-test
 'kotoba.render.uastc-test
 'kotoba.render.vegetation-cluster-test
 'kotoba.render.vegetation-test
 'kotoba.render.world-presets-test)
