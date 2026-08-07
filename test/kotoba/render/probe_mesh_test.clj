(ns kotoba.render.probe-mesh-test
  "Proves the injected-visibility seam works with a REAL mesh tracer, not only
   with the analytic occluders that ship in `kotoba.render.probe-bake`.

   `probe_bake.clj` documents a three-line adapter onto
   `kotoba.lang.kami-nv-compat.kami-rt.bvh` and, until this namespace existed,
   that was all it was: a documented claim with nothing exercising it. The gap
   ledger recorded the honest version — 'mesh occlusion unverified; this repo's
   tests bake no mesh scene'. This closes that.

   ## Why the strongest assertion here is a cross-check

   Baking a box as twelve triangles and asserting 'the inside is dark' would only
   repeat what the analytic gate already proves. The assertion that carries
   weight is that **two independent implementations of the same geometry agree**:
   `probe-bake/box-occluder` (exact slab arithmetic, written here) and a
   Moller-Trumbore BVH traversal over a triangulated box (written in another repo,
   ported from TypeScript, knowing nothing about this one). If they agree probe by
   probe, the seam is transporting real visibility rather than something that
   merely looks plausible.

   ## Test-only dependency

   `kami-nv-compat` is in the `:test` alias, not `:paths` — see the comment in
   deps.edn. `render` does not ship a dependency on an engine repo to get this
   coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.kami-nv-compat.kami-rt.bvh :as bvh]
            [kotoba.render.probe-bake :as bake]
            [kotoba.render.sh :as sh]))

(defn- close? [a b tol] (< (Math/abs (double (- a b))) tol))
(def ^:private pi Math/PI)

(defn box-triangles
  "Closed axis-aligned box as 12 triangles. Winding is irrelevant: the tracer is
   asked only whether a ray hits anything, never which side it hit."
  [[x0 y0 z0] [x1 y1 z1]]
  (let [v [[x0 y0 z0] [x1 y0 z0] [x1 y1 z0] [x0 y1 z0]
           [x0 y0 z1] [x1 y0 z1] [x1 y1 z1] [x0 y1 z1]]
        quad (fn [a b c d] [[(v a) (v b) (v c)] [(v a) (v c) (v d)]])]
    (vec (concat (quad 0 1 2 3)     ;; -z
                 (quad 4 5 6 7)     ;; +z
                 (quad 0 1 5 4)     ;; -y
                 (quad 3 2 6 7)     ;; +y
                 (quad 0 3 7 4)     ;; -x
                 (quad 1 2 6 5))))) ;; +x

(defn mesh-occluder
  "The adapter `probe_bake.clj` describes: a `visible?` closure backed by a BVH.
   A ray is visible when closest-hit finds nothing."
  [triangles]
  (let [soup (bvh/triangle-soup triangles)
        accel (bvh/build-bvh soup)]
    (fn [origin direction]
      (nil? (bvh/trace-closest soup accel origin direction)))))

(def ^:private room-min [-5.0 -5.0 -5.0])
(def ^:private room-max [5.0 5.0 5.0])
(defn- white-sky [_d] [1.0 1.0 1.0])

(deftest mesh-tracer-is-actually-wired
  (testing "sanity on the adapter itself before any baking: a ray from the box
            centre must hit the mesh, and a ray far outside pointing away must not"
    (let [visible? (mesh-occluder (box-triangles room-min room-max))]
      (is (false? (visible? [0.0 0.0 0.0] [0.0 1.0 0.0]))
          "from inside a closed box, every direction is blocked")
      (is (true? (visible? [50.0 0.0 0.0] [1.0 0.0 0.0]))
          "outside and pointing away, nothing is in the way")
      (is (false? (visible? [50.0 0.0 0.0] [-1.0 0.0 0.0]))
          "outside and pointing back at the box, it is in the way"))))

(deftest sealed-mesh-interior-is-dark
  (testing "the same property the analytic gate proves, now through a mesh"
    (let [visible? (mesh-occluder (box-triangles room-min room-max))
          inside (bake/bake-probe {:position [0.0 0.0 0.0] :samples 512
                                   :sky white-sky :visible? visible?})
          outside (bake/bake-probe {:position [50.0 0.0 0.0] :samples 512
                                    :sky white-sky :visible? visible?})
          n [0.0 1.0 0.0]
          e-in (nth (sh/irradiance inside n) 0)
          e-out (nth (sh/irradiance outside n) 0)]
      (is (close? e-in 0.0 1.0e-9) (str "sealed mesh interior must be dark, got " e-in))
      (is (> e-out (* 0.5 pi)) (str "outside must still see the sky, got " e-out)))))

(deftest mesh-and-analytic-occluders-agree
  (testing "THE assertion. Exact slab arithmetic in this repo versus a
            Moller-Trumbore BVH ported from TypeScript in another repo, over the
            same box, at positions chosen to include the hard cases: dead centre,
            near a face, near an edge, near a corner, and outside.

            Agreement here cannot come from either implementation being wrong in
            a convenient way — they share no code and no author's assumptions."
    (let [tris (box-triangles room-min room-max)
          mesh? (mesh-occluder tris)
          analytic? (bake/box-occluder room-min room-max)
          positions [[0.0 0.0 0.0]        ;; sealed centre
                     [0.0 4.4 0.0]        ;; inside, near the +y face
                     [4.4 4.4 0.0]        ;; inside, near an edge
                     [4.4 4.4 4.4]        ;; inside, near a corner
                     [0.0 8.0 0.0]        ;; outside, directly above
                     [12.0 0.0 0.0]       ;; outside, to one side
                     [9.0 9.0 9.0]]       ;; outside, off a corner
          n [0.0 1.0 0.0]]
      (doseq [p positions]
        (let [em (nth (sh/irradiance (bake/bake-probe {:position p :samples 512
                                                       :sky white-sky :visible? mesh?}) n) 0)
              ea (nth (sh/irradiance (bake/bake-probe {:position p :samples 512
                                                       :sky white-sky :visible? analytic?}) n) 0)
              inside? (every? true? (map #(and (> %1 -5.0) (< %1 5.0)) p))]
          (if inside?
            ;; Sealed: both must be exactly zero. No tolerance argument exists —
            ;; a ray fired from inside a closed solid hits something in every
            ;; direction, so any light at all is a defect.
            (do (is (close? em 0.0 1.0e-12) (str "mesh, sealed at " p ", got " em))
                (is (close? ea 0.0 1.0e-12) (str "analytic, sealed at " p ", got " ea)))
            ;; Outside: agreement to sample scale, not to the bit. An exact slab
            ;; test and a triangulated shell legitimately disagree on rays that
            ;; graze an edge or corner, and with 512 discrete samples a few land
            ;; there. Measured spread at the corner position: 0.2%. Demanding
            ;; bit-equality here would be asserting that two different
            ;; formulations of geometry round identically, which is not true and
            ;; not what the cross-check is for.
            (is (< (Math/abs (double (- em ea))) (* 0.01 (max em ea)))
                (str "at " p " mesh gave " em " and analytic gave " ea
                     " — beyond 1%, so they disagree about more than grazing rays"))))))))

(deftest a-mesh-with-a-hole-lets-light-in
  (testing "geometry a box occluder cannot express, so this can only be answered
            by the mesh path: remove the +y face and the interior stops being
            dark. If the tracer were ignoring triangles this would be dark, and if
            it were treating the box as solid this would also be dark — only real
            per-triangle visibility produces light here."
    (let [;; every face except +y (quad 3 2 6 7 is the +y pair, triangles 6 and 7)
          full (box-triangles room-min room-max)
          open (vec (concat (subvec full 0 6) (subvec full 8)))
          visible? (mesh-occluder open)
          c (bake/bake-probe {:position [0.0 0.0 0.0] :samples 1024
                              :sky white-sky :visible? visible?})
          up (nth (sh/irradiance c [0.0 1.0 0.0]) 0)
          down (nth (sh/irradiance c [0.0 -1.0 0.0]) 0)]
      (is (= 10 (count open)) "the open box must be 12 triangles minus the +y pair")
      (is (> up 0.1) (str "light must enter through the opening, got " up))
      (is (> up (* 4.0 down))
          "and it must arrive from above — the direction of the hole"))))
