(ns kotoba.render.road-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [kotoba.render.road :as road]))

(def terrain {:patch [0 0] :size 64.0 :base-segments 32
              :amplitude 7.0 :seed 2654435769 :skirt-depth 2.0})
(def spec {:path [[0.0 0.0] [24.0 0.0] [24.0 20.0]]
           :width 8.0 :shoulder 1.5 :camber 0.16 :shoulder-drop 0.1
           :clearance 0.04 :uv-scale 5.0 :base-subdivisions 8 :miter-limit 1.75
           :terrain terrain})

(deftest terrain-following-ribbon-is-deterministic-and-indexed
  (doseq [detail road/details]
    (let [[positions normals uvs indices :as mesh] (road/road-mesh spec detail)
          vertices (quot (count positions) 3)]
      (is (= mesh (road/road-mesh spec detail)))
      (is (= vertices (quot (count normals) 3) (quot (count uvs) 2)))
      (is (every? #(< -1 % vertices) indices))
      (is (every? number? positions)))))

(deftest polyline-junction-is-one-shared-row-with-continuous-uv
  (let [[positions _ uvs indices] (road/road-mesh spec :high)
        row-width 5
        expected-rows (inc (* 2 (:base-subdivisions spec)))
        junction-row (:base-subdivisions spec)
        junction-v (second (nth (partition 2 uvs) (* junction-row row-width)))
        junction-centers (filter (fn [[x _ z]] (and (= 24.0 x) (= 0.0 z)))
                                 (partition 3 positions))]
    (is (= (* expected-rows row-width 3) (count positions)))
    (is (= (* (dec expected-rows) (dec row-width) 6) (count indices)))
    (is (= (/ 24.0 (:uv-scale spec)) junction-v))
    (is (= 1 (count junction-centers))
        "junction has one crown vertex, not overlapping segment caps")))

(deftest cross-section-has-camber-and-shoulders-over-heightfield
  (let [[positions _ _ _] (road/road-mesh spec :high)
        [left-shoulder left-edge crown right-edge right-shoulder]
        (take 5 (partition 3 positions))]
    (is (> (second crown) (+ (road/terrain-height terrain (first crown) (nth crown 2)) 0.15)))
    (is (< (- (second left-shoulder)
              (road/terrain-height terrain (first left-shoulder) (nth left-shoulder 2)))
           (- (second left-edge)
              (road/terrain-height terrain (first left-edge) (nth left-edge 2)))))
    (is (< (- (second right-shoulder)
              (road/terrain-height terrain (first right-shoulder) (nth right-shoulder 2)))
           (- (second right-edge)
              (road/terrain-height terrain (first right-edge) (nth right-edge 2)))))))

(deftest lod-reduces-centerline-density-with-stable-endpoints
  (let [lods (road/road-lods spec)
        counts (mapv :triangle-count lods)
        endpoints (mapv (fn [{:keys [mesh]}]
                          (let [positions (partition 3 (first mesh))]
                            [(vec (take 5 positions)) (vec (take-last 5 positions))]))
                        lods)]
    (is (apply > counts))
    (is (apply = endpoints))))

(deftest registration-exposes-portable-high-medium-low-meshes
  (let [registry (road/webgpu-registration :coast spec)]
    (is (= #{:coast-surface-high :coast-surface-medium :coast-surface-low
             :coast-shoulder-high :coast-shoulder-medium :coast-shoulder-low}
           (set (keys registry))))
    (is (every? #(= :mesh (:type %)) (vals registry)))
    (is (apply > (mapv #(get-in registry [% :triangle-count])
                       [:coast-surface-high :coast-surface-medium :coast-surface-low])))))

(deftest surface-and-shoulders-share-byte-equal-boundaries
  (let [{surface :surface shoulder :shoulder} (road/road-mesh-parts spec :high)
        surface-positions (set (partition 3 (first surface)))
        shoulder-positions (set (partition 3 (first shoulder)))
        shared (set/intersection surface-positions shoulder-positions)
        expected (* 2 (inc (* 2 (:base-subdivisions spec))))]
    (is (= expected (count shared)))))

(deftest right-angle-miter-preserves-width-without-unbounded-spike
  (let [limit 1.5
        corner-spec (assoc spec :path [[0.0 0.0] [10.0 0.0] [10.0 10.0]]
                          :base-subdivisions 4 :miter-limit limit)
        [positions _ _ _] (road/road-mesh corner-spec :high)
        rows (partition 5 (partition 3 positions))
        corner (nth rows 4)
        [outer-left _ center _ outer-right] corner
        half-total (+ (/ (:width spec) 2.0) (:shoulder spec))
        radius (fn [[x _ z]] (Math/sqrt (+ (* (- x 10.0) (- x 10.0)) (* z z))))]
    (is (<= (radius outer-left) (+ (* half-total limit) 1.0e-9)))
    (is (<= (radius outer-right) (+ (* half-total limit) 1.0e-9)))
    (is (= [10.0 (second center) 0.0] center))))
