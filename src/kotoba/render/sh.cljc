(ns kotoba.render.sh
  "Second-order real spherical harmonics (L0..L2, 9 coefficients) over RGB, and
   the clamped-cosine convolution that turns projected radiance into diffuse
   irradiance.

   ## Why this exists

   `kotoba.render.environment` stores one already-convolved irradiance cube for
   the whole scene, and `kotoba.render.environment-bake` bakes it from an
   analytic sky. That is real indirect light, but it is *positionless*: every
   point in the world receives the same irradiance, so a room interior is lit
   exactly like the field outside it.

   A probe needs irradiance *per position*, and a cube per probe is far too much
   data. Nine RGB coefficients reconstruct diffuse irradiance to within a few
   percent — the Ramamoorthi/Hanrahan result that made SH probes standard — so a
   probe is 27 floats, not a cubemap.

   ## Representation

   A coefficient set is a vector of exactly 9 `[r g b]` triples, in the
   conventional band order:

       0            L0
       1 2 3        L1  (y, z, x)
       4 5 6 7 8    L2  (xy, yz, 3z^2-1, xz, x^2-y^2)

   Plain EDN, so it serializes with the rest of the render contracts.

   ## Radiance in, irradiance out

   [[accumulate]] projects *radiance* samples. [[irradiance]] evaluates the
   projection already convolved with the clamped cosine lobe, so its result is
   irradiance E(n) in the same linear units as the radiance that went in. A
   Lambertian surface reflects `albedo/pi * E`; this namespace does not apply
   that, because whether the caller wants radiance or irradiance depends on the
   shading model and silently folding in a 1/pi is how the two get confused.

   The unit test pins both directions against closed forms: a uniform sphere of
   radiance L must give exactly `pi*L` for every normal, and a single direction
   must peak along itself."
  (:require [kotoba.render.sampling :as sampling]))

(def coefficient-count 9)

(def zero
  "All-black coefficient set."
  (vec (repeat coefficient-count [0.0 0.0 0.0])))

(defn basis
  "Real orthonormal SH basis up to L2, evaluated at unit direction `[x y z]`."
  [[x y z]]
  [0.28209479177387814
   (* 0.4886025119029199 y)
   (* 0.4886025119029199 z)
   (* 0.4886025119029199 x)
   (* 1.0925484305920792 x y)
   (* 1.0925484305920792 y z)
   (* 0.31539156525252005 (- (* 3.0 z z) 1.0))
   (* 1.0925484305920792 x z)
   (* 0.5462742152960396 (- (* x x) (* y y)))])

(def ^:private cosine-lobe
  "Clamped-cosine convolution coefficients per band: pi, 2pi/3, pi/4.
   Convolving the projected radiance with this lobe is what makes evaluation
   yield irradiance instead of radiance."
  [3.141592653589793
   2.0943951023931953 2.0943951023931953 2.0943951023931953
   0.7853981633974483 0.7853981633974483 0.7853981633974483
   0.7853981633974483 0.7853981633974483])

(defn accumulate
  "Project one radiance sample into `coefficients`.

   `rgb` is radiance arriving from `direction` (a unit vector), and `weight` is
   the sample's solid angle — `4*pi/n` for `n` uniform-sphere samples. Getting
   the weight wrong scales the whole result, which looks like an exposure bug
   rather than an integration bug, so callers should take it from the sampler
   that produced the directions."
  [coefficients rgb direction weight]
  (let [y (basis direction)]
    (mapv (fn [c yi]
            (let [w (* yi weight)]
              [(+ (nth c 0) (* (nth rgb 0) w))
               (+ (nth c 1) (* (nth rgb 1) w))
               (+ (nth c 2) (* (nth rgb 2) w))]))
          coefficients
          y)))

(defn add [a b]
  (mapv (fn [x y] (mapv + x y)) a b))

(defn scale [coefficients s]
  (mapv (fn [c] (mapv #(* % s) c)) coefficients))

(defn irradiance-unclamped
  "Raw clamped-cosine reconstruction, negatives included. Tests use this to see
   ringing directly; shading should use [[irradiance]]."
  [coefficients n]
  (let [y (basis n)]
    (reduce (fn [acc i]
              (let [w (* (nth cosine-lobe i) (nth y i))
                    c (nth coefficients i)]
                [(+ (nth acc 0) (* (nth c 0) w))
                 (+ (nth acc 1) (* (nth c 1) w))
                 (+ (nth acc 2) (* (nth c 2) w))]))
            [0.0 0.0 0.0]
            (range coefficient-count))))

(defn irradiance
  "Diffuse irradiance E(n) for unit normal `n`, from projected radiance.

   Reconstruction is band-limited, so a very sharp light can ring slightly
   negative. Negative irradiance is not physical and would darken a surface
   below black, so each channel is clamped at zero — the standard remedy, and
   the reason [[irradiance-unclamped]] exists for tests that need to see the
   raw reconstruction."
  [coefficients n]
  (mapv #(max 0.0 %) (irradiance-unclamped coefficients n)))

(defn ambient
  "Coefficients for a uniform sphere of radiance `rgb`.

   Analytic rather than sampled: only L0 is non-zero, at `rgb * 4*pi * Y00`.
   Evaluating it returns exactly `pi * rgb`, which is the textbook irradiance
   from a uniform environment and the oracle the test uses."
  [rgb]
  (let [k (* 4.0 Math/PI 0.28209479177387814)]
    (assoc zero 0 (mapv #(* % k) rgb))))

(defn project
  "Project a radiance function over the whole sphere with `n` deterministic
   uniform-sphere samples. `radiance-fn` takes a unit direction and returns
   linear `[r g b]`."
  [radiance-fn n]
  (let [weight (/ (* 4.0 Math/PI) n)]
    (reduce (fn [acc i]
              (let [d (sampling/uniform-sphere-direction (sampling/hammersley i n))]
                (accumulate acc (radiance-fn d) d weight)))
            zero
            (range n))))
