(ns kotoba.render.probe
  "Irradiance probe grid — the positional half of indirect light.

   `kotoba.render.environment` gives the scene one irradiance cube, so indirect
   light is identical everywhere: a probe-less renderer lights a room interior
   exactly like the open field outside it. A probe grid samples irradiance at
   positions and interpolates between them, which is what makes 'inside' darker
   than 'outside' without a per-texel lightmap.

   ## Contract

       {:schema  :kotoba.render/probe-grid-v1
        :origin  [x y z]        ;; world position of probe [0 0 0]
        :spacing [sx sy sz]     ;; world distance between adjacent probes
        :dims    [nx ny nz]     ;; probe counts per axis, each >= 1
        :probes  [sh ...]}      ;; nx*ny*nz coefficient sets, x fastest

   Plain EDN like the rest of the render contracts, so it serializes and diffs
   with them. Each probe is nine RGB triples ([[kotoba.render.sh]]), so a
   16x4x16 grid is 1,024 probes — about 27k floats, not 1,024 cubemaps.

   ## Outside the grid

   Sampling clamps to the boundary rather than extrapolating or returning black.
   Extrapolating SH produces confidently wrong values, and black produces a
   visible hard edge at the grid bounds; clamping degrades to 'the nearest thing
   we measured', which is wrong by a bounded amount. Callers that need to know
   they left the volume can ask [[inside?]].

   Baking lives in `kotoba.render.probe-bake` (JVM, offline). This namespace is
   portable because the runtime has to evaluate it."
  (:require [kotoba.render.sh :as sh]))

(def schema :kotoba.render/probe-grid-v1)

(defn probe-count [[nx ny nz]] (* nx ny nz))

(defn index
  "Flat index of probe `[i j k]`, x fastest."
  [[nx ny _nz] [i j k]]
  (+ i (* nx (+ j (* ny k)))))

(defn probe-grid
  "Validate and tag a probe grid. Throws on a shape a sampler could not use —
   a grid whose `:probes` count disagrees with `:dims` would silently sample the
   wrong probe, which reads as a lighting bug at a position nobody can reproduce."
  [{:keys [origin spacing dims probes] :as grid}]
  (when-not (and (vector? dims) (= 3 (count dims)) (every? pos-int? dims))
    (throw (ex-info "probe grid :dims must be three positive integers"
                    {:reason :probe/bad-dims :dims dims})))
  (when-not (and (vector? spacing) (= 3 (count spacing)) (every? #(and (number? %) (pos? %)) spacing))
    (throw (ex-info "probe grid :spacing must be three positive numbers"
                    {:reason :probe/bad-spacing :spacing spacing})))
  (when-not (and (vector? origin) (= 3 (count origin)) (every? number? origin))
    (throw (ex-info "probe grid :origin must be three numbers"
                    {:reason :probe/bad-origin :origin origin})))
  (let [expected (probe-count dims)]
    (when-not (= expected (count probes))
      (throw (ex-info "probe grid :probes count must equal the product of :dims"
                      {:reason :probe/probe-count-mismatch
                       :expected expected :actual (count probes) :dims dims})))
    (when-let [bad (first (remove #(= sh/coefficient-count (count %)) probes))]
      (throw (ex-info "every probe must carry exactly 9 SH coefficients"
                      {:reason :probe/bad-coefficients :found (count bad)}))))
  (assoc grid :schema schema))

(defn- axis-weights
  "Lower probe index and blend factor along one axis.

   `(min (- dim 2) i)` is negative when `dim` is 1, and `(max 0 ...)` pulls it
   back to 0, so a single-probe axis needs no special case here — the offset
   guard in [[sample]] is what keeps the upper corner from leaving the grid. An
   earlier version special-cased `dim = 1` in both places; a mutation test showed
   the branch here could be deleted without any gate noticing, which is the
   signature of a second guard for a case the first one already covers."
  [p origin spacing dim]
  (let [f (/ (- p origin) spacing)
        i (max 0 (min (- dim 2) (long (Math/floor f))))
        t (max 0.0 (min 1.0 (- f i)))]
    [i t]))

(defn inside?
  "Is `position` within the grid's bounds (no clamping applied)?"
  [{:keys [origin spacing dims]} position]
  (every? true?
          (map (fn [p o s n] (and (>= p o) (<= p (+ o (* s (dec n))))))
               position origin spacing dims)))

(defn sample
  "Trilinearly interpolated coefficient set at world `position`.

   Interpolating the coefficients and then evaluating is equivalent to
   interpolating the evaluated irradiance, because reconstruction is linear in
   the coefficients — so this does the cheap one."
  [{:keys [origin spacing dims probes] :as _grid} position]
  (let [[i ti] (axis-weights (nth position 0) (nth origin 0) (nth spacing 0) (nth dims 0))
        [j tj] (axis-weights (nth position 1) (nth origin 1) (nth spacing 1) (nth dims 1))
        [k tk] (axis-weights (nth position 2) (nth origin 2) (nth spacing 2) (nth dims 2))
        di (if (= 1 (nth dims 0)) 0 1)
        dj (if (= 1 (nth dims 1)) 0 1)
        dk (if (= 1 (nth dims 2)) 0 1)]
    (reduce
     (fn [acc [oi oj ok]]
       (let [w (* (if (zero? oi) (- 1.0 ti) ti)
                  (if (zero? oj) (- 1.0 tj) tj)
                  (if (zero? ok) (- 1.0 tk) tk))]
         (if (zero? w)
           acc
           (sh/add acc (sh/scale (nth probes (index dims [(+ i (* oi di))
                                                          (+ j (* oj dj))
                                                          (+ k (* ok dk))]))
                                 w)))))
     sh/zero
     (for [ok [0 1] oj [0 1] oi [0 1]] [oi oj ok]))))

(defn irradiance
  "Diffuse irradiance at world `position` for surface normal `n`."
  [grid position n]
  (sh/irradiance (sample grid position) n))
