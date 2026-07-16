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
    (is (= :kotoba.render/character-rig-v1
           (get-in registry [:operator-high :rig :schema])))
    (is (= #{:weapon-hand :weapon-muzzle :back}
           (set (keys (get-in registry [:operator-high :rig :sockets])))))
    (is (> (get-in registry [:operator-high :triangle-count])
           (get-in registry [:operator-low :triangle-count])))
    (is (= {:min [-0.56 0.0 -0.325] :max [0.56 2.1 0.48750000000000004]}
           (:bounds (registry :operator-high))))))

(deftest registration-carries-executable-skin-streams
  (let [mesh (get-in (character/webgpu-registration :operator spec)
                     [:operator-high :mesh])
        n (count (:positions mesh))]
    (is (= n (count (:joints mesh)) (count (:weights mesh))))
    (is (= #{0 1 2 3 4} (set (map first (:joints mesh)))))
    (is (every? #(= [1.0 0.0 0.0 0.0] %) (:weights mesh)))
    (is (every? #(every? (fn [j] (< -1 j (count character/joint-order))) %)
                (:joints mesh)))))

(deftest walk-palette-is-deterministic-and-actually-poses-limbs
  (let [rest (character/walk-palette spec 0.0 1.0)
        moving (character/walk-palette spec 0.25 1.0)]
    (is (= rest (character/walk-palette spec 0.0 1.0)))
    (is (= (count character/joint-order) (count moving)))
    (is (every? #(= 16 (count %)) moving))
    (is (= (first rest) (first moving)) "root remains identity")
    (is (not= (nth rest 1) (nth moving 1)) "arm palette changes")
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
