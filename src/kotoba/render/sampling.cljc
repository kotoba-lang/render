(ns kotoba.render.sampling
  "Deterministic low-discrepancy direction sampling shared by the offline bakers.

   `kotoba.render.environment-bake` has had private copies of the van der Corput
   / Hammersley pair since it was written. They are here, public and portable, so
   the probe baker does not become a second implementation of the same sequence —
   two bakers drawing different samples would produce environments that disagree
   for reasons nobody could see in the output.

   Nothing here is random. A bake is a build step: the same inputs must give the
   same bytes, so every sequence is indexed, not seeded.")

(defn v-add [a b] (mapv + a b))
(defn v-scale [a s] (mapv #(* % s) a))
(defn v-dot [a b] (reduce + (map * a b)))

(defn v-cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn v-normalize [v]
  (v-scale v (/ 1.0 (Math/sqrt (max 1.0e-20 (v-dot v v))))))

(defn radical-inverse-vdc
  "Van der Corput radical inverse in base 2 — the second Hammersley coordinate."
  [bits]
  (loop [n (long bits) denominator 2.0 result 0.0]
    (if (zero? n)
      result
      (recur (unsigned-bit-shift-right n 1)
             (* denominator 2.0)
             (+ result (if (odd? n) (/ 1.0 denominator) 0.0))))))

(defn hammersley
  "`i`-th of `n` points of the 2D Hammersley set, in [0,1)^2."
  [i n]
  [(/ (+ i 0.5) n) (radical-inverse-vdc i)])

(defn uniform-sphere-direction
  "Map `[u1 u2]` in [0,1)^2 to a direction uniformly distributed over the whole
   sphere. Solid angle per sample is 4*pi/n — the weight a projection must use.

   Cosine-hemisphere sampling (`cosine-direction`) is the right choice when the
   integrand already contains the cosine, as an irradiance convolution does. It
   is the wrong choice for projecting radiance into spherical harmonics, which
   needs the full sphere under uniform measure."
  [[u1 u2]]
  (let [z (- 1.0 (* 2.0 u1))
        r (Math/sqrt (max 0.0 (- 1.0 (* z z))))
        phi (* 2.0 Math/PI u2)]
    [(* r (Math/cos phi)) (* r (Math/sin phi)) z]))

(defn cosine-direction
  "Cosine-weighted direction in the tangent frame, where +z is the normal."
  [[u1 u2]]
  (let [r (Math/sqrt u1)
        theta (* 2.0 Math/PI u2)]
    [(* r (Math/cos theta)) (* r (Math/sin theta)) (Math/sqrt (max 0.0 (- 1.0 u1)))]))

(defn orthonormal-basis
  "Tangent and bitangent for unit normal `n`."
  [n]
  (let [up (if (< (Math/abs (double (nth n 1))) 0.999) [0.0 1.0 0.0] [1.0 0.0 0.0])
        tangent (v-normalize (v-cross up n))]
    [tangent (v-cross n tangent)]))

(defn tangent->world
  "Rotate a tangent-space direction into world space about normal `n`."
  [[x y z] n]
  (let [[t b] (orthonormal-basis n)]
    (v-normalize (v-add (v-add (v-scale t x) (v-scale b y)) (v-scale n z)))))
