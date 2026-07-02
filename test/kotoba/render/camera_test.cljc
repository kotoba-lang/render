(ns kotoba.render.camera-test
  "kami-render/src/camera.rs carried no #[cfg(test)] block, so these are new
   sanity/parity tests written against the documented Rust semantics (glam's
   look_at_rh / perspective_rh / orthographic_rh, wgpu depth-range [0,1])."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.camera :as cam]))

(defn- close? [a b eps] (< (Math/abs (double (- a b))) eps))

(deftest ortho-matrix-symmetric-box
  (let [m (cam/ortho-matrix 10.0 20.0 0.1 100.0)]
    (is (= (count m) 16))
    ;; column 0: scale x = 2/width
    (is (close? (nth m 0) 0.2 1e-6))
    ;; column 1: scale y = 2/height
    (is (close? (nth m 5) 0.1 1e-6))))

(deftest camera-default-pose
  (let [c (cam/camera (/ 16.0 9.0))]
    (is (= (:position c) [0.0 10.0 20.0]))
    (is (= (:target c) [0.0 0.0 0.0]))
    (is (= (:kind (:mode c)) :perspective))))

(deftest camera-uniform-perspective-shape
  (let [c (cam/camera 1.777)
        u (cam/camera-uniform c)]
    (is (= (count (:view u)) 16))
    (is (= (count (:projection u)) 16))
    (is (= (:position u) (:position c)))
    ;; perspective column2.w must be -1 (wgpu right-handed convention)
    (is (close? (nth (:projection u) 11) -1.0 1e-6))))

(deftest camera-uniform-orthographic-top
  (let [c (assoc (cam/camera 1.0) :position [0.0 50.0 0.0] :mode {:kind :orthographic-top})
        u (cam/camera-uniform c)]
    (is (= (count (:projection u)) 16))
    ;; ortho has no perspective divide row: column2.w == 0
    (is (close? (nth (:projection u) 11) 0.0 1e-6))))

(deftest orbit-places-camera-at-distance
  (let [c (cam/camera 1.0)
        c2 (cam/orbit c 0.0 0.0 5.0)]
    ;; yaw=0 pitch=0 → position = target + (0, 0, distance)
    (is (close? (nth (:position c2) 2) 5.0 1e-6))))

(deftest set-position-looks-toward-minus-z
  (let [c (cam/set-position (cam/camera 1.0) [1.0 2.0 3.0])]
    (is (= (:position c) [1.0 2.0 3.0]))
    (is (= (:target c) [1.0 2.0 2.0]))))

(deftest map-view-update-top-down-uses-north-up
  (let [c (cam/map-view-update (cam/camera 1.0) 0.0 0.0 16.0 0.0 0.0)]
    ;; pitch=0 → straight down, altitude = 256 * 2^(16-16) = 256
    (is (close? (nth (:position c) 1) 256.0 1e-3))
    (is (= (:up c) [0.0 0.0 -1.0]))))

(deftest side-scroll-update-follows-player
  (let [c (cam/side-scroll-update (cam/camera 1.0) 3.0 4.0)]
    (is (= (:position c) [3.0 6.0 20.0]))
    (is (= (:target c) [3.0 6.0 0.0]))))

(deftest light-uniform-normalizes-direction
  (let [l (cam/light-uniform [0.0 5.0 0.0] [1.0 1.0 1.0] 2.0)]
    (is (= (:direction l) [0.0 1.0 0.0]))
    (is (= (:intensity l) 2.0))
    (is (= (count (:view-proj l)) 16))))

(deftest material-defaults
  (is (= (:albedo cam/default-material) [0.8 0.8 0.8 1.0]))
  (is (= (:metallic cam/default-material) 0.0))
  (is (= (:roughness cam/default-material) 0.5)))

(deftest material-skin-preset
  (let [m (cam/material-skin 0.5)]
    (is (= (:sss-model m) 1))
    (is (= (:subsurface-radius m) [1.2 0.4 0.2]))))

(deftest material-eye-preset
  (let [m (cam/material-eye [0.3 0.2 0.1])]
    (is (= (:albedo m) [0.3 0.2 0.1 1.0]))
    (is (= (:clearcoat m) 0.95))))
