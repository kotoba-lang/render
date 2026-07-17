(ns kotoba.render.material-readability-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.material :as material]
            [kotoba.render.material-readability :as readability]))

(deftest semantic-materials-are-real-valid-and-readable
  (doseq [team readability/teams
          :let [profile (readability/material-profile {:team team})]]
    (is (= (set readability/roles) (set (keys (:materials profile)))))
    (is (every? material/valid? (vals (:materials profile))))
    (is (true? (get-in profile [:evidence :portable-pbr-valid?])))
    (is (true? (get-in profile [:evidence :value-separation-passes?])))
    (is (true? (get-in profile [:evidence :color-blind-readable?])))
    (is (= [:hue :value :emissive :pattern]
           (get-in profile [:evidence :redundant-team-channels])))))

(deftest character-source-roles-map-to-semantic-records
  (let [profile (readability/material-profile {:team :blue})
        palette (readability/character-palette profile)]
    (is (= (get-in profile [:materials :cloth]) (:fabric palette)))
    (is (= (get-in profile [:materials :metal]) (:armour palette)))
    (is (= (get-in profile [:materials :accent]) (:weapon-accent palette)))
    (is (every? map? (vals palette)))))

(deftest distance-lod-reduces-shading-complexity
  (let [near (readability/material-profile {:distance-in-heights 2.0})
        mid (readability/material-profile {:distance-in-heights 20.0})
        far (readability/material-profile {:distance-in-heights 50.0})]
    (is (= [:near :mid :far] (mapv :selected-lod [near mid far])))
    (is (= [3 2 2] (mapv #(get-in % [:materials :skin :toon-bands]) [near mid far])))
    (is (> (get-in near [:materials :metal :specular-strength])
           (get-in mid [:materials :metal :specular-strength])
           (get-in far [:materials :metal :specular-strength])))))

(deftest team-palette-is-redundant-not-red-green-only
  (let [blue (readability/material-profile {:team :blue})
        orange (readability/material-profile {:team :orange})]
    (is (not= (get-in blue [:materials :accent :base-color])
              (get-in orange [:materials :accent :base-color])))
    (is (not= (get-in blue [:materials :accent :team-pattern])
              (get-in orange [:materials :accent :team-pattern])))
    (is (not= (get-in blue [:materials :accent :emissive])
              (get-in orange [:materials :accent :emissive])))))

(deftest photoreal-boundary-does-not-claim-quality
  (let [profile (readability/material-profile {:family :photoreal})]
    (is (= :boundary-only (:implementation-status profile)))
    (is (= :unsupported-future (:quality-claim profile)))
    (is (empty? (:materials profile)))))
