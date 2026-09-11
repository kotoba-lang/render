(ns kotoba.render.grounding-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.grounding :as grounding]))

(def base {:family :stylized :entity-id :operator-1
           :width 1.0 :depth 0.65 :height 1.82})

(deftest ellipse-is-valid-portable-indexed-geometry
  (let [[positions normals uvs indices] (grounding/ellipse-mesh 16)]
    (is (= (count positions) (count normals)))
    (is (= (quot (count positions) 3) (quot (count uvs) 2)))
    (is (= 16 (quot (count indices) 3)))
    (is (every? #(< -1 % 17) indices))))

(deftest near-grounding-has-body-and-foot-contact-with-restrained-evidence
  (let [resolved (grounding/grounding-presentation base)]
    (is (= :near (:selected-lod resolved)))
    (is (= 2 (get-in resolved [:evidence :contact-anchor-count])))
    (is (= 3 (count (:components resolved))))
    (is (true? (get-in resolved [:evidence :no-floating-shadow?])))
    (is (true? (get-in resolved [:evidence :no-oversized-shadow?])))
    (is (<= (get-in resolved [:evidence :max-opacity]) 0.34))
    (is (true? (get-in resolved [:budget :within-budget?])))
    (is (every? #(= :unlit-multiply (get-in % [:material :model]))
                (:components resolved)))
    (is (every? #(= {:mode :none :visual-only? true} (:collision %))
                (:components resolved)))))

(deftest raised-foot-drops-only-that-contact-patch
  (let [resolved (grounding/grounding-presentation
                  (assoc base :foot-offsets {:left 0.0 :right 0.3}))]
    (is (= 1 (get-in resolved [:evidence :contact-anchor-count])))
    (is (= 2 (count (:components resolved))))
    (is (= #{:left} (set (map :side (filter :contact? (:anchors resolved))))))))

(deftest distance-lod-reduces-and-then-removes-contact-geometry
  (let [near (grounding/grounding-presentation base)
        mid (grounding/grounding-presentation (assoc base :distance-in-heights 20.0))
        far (grounding/grounding-presentation (assoc base :distance-in-heights 50.0))]
    (is (= [:near :mid :far] (mapv :selected-lod [near mid far])))
    (is (> (get-in near [:budget :triangles]) (get-in mid [:budget :triangles])
           (get-in far [:budget :triangles])))
    (is (= [3 1 0] (mapv #(count (:components %)) [near mid far])))
    (is (empty? (:mesh-library far)))))

(deftest photoreal-boundary-is-explicitly-unsupported
  (let [resolved (grounding/grounding-presentation (assoc base :family :photoreal))]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unsupported-future (:quality-claim resolved)))
    (is (empty? (:components resolved)))
    (is (empty? (:mesh-library resolved)))))
