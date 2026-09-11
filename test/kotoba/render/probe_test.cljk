(ns kotoba.render.probe-test
  "Gates for the probe grid and the occluded bake.

   The one that matters is `sealed-interior-is-dark-and-outside-is-not`: the whole
   reason probes exist is that a positionless environment lights a room interior
   like the field outside it. If that test can pass without occlusion working,
   nothing here is worth keeping — so it is written as a *contrast* between two
   positions in one grid, not as an absolute value."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.render.probe :as probe]
            [kotoba.render.probe-bake :as bake]
            [kotoba.render.sh :as sh]))

(defn- close? [a b tol] (< (Math/abs (double (- a b))) tol))
(def ^:private pi Math/PI)

;; --- the grid contract ------------------------------------------------------

(defn- flat-grid
  "A 2x1x1 grid whose two probes are constant environments of different
   brightness — enough to see interpolation without involving the baker."
  []
  (probe/probe-grid {:origin [0.0 0.0 0.0]
                     :spacing [10.0 10.0 10.0]
                     :dims [2 1 1]
                     :probes [(sh/ambient [0.0 0.0 0.0])
                              (sh/ambient [1.0 1.0 1.0])]}))

(deftest grid-validates-shapes-a-sampler-could-not-use
  (is (= :kotoba.render/probe-grid-v1 (:schema (flat-grid))))
  (doseq [[label bad reason]
          [["probes fewer than dims claim"
            {:origin [0.0 0.0 0.0] :spacing [1.0 1.0 1.0] :dims [2 2 2]
             :probes [sh/zero]}
            :probe/probe-count-mismatch]
           ["zero dimension"
            {:origin [0.0 0.0 0.0] :spacing [1.0 1.0 1.0] :dims [0 1 1] :probes []}
            :probe/bad-dims]
           ["zero spacing"
            {:origin [0.0 0.0 0.0] :spacing [0.0 1.0 1.0] :dims [1 1 1]
             :probes [sh/zero]}
            :probe/bad-spacing]
           ["a probe with the wrong coefficient count"
            {:origin [0.0 0.0 0.0] :spacing [1.0 1.0 1.0] :dims [1 1 1]
             :probes [[[0.0 0.0 0.0]]]}
            :probe/bad-coefficients]]]
    (testing label
      (is (= reason
             (try (probe/probe-grid bad) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest sampling-a-grid-vertex-returns-that-probe
  (let [g (flat-grid)
        n [0.0 1.0 0.0]]
    (is (close? (nth (probe/irradiance g [0.0 0.0 0.0] n) 0) 0.0 1.0e-9))
    (is (close? (nth (probe/irradiance g [10.0 0.0 0.0] n) 0) pi 1.0e-9)
        "the bright probe must read pi*1.0")))

(deftest sampling-between-probes-interpolates
  (let [g (flat-grid)
        n [0.0 1.0 0.0]]
    (is (close? (nth (probe/irradiance g [5.0 0.0 0.0] n) 0) (* 0.5 pi) 1.0e-9)
        "midpoint is the average")
    (is (close? (nth (probe/irradiance g [2.5 0.0 0.0] n) 0) (* 0.25 pi) 1.0e-9)
        "quarter point is a quarter")))

(deftest sampling-outside-the-grid-clamps-instead-of-extrapolating
  (let [g (flat-grid)
        n [0.0 1.0 0.0]]
    (is (false? (probe/inside? g [-5.0 0.0 0.0])))
    (is (close? (nth (probe/irradiance g [-5.0 0.0 0.0] n) 0) 0.0 1.0e-9)
        "left of the grid clamps to the dark probe, not to a negative extrapolation")
    (is (close? (nth (probe/irradiance g [999.0 0.0 0.0] n) 0) pi 1.0e-9)
        "right of the grid clamps to the bright probe")))

(deftest a-single-probe-axis-does-not-read-out-of-bounds
  (testing "dims of 1 leave nothing to interpolate; the naive lower-index formula
            would go to -1 and read the wrong probe or throw"
    (let [g (probe/probe-grid {:origin [0.0 0.0 0.0] :spacing [1.0 1.0 1.0]
                               :dims [1 1 1] :probes [(sh/ambient [0.5 0.5 0.5])]})]
      (is (close? (nth (probe/irradiance g [7.0 -3.0 2.0] [0.0 1.0 0.0]) 0)
                  (* 0.5 pi) 1.0e-9)))))

;; --- occlusion: the point of the whole feature -----------------------------

(def ^:private sky-radiance 1.0)
(defn- white-sky [_d] [sky-radiance sky-radiance sky-radiance])

(deftest unoccluded-bake-reproduces-the-analytic-ambient
  (testing "with no geometry the bake must agree with the closed form — the
            control that says the sampler and weights are right before occlusion
            is added"
    (let [c (bake/bake-probe {:position [0.0 0.0 0.0] :samples 2048
                              :sky white-sky})]
      (is (close? (nth (sh/irradiance c [0.0 1.0 0.0]) 0) (* pi sky-radiance) 0.02)))))

(deftest sealed-interior-is-dark-and-outside-is-not
  (testing "THE gate. A probe sealed inside a box receives nothing from any
            direction; a probe outside the same box receives the sky. Written as a
            contrast within one bake so it cannot pass by both being dark or both
            being bright."
    (let [room-min [-5.0 -5.0 -5.0]
          room-max [5.0 5.0 5.0]
          visible? (bake/box-occluder room-min room-max)
          inside (bake/bake-probe {:position [0.0 0.0 0.0] :samples 512
                                   :sky white-sky :visible? visible?})
          outside (bake/bake-probe {:position [50.0 0.0 0.0] :samples 512
                                    :sky white-sky :visible? visible?})
          n [0.0 1.0 0.0]
          e-in (nth (sh/irradiance inside n) 0)
          e-out (nth (sh/irradiance outside n) 0)]
      (is (close? e-in 0.0 1.0e-9)
          (str "a sealed probe must be exactly dark, got " e-in))
      (is (> e-out (* 0.5 pi))
          (str "a probe in the open must still see most of the sky, got " e-out))
      (is (> (- e-out e-in) (* 0.5 pi))
          "the two positions must differ by most of the sky, which is the entire
           claim: irradiance depends on where you are"))))

(deftest partial-occlusion-is-directional
  (testing "A probe above a large floor slab. The physics, which an earlier
            version of this test got backwards: for an UP-facing normal the
            clamped cosine already discards the lower hemisphere, so blocking the
            floor cannot reduce irradiance — up must stay at pi. It is the
            DOWN-facing normal that loses everything it could have seen.

            Asserting `up < pi` was wrong and the code was right; the failure
            was the oracle, not the implementation. Stated correctly it is a
            stronger gate, because a hemisphere or sign error now shows up as up
            and down moving together."
    (let [floor (bake/box-occluder [-50.0 -1.0 -50.0] [50.0 0.0 50.0])
          c (bake/bake-probe {:position [0.0 1.0 0.0] :samples 1024
                              :sky white-sky :visible? floor})
          up (nth (sh/irradiance c [0.0 1.0 0.0]) 0)
          down (nth (sh/irradiance c [0.0 -1.0 0.0]) 0)]
      (is (close? up (* pi sky-radiance) 0.05)
          (str "an up-facing normal never saw the floor, so it keeps pi; got " up))
      (is (< down (* 0.15 pi))
          (str "a down-facing normal sees only the slab, so it goes dark; got " down))
      (is (> up (* 4.0 down))
          "the two directions must separate sharply — this is the shape of contact
           shadowing that a positionless environment cannot express"))))

(deftest sphere-occluder-blocks-what-it-covers
  (let [blocker (bake/sphere-occluder [0.0 10.0 0.0] 6.0)
        c (bake/bake-probe {:position [0.0 0.0 0.0] :samples 1024
                            :sky white-sky :visible? blocker})
        up (nth (sh/irradiance c [0.0 1.0 0.0]) 0)
        down (nth (sh/irradiance c [0.0 -1.0 0.0]) 0)]
    (is (< up down) "the sphere hangs overhead, so up is the shadowed direction")))

(deftest baked-grid-varies-with-position
  (testing "the grid-level statement of the same property: probes inside the room
            differ from probes outside it, in one artifact"
    (let [visible? (bake/box-occluder [-3.0 -3.0 -3.0] [3.0 3.0 3.0])
          g (bake/bake-grid {:origin [0.0 0.0 0.0] :spacing [20.0 20.0 20.0]
                             :dims [2 1 1] :samples 256
                             :sky white-sky :visible? visible?})
          n [0.0 1.0 0.0]
          at-origin (nth (probe/irradiance g [0.0 0.0 0.0] n) 0)
          far (nth (probe/irradiance g [20.0 0.0 0.0] n) 0)]
      (is (= 2 (probe/probe-count (:dims g))))
      (is (close? at-origin 0.0 1.0e-9) "probe 0 sits sealed at the box centre")
      (is (> far 1.0) "probe 1 sits well outside it")
      (is (> far at-origin)
          "a positionless environment could not produce this difference"))))

(defn- bake-grid-with-default-ground
  "The shipped default geometry at test resolution."
  []
  (bake/bake-grid {:origin [-4.0 0.5 -4.0]
                   :spacing [4.0 3.0 4.0]
                   :dims [2 3 2]
                   :samples 128
                   :visible? (bake/box-occluder (:ground-min bake/default-scene)
                                                (:ground-max bake/default-scene))}))

(deftest default-scene-produces-a-grid-that-actually-varies
  (testing "The shipped default bake includes a ground slab specifically so the
            artifact is not uniform. A grid with no geometry is uniform by
            construction — correct, but it demonstrates nothing and reads as a
            broken feature. This pins the spread so a change that flattens the
            default is caught here rather than in someone's renderer.

            Down-facing normals are where a flat ground shows up; up-facing ones
            barely move, because the sky dominates them."
    (let [g (bake-grid-with-default-ground)
          down (map #(nth (sh/irradiance % [0.0 -1.0 0.0]) 1) (:probes g))
          spread (- (apply max down) (apply min down))]
      (is (pos? spread)
          (str "the default scene must give probes different downward irradiance, "
               "got a spread of " spread)))))

(deftest bake-is-deterministic
  (testing "same inputs, same bytes — a bake is a build step"
    (let [opts {:position [1.0 2.0 3.0] :samples 128 :sky white-sky
                :visible? (bake/box-occluder [-1.0 -1.0 -1.0] [1.0 1.0 1.0])}]
      (is (= (bake/bake-probe opts) (bake/bake-probe opts))))))
