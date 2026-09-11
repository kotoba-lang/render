(ns kotoba.render.splat-raster
  "A CPU rasterizer for gaussian splat clouds, so somebody can look at one.

   This exists because the fleet that produces splats cannot draw them.
   Measured 2026-08-19 on gad: `diff_gaussian_rasterization` imports and then
   its kernel reports `invalid device function` -- built for an architecture
   this GPU is not. So eight scenes were generated in May and nobody ever saw
   any of them, and the only quality signal in use was file size.

   It is not a renderer for shipping frames. No anisotropy, no view-dependent
   SH, no tiling: each splat is a screen-space disc, painted back to front with
   alpha. What it is for is answering `is there a room in there`, which is a
   question about whether anything at all is where it should be, and for that
   a disc is enough. `kotoba.render.splat` holds the data model; this only
   draws it.

   Everything is pure: a cloud plus a camera gives the same pixels on both
   runtimes, which is what makes an image usable as a regression fixture."
  (:require [kotoba.render.splat :as splat]))

(def ^:private sh-c0
  "Zeroth-order spherical-harmonic coefficient. `f_dc` is stored premultiplied
   by it, so colour is `0.5 + C0 * dc` -- not `dc` itself, which renders a
   correct cloud as black."
  0.28209479177387814)

(defn- v- [a b] (mapv - a b))
(defn- dot [a b] (reduce + (map * a b)))
(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- norm [v]
  (let [l (#?(:clj Math/sqrt :cljs js/Math.sqrt) (dot v v))]
    (if (zero? l) v (mapv #(/ % l) v))))
(defn- sigmoid [x] (/ 1.0 (+ 1.0 (#?(:clj Math/exp :cljs js/Math.exp) (- x)))))
(defn- expf [x] (#?(:clj Math/exp :cljs js/Math.exp) x))
(defn- clamp01 [x] (max 0.0 (min 1.0 x)))

(defn camera
  "A camera looking from `eye` at `target`. `fov-deg` is the vertical field of
   view. `up` defaults to +Y and is re-orthogonalised, so a caller may pass a
   convenient approximate up without producing a skewed basis."
  [{:keys [eye target up fov-deg] :or {up [0.0 1.0 0.0] fov-deg 60.0}}]
  (let [fwd (norm (v- target eye))
        right (norm (cross fwd up))
        up' (cross right fwd)]
    {:eye eye :forward fwd :right right :up up'
     :focal-scale (/ 1.0 (#?(:clj Math/tan :cljs js/Math.tan)
                          (* 0.5 fov-deg (/ #?(:clj Math/PI :cljs js/Math.PI) 180.0))))}))

(defn- project
  "Splat centre -> {:x :y :depth :radius} in pixels, or nil if behind the eye."
  [{:keys [eye forward right up focal-scale]} width height position scale]
  (let [d (v- position eye)
        z (dot d forward)]
    (when (> z 1e-6)
      (let [f (* 0.5 height focal-scale)]
        {:x (+ (* 0.5 width) (/ (* f (dot d right)) z))
         :y (- (* 0.5 height) (/ (* f (dot d up)) z))
         :depth z
         ;; One radius from the largest axis. Anisotropy is the first thing to
         ;; add if this ever needs to be more than an inspection view.
         :radius (max 0.5 (/ (* f scale) z))}))))

(defn render
  "Rasterize `cloud` through `cam` into a row-major RGB byte vector.

   `opts`: `:width` `:height` `:background` (3 bytes) `:max-splats`.
   `:max-splats` keeps the brightest-alpha splats and reports the drop in the
   returned metadata, because silently drawing some of a cloud and calling it
   the cloud is how a picture starts lying."
  [cloud cam {:keys [width height background max-splats]
              :or {width 512 height 512 background [0 0 0]}}]
  (let [splats (:splats cloud)
        prepared (->> splats
                      (keep (fn [s]
                              (let [alpha (sigmoid (:opacity s))
                                    scale (apply max (map expf (:scale s)))]
                                (when-let [p (project cam width height (:position s) scale)]
                                  (assoc p :alpha alpha
                                         :colour (mapv #(clamp01 (+ 0.5 (* sh-c0 %)))
                                                       (:sh-dc s)))))))
                      vec)
        kept (if (and max-splats (> (count prepared) max-splats))
               (vec (take max-splats (sort-by (comp - :alpha) prepared)))
               prepared)
        ;; Back to front: a painter's algorithm needs the far ones first.
        ordered (sort-by (comp - :depth) kept)
        n (* width height)
        acc (transient (vec (repeat (* n 3) 0.0)))
        cov (transient (vec (repeat n 0.0)))]
    (doseq [{:keys [x y radius alpha colour]} ordered
            :let [r (min radius 64.0)
                  x0 (max 0 (int (- x r))) x1 (min (dec width) (int (+ x r)))
                  y0 (max 0 (int (- y r))) y1 (min (dec height) (int (+ y r)))
                  r2 (* r r)]
            py (range y0 (inc y1))
            px (range x0 (inc x1))]
      (let [dx (- (+ px 0.5) x) dy (- (+ py 0.5) y)
            d2 (+ (* dx dx) (* dy dy))]
        (when (<= d2 r2)
          ;; Gaussian falloff across the disc rather than a hard edge: a field
          ;; of hard circles reads as noise at these splat counts.
          (let [a (* alpha (expf (* -2.0 (/ d2 (max 1e-6 r2)))))
                i (+ (* py width) px)
                o (* i 3)
                inv (- 1.0 a)]
            (dotimes [c 3]
              (assoc! acc (+ o c) (+ (* a (nth colour c)) (* inv (nth acc (+ o c))))))
            (assoc! cov i (+ a (* inv (nth cov i))))))))
    (let [acc (persistent! acc) cov (persistent! cov)]
      {:width width :height height
       :splats-drawn (count kept)
       :splats-dropped (- (count prepared) (count kept))
       :behind-camera (- (count splats) (count prepared))
       :coverage (/ (count (filter #(> % 0.01) cov)) (double (max 1 n)))
       :data (into []
                   (mapcat (fn [i]
                             (let [a (nth cov i) o (* i 3)]
                               (mapv (fn [c]
                                       (int (+ 0.5 (* 255.0 (clamp01
                                                             (+ (nth acc (+ o c))
                                                                (* (- 1.0 a)
                                                                   (/ (nth background c) 255.0)))))))
                                       ) (range 3)))))
                   (range n))})))

(defn inside-views
  "Four cameras at the centre of `cloud`'s bounds, looking along +X, +Z, -X, -Z.

   A room is a shell, and the stock way to look at a reconstructed object --
   put the camera on a sphere outside it and look in -- shows the outside of
   the walls. For a place, the camera belongs inside."
  [cloud & [fov-deg]]
  (let [[lo hi] (splat/bounds cloud)
        c (mapv #(/ (+ %1 %2) 2.0) lo hi)]
    (mapv (fn [[dx dz]]
            (camera {:eye c :target (mapv + c [dx 0.0 dz]) :fov-deg (or fov-deg 75.0)}))
          [[1.0 0.0] [0.0 1.0] [-1.0 0.0] [0.0 -1.0]])))
