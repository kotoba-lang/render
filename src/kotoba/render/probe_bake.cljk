(ns kotoba.render.probe-bake
  "Offline bake of an irradiance probe grid that consults scene visibility.

   ## What was missing, precisely

   `kotoba.render.environment-bake` already bakes indirect light offline, with
   cosine importance sampling and a low-discrepancy sequence. Two things it does
   not do, and cannot: it takes no **position**, and it consults no **geometry**.
   It convolves an analytic sky, so its answer is the same everywhere and nothing
   ever casts shade onto it. That is why a room interior came out lit like the
   field outside.

   This baker takes both. For each probe position it fires deterministic
   uniform-sphere rays, asks `visible?` whether each ray escapes to the sky, and
   projects the surviving radiance into spherical harmonics. A probe sealed
   inside geometry receives nothing from every direction, so its coefficients are
   zero and its irradiance is zero — the property the whole feature exists for.

   ## Visibility is injected, and that is deliberate

   `visible?` is `(fn [origin direction] -> boolean)`. Two reasons it is not
   hardwired to a BVH:

   1. The analytic occluders below are **exact**. A closed box is the decisive
      test case, and an exact box beats a sampled mesh for pinning behaviour —
      no intersection tolerance enters the oracle.
   2. A mesh tracer already exists in the workspace
      (`kotoba.lang.kami-nv-compat.kami-rt.bvh`: Moller-Trumbore, median-split
      BVH, closest-hit, portable .cljc, tested). Wiring it in means `render`
      taking a dependency on `kami-nv-compat`, which is a layer decision
      (`manifest/layers.edn`, checked by `scripts/verify-layer-deps.cljs`) and
      not one to make as a side effect of adding a baker. The seam is here and
      the adapter is three lines:

          (let [soup (bvh/triangle-soup triangles)
                accel (bvh/build-bvh soup)]
            (fn [o d] (nil? (bvh/trace-closest soup accel o d))))

   Until that dependency is decided, mesh scenes bake by passing that closure in
   from the caller. **No mesh scene is baked by this repo's own tests**, so do
   not read these gates as evidence that mesh occlusion works — they prove the
   projection, the grid, and exact-solid occlusion.

   ## Determinism

   Same inputs, same bytes: directions come from the indexed Hammersley set in
   `kotoba.render.sampling`, never from a seeded generator."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.render.environment-bake :as env-bake]
            [kotoba.render.probe :as probe]
            [kotoba.render.sampling :as sampling]
            [kotoba.render.sh :as sh])
  (:import [java.util.zip GZIPOutputStream]))

(def production-config
  "Sample count chosen the way the sibling IBL bake chooses its own: high enough
   that the L2 projection is stable, low enough that a whole grid is a practical
   build step. 256 rays x 1,024 probes is ~260k visibility queries."
  {:samples 256})

(def ^:private eps 1.0e-6)

;; --- exact analytic occluders ----------------------------------------------

(defn always-visible
  "No geometry: every ray reaches the sky. Baking a grid with this must
   reproduce the positionless environment bake, which is a useful control."
  [_origin _direction]
  true)

(defn- slab
  "Ray/slab overlap along one axis, or nil when the ray misses it entirely."
  [o d lo hi]
  (if (< (Math/abs (double d)) 1.0e-12)
    (when (and (>= o lo) (<= o hi)) [Double/NEGATIVE_INFINITY Double/POSITIVE_INFINITY])
    (let [t1 (/ (- lo o) d)
          t2 (/ (- hi o) d)]
      [(min t1 t2) (max t1 t2)])))

(defn box-hit?
  "Does the ray from `origin` along `direction` meet the axis-aligned box?

   A ray starting *inside* the box exits through a wall, so this is true — which
   is exactly what makes a sealed probe dark. Only forward hits count (`t > eps`),
   so a probe sitting on a surface is not occluded by the surface it rests on."
  [[minx miny minz] [maxx maxy maxz] origin direction]
  (let [sx (slab (nth origin 0) (nth direction 0) minx maxx)
        sy (slab (nth origin 1) (nth direction 1) miny maxy)
        sz (slab (nth origin 2) (nth direction 2) minz maxz)]
    (when (and sx sy sz)
      (let [t-min (max (nth sx 0) (nth sy 0) (nth sz 0))
            t-max (min (nth sx 1) (nth sy 1) (nth sz 1))]
        (and (<= t-min t-max) (> t-max eps))))))

(defn box-occluder
  "`visible?` for a solid axis-aligned box."
  [box-min box-max]
  (fn [origin direction] (not (box-hit? box-min box-max origin direction))))

(defn sphere-occluder
  "`visible?` for a solid sphere."
  [center radius]
  (fn [origin direction]
    (let [oc (mapv - origin center)
          b (sampling/v-dot oc direction)
          c (- (sampling/v-dot oc oc) (* radius radius))
          disc (- (* b b) c)]
      (if (neg? disc)
        true
        (let [root (Math/sqrt disc)
              t1 (- (- b) root)
              t2 (+ (- b) root)]
          (not (or (> t1 eps) (> t2 eps))))))))

(defn union-occluders
  "Visible only where every occluder agrees it is."
  [& fs]
  (fn [origin direction] (every? #(% origin direction) fs)))

;; --- the bake --------------------------------------------------------------

(defn bake-probe
  "Coefficients for one probe.

   `sky` is a radiance function of direction (defaults to the same analytic
   studio environment the IBL bake uses, so a probe grid and an environment cube
   agree about what the sky looks like). `visible?` decides whether each ray
   reaches it."
  [{:keys [position samples sky visible?]
    :or {samples (:samples production-config)
         sky env-bake/studio-radiance
         visible? always-visible}}]
  (let [weight (/ (* 4.0 Math/PI) samples)]
    (reduce (fn [acc i]
              (let [d (sampling/uniform-sphere-direction (sampling/hammersley i samples))]
                (if (visible? position d)
                  (sh/accumulate acc (sky d) d weight)
                  acc)))
            sh/zero
            (range samples))))

(defn bake-grid
  "Bake every probe of a grid. Returns a `probe-grid-v1`."
  [{:keys [origin spacing dims samples sky visible?]
    :or {samples (:samples production-config)}}]
  (let [[nx ny nz] dims
        probes (vec (for [k (range nz) j (range ny) i (range nx)]
                      (bake-probe (cond-> {:position [(+ (nth origin 0) (* i (nth spacing 0)))
                                                      (+ (nth origin 1) (* j (nth spacing 1)))
                                                      (+ (nth origin 2) (* k (nth spacing 2)))]
                                           :samples samples}
                                    sky (assoc :sky sky)
                                    visible? (assoc :visible? visible?)))))]
    (probe/probe-grid {:origin origin :spacing spacing :dims dims :probes probes})))

;; --- artifact --------------------------------------------------------------

(defn write-grid!
  "Write a probe grid as gzipped EDN, matching how the IBL bake ships its asset."
  [grid path]
  (io/make-parents path)
  (with-open [out (GZIPOutputStream. (io/output-stream path))]
    (.write out (.getBytes (pr-str grid) "UTF-8")))
  path)

(defn read-grid
  "Read a gzipped EDN probe grid back, re-validating its shape."
  [path]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream path))]
    (probe/probe-grid (edn/read-string (slurp in)))))

(def default-scene
  "A ground slab. The default bake includes one because a grid baked with no
   geometry at all is uniform by construction — correct physics, useless asset,
   and misleading as a demonstration: every probe reads the same and the feature
   looks broken. A ground plane is the least a real scene has, and it makes the
   grid vary with height, which is the cheapest visible proof that position
   matters."
  {:ground-min [-64.0 -2.0 -64.0]
   :ground-max [64.0 0.0 64.0]})

(defn -main
  "Bake a default 8x3x8 grid over a 32-unit square above a ground slab.
   `--out <path>` chooses the artifact location, `--no-ground` drops the slab."
  [& args]
  (let [opts (apply hash-map args)
        out (get opts "--out" "target/probe/studio-probe-grid.edn.gz")
        ground? (not (contains? opts "--no-ground"))
        grid (bake-grid (cond-> {:origin [-16.0 0.5 -16.0]
                                 :spacing [4.0 3.0 4.0]
                                 :dims [8 3 8]}
                          ground? (assoc :visible?
                                         (box-occluder (:ground-min default-scene)
                                                       (:ground-max default-scene)))))
        up [0.0 1.0 0.0]
        down [0.0 -1.0 0.0]
        downs (map #(nth (sh/irradiance % down) 1) (:probes grid))]
    (write-grid! grid out)
    (println "probe grid ->" out
             (str "(" (probe/probe-count (:dims grid)) " probes, "
                  (:samples production-config) " rays each"
                  (if ground? ", ground slab" ", no geometry") ")"))
    ;; Report the spread, so a bake that silently produced a uniform grid is
    ;; visible at the console instead of only in a renderer.
    (println (format "  down-facing irradiance (green) min %.4f  max %.4f"
                     (apply min downs) (apply max downs)))
    (println (format "  up-facing irradiance at grid centre (rgb) %s"
                     (mapv #(Double/parseDouble (format "%.4f" %))
                           (probe/irradiance grid [0.0 3.5 0.0] up))))))
