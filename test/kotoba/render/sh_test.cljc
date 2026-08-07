(ns kotoba.render.sh-test
  "Gates for the SH projection, pinned against closed forms rather than against
   recorded output of this code. A fixture captured from my own implementation
   would agree with it however wrong it was."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.sampling :as sampling]
            [kotoba.render.sh :as sh]))

(defn- close? [a b tol] (< (Math/abs (double (- a b))) tol))
(defn- rgb-close? [a b tol] (every? true? (map #(close? %1 %2 tol) a b)))

(def ^:private pi Math/PI)

;; --- closed form 1: a uniform environment ----------------------------------

(deftest uniform-environment-gives-pi-times-radiance
  (testing "irradiance from a full sphere of radiance L is exactly pi*L, for
            every normal — the textbook result, and it does not depend on any
            choice this implementation makes"
    (let [l [0.4 0.5 0.6]
          coeffs (sh/ambient l)]
      (doseq [n [[0.0 1.0 0.0] [0.0 -1.0 0.0] [1.0 0.0 0.0]
                 [0.0 0.0 1.0] (sampling/v-normalize [1.0 2.0 -3.0])]]
        (is (rgb-close? (sh/irradiance coeffs n) (mapv #(* pi %) l) 1.0e-9)
            (str "normal " n))))))

(deftest projecting-a-constant-matches-the-analytic-ambient
  (testing "sampling a constant sky must converge to the closed form, which
            checks the sample weight (4pi/n) and the basis together — the two
            errors that look like an exposure bug"
    (let [l [0.25 0.5 1.0]
          sampled (sh/project (constantly l) 4096)
          analytic (sh/ambient l)]
      (is (rgb-close? (nth sampled 0) (nth analytic 0) 1.0e-3)
          "L0 must match the analytic value")
      (doseq [i (range 1 sh/coefficient-count)]
        (is (rgb-close? (nth sampled i) [0.0 0.0 0.0] 1.0e-3)
            (str "band coefficient " i " of a constant field must vanish"))))))

;; --- closed form 2: orthonormality -----------------------------------------

(deftest basis-is-orthonormal-over-the-sphere
  (testing "integral of Yi*Yj over the sphere is the identity — this is what
            makes projection-then-reconstruction meaningful at all"
    (let [n 8192
          w (/ (* 4.0 pi) n)
          dirs (map #(sampling/uniform-sphere-direction (sampling/hammersley % n)) (range n))
          bases (map sh/basis dirs)]
      (doseq [i (range sh/coefficient-count)
              j (range sh/coefficient-count)]
        (let [integral (* w (reduce + (map (fn [b] (* (nth b i) (nth b j))) bases)))]
          (is (close? integral (if (= i j) 1.0 0.0) 0.02)
              (str "<Y" i ", Y" j "> = " integral)))))))

;; --- closed form 3: a single direction ------------------------------------

(deftest directional-light-peaks-along-itself
  (testing "a narrow lobe from +y must light a +y normal most and a -y normal
            least; L2 cannot resolve a delta, so this pins the shape, not a value"
    (let [dir [0.0 1.0 0.0]
          ;; a narrow but band-limited lobe, so the projection is meaningful
          radiance (fn [d]
                     (let [v (Math/pow (max 0.0 (sampling/v-dot d dir)) 16.0)]
                       [v v v]))
          coeffs (sh/project radiance 4096)
          up (nth (sh/irradiance coeffs dir) 0)
          down (nth (sh/irradiance coeffs (mapv - dir)) 0)
          side (nth (sh/irradiance coeffs [1.0 0.0 0.0]) 0)]
      (is (> up side) "lit from above beats sideways")
      (is (>= side down) "sideways is at least as lit as facing away")
      (is (> up (* 5.0 (+ down 1.0e-9))) "the peak must actually dominate"))))

(deftest reconstruction-is-linear-in-the-coefficients
  (testing "this is why the probe grid may interpolate coefficients instead of
            evaluating eight probes and blending the results"
    (let [a (sh/ambient [0.2 0.4 0.6])
          b (sh/project (fn [[_ y _]] (let [v (max 0.0 y)] [v 0.0 0.0])) 1024)
          n (sampling/v-normalize [0.3 0.8 -0.5])
          mixed (sh/add (sh/scale a 0.25) (sh/scale b 0.75))]
      (is (rgb-close? (sh/irradiance-unclamped mixed n)
                      (mapv + (mapv #(* 0.25 %) (sh/irradiance-unclamped a n))
                            (mapv #(* 0.75 %) (sh/irradiance-unclamped b n)))
                      1.0e-9)))))

(deftest irradiance-never-returns-negative-light
  (testing "band-limited reconstruction rings; a negative channel would darken a
            surface below black, so the public accessor clamps"
    (let [;; a hard hemisphere step is the classic ringing case
          coeffs (sh/project (fn [[_ y _]] (if (pos? y) [1.0 1.0 1.0] [0.0 0.0 0.0])) 4096)
          ;; facing hard away from the lit half is where the undershoot lands
          n [0.0 -1.0 0.0]]
      (is (every? #(>= % 0.0) (sh/irradiance coeffs n))
          "clamped accessor must not go negative")
      (is (some? (sh/irradiance-unclamped coeffs n))
          "the unclamped accessor stays available for tests"))))
