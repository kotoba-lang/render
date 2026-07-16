(ns kotoba.render.character-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.character :as character]
            [kotoba.render.mesh :as mesh]))

(def spec {:width 1.0 :depth 0.65 :height 2.1 :weapon-side :right})

(deftest humanoid-lods-are-deterministic-valid-and-reduced
  (let [high (character/character-mesh spec :high)
        low (character/character-mesh spec :low)]
    (is (= high (character/character-mesh spec :high)))
    (doseq [[positions normals uvs indices] [high low]]
      (is (= (count positions) (count normals)))
      (is (= (quot (count positions) 3) (quot (count uvs) 2)))
      (is (zero? (mod (count indices) 3)))
      (is (every? #(< -1 % (quot (count positions) 3)) indices))
      (is (= (quot (count positions) 3)
             (:vertex-count (mesh/loaded-mesh positions normals uvs indices)))))
    (is (> (count (nth high 3)) (count (nth low 3))))))

(deftest registration-retains-bounds-and-animation-ready-rig
  (let [registry (character/webgpu-registration :operator spec)]
    (is (= #{:operator-high :operator-low} (set (keys registry))))
    (is (every? #(= :mesh (:type %)) (vals registry)))
    (is (= :kotoba.render/character-rig-v2
           (get-in registry [:operator-high :rig :schema])))
    (is (= #{:weapon-hand :weapon-muzzle :back}
           (set (keys (get-in registry [:operator-high :rig :sockets])))))
    (is (> (get-in registry [:operator-high :triangle-count])
           (get-in registry [:operator-low :triangle-count])))
    (is (= {:min [-0.64 0.0 -0.42900000000000005]
            :max [0.64 2.3310000000000004 0.7150000000000001]}
           (:bounds (registry :operator-high))))))

(deftest weapon-ready-pose-traverses-shoulder-to-muzzle
  (let [high (character/character-assembly spec :high)
        ranges (filter #(#{:weapon :weapon-accent} (:role %))
                       (:material-ranges high))]
    (is (= 8 (count ranges)))
    (is (> (get-in (character/bounds spec) [:max 0]) 0.6)
        "angled rifle expands the lateral silhouette beyond the shoulders")))

(deftest high-lod-authors-readable-operator-and-rifle-detail
  (let [high (first (character/character-lods spec))
        low (second (character/character-lods spec))
        high-ranges (:material-ranges high)
        low-ranges (:material-ranges low)
        roles #(set (map :role %))]
    (is (= #{:fabric :skin :armour :armour-accent :visor :weapon :weapon-accent}
           (roles high-ranges)))
    (is (= #{:fabric :skin :armour :weapon :weapon-accent}
           (roles low-ranges)))
    (is (>= (count high-ranges) 27) "high LOD is a genuinely authored assembly")
    (is (>= (count low-ranges) 16) "low LOD retains character and rifle silhouette")
    (is (< (:triangle-count low) (* 0.70 (:triangle-count high))))
    (is (= (count (get-in (character/webgpu-registration :operator spec)
                          [:operator-high :mesh :indices]))
           (reduce + (map :index-count high-ranges))))
    (is (apply <= (map :index-start high-ranges)))))

(deftest registration-carries-executable-skin-streams
  (let [mesh (get-in (character/webgpu-registration :operator spec)
                     [:operator-high :mesh])
        n (count (:positions mesh))]
    (is (= n (count (:joints mesh)) (count (:weights mesh))))
    (is (>= (count (set (mapcat identity (:joints mesh)))) 20))
    (is (every? #(<= (Math/abs (- 1.0 (reduce + %))) 1.0e-9) (:weights mesh)))
    (is (every? #(every? (fn [j] (< -1 j (count character/joint-order))) %)
                (:joints mesh)))))

(deftest walk-palette-is-deterministic-and-actually-poses-limbs
  (let [rest (character/walk-palette spec 0.0 1.0)
        moving (character/walk-palette spec 0.25 1.0)]
    (is (= rest (character/walk-palette spec 0.0 1.0)))
    (is (= (count character/joint-order) (count moving)))
    (is (every? #(= 16 (count %)) moving))
    (is (= (first rest) (first moving)) "root remains identity")
    (is (= (nth rest 0) (nth moving 0)) "root motion stays outside the palette")
    (is (not= (nth rest 7) (nth moving 7)) "arm palette changes")
    (is (not= (nth moving 1) (nth moving 2)) "opposing limbs counter-swing")))

(deftest weapon-side-changes-readable-silhouette-metadata
  (let [right (character/rig-metadata spec)
        left (character/rig-metadata (assoc spec :weapon-side :left))]
    (is (pos? (first (get-in right [:sockets :weapon-hand]))))
    (is (neg? (first (get-in left [:sockets :weapon-hand]))))))

(deftest invalid-character-contract-is-rejected
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (character/character-mesh (assoc spec :height 0) :high)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (character/character-mesh spec :medium))))
