(ns kotoba.render.quality-test (:require [clojure.test :refer [deftest is]] [kotoba.render.lod :as lod] [kotoba.render.material :as material] [kotoba.render.post-process :as post] [kotoba.render.quality :as quality] [kotoba.render.shadow :as shadow]))
(deftest pbr-material-contract
  (is (material/valid? {}))
  (is (= [:invalid-metallic] (material/errors {:metallic 1.2})))
  (is (= [0 0 1 1] (:flags (material/gpu-uniform {})))))
(deftest cascaded-shadow-plan
  (let [plan (shadow/plan {:quality :high :near 1.0 :far 100.0 :lambda 0.5})]
    (is (= 4 (count (:passes plan)))) (is (= 100.0 (last (:splits plan))))
    (is (= {:x 0.5 :y 0.5 :width 0.5 :height 0.5} (:viewport (last (:passes plan))))))
  (is (false? (:enabled? (shadow/plan {:quality :off})))))
(deftest post-process-frame-graph
  (is (= [:ssao :bloom :tone-map :taa :color-grade] (mapv :kind (:passes (post/plan {:quality :high})))))
  (is (= 2.0 (get-in (post/plan {:quality :low :overrides {:tone-map {:exposure 2.0}}}) [:passes 0 :params :exposure]))))
(deftest lod-and-density
  (let [levels [{:id :lod0 :min-pixels 100} {:id :lod1 :min-pixels 25} {:id :lod2 :min-pixels 0}]]
    (is (> (lod/projected-radius-px 1 10 (/ #?(:clj Math/PI :cljs js/Math.PI) 3) 1080) 90))
    (is (= :lod1 (:id (lod/select-level levels 50))))
    (is (= :lod0 (:id (lod/select-level-stable levels 95 :lod0 0.1)))))
  (let [result (lod/density-plan [{:id :far :distance 20 :triangles 40} {:id :hero :distance 10 :importance 5 :triangles 70} {:id :near :distance 2 :triangles 40}] {:max-instances 2 :max-triangles 110})]
    (is (= [:hero :near] (mapv :id (:instances result)))) (is (= 1 (:culled-count result)))))
(deftest unified-quality-profile
  (let [plan (quality/render-plan :cinematic)]
    (is (= :kotoba.render/quality-v1 (:schema plan))) (is (= 4 (count (get-in plan [:shadow :passes]))))
    (is (= :ssao (get-in plan [:post-process :passes 0 :kind]))) (is (= 60000000 (get-in plan [:lod :max-visible-triangles])))))
