(ns kotoba.render.close-character-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.close-character :as close]
            [kotoba.render.material :as material]))

(deftest semantic-materials-are-valid-and-contrasted
  (let [resolved (close/presentation {})]
    (is (= (set close/roles) (set (keys (:materials resolved)))))
    (is (every? material/valid? (vals (:materials resolved))))
    (is (true? (get-in resolved [:evidence :portable-pbr-valid?])))
    (is (true? (get-in resolved [:evidence :contrast-passes?])))
    (is (true? (get-in resolved [:evidence :occlusion-passes?])))))

(deftest scale-selects-close-gameplay-and-crowd-lod
  (let [profiles (mapv #(close/presentation {:projected-character-height-px %})
                       [420.0 160.0 44.0])]
    (is (= [:close :gameplay :crowd] (mapv :selected-lod profiles)))
    (is (= [3 2 2] (mapv #(get-in % [:materials :face :toon-bands]) profiles)))
    (is (> (get-in (profiles 0) [:materials :optic :specular-strength])
           (get-in (profiles 1) [:materials :optic :specular-strength])
           (get-in (profiles 2) [:materials :optic :specular-strength])))))

(deftest character-palette-maps-face-and-weapon-semantics
  (let [palette (close/character-palette (close/presentation {}))]
    (is (= (:skin palette) (:face palette)))
    (is (= (:weapon palette) (:weapon-receiver palette)))
    (is (= (:visor palette) (:optic palette)))
    (is (every? map? (vals palette)))))

(deftest production-framing-passes-contextual-grounded-capture
  (let [evidence (close/production-framing-evidence
                  {:ground-ratio 0.31 :context-ratio 0.46 :sky-ratio 0.33
                   :floating-landmark-ratio 0.01 :subject-ratio 0.28
                   :subject-ground-contact? true})]
    (is (true? (:passes? evidence)))
    (is (empty? (:failures evidence)))
    (is (every? true? (vals (:checks evidence))))))

(deftest production-framing-rejects-sky-only-floating-landmark
  (let [evidence (close/production-framing-evidence
                  {:ground-ratio 0.02 :context-ratio 0.08 :sky-ratio 0.88
                   :floating-landmark-ratio 0.32 :subject-ratio 0.04
                   :subject-ground-contact? false})]
    (is (false? (:passes? evidence)))
    (is (= #{:ground-present? :environment-context-present? :not-sky-only?
             :no-floating-landmark? :subject-scale-valid? :subject-grounded?}
           (set (:failures evidence))))))

(deftest photoreal-boundary-is-future
  (let [resolved (close/presentation {:family :photoreal})]
    (is (= :boundary-only (:implementation-status resolved)))
    (is (= :unsupported-future (:quality-claim resolved)))
    (is (empty? (:materials resolved)))))
