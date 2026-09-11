(ns kotoba.render.cubemap
  "Cube-face direction math, and equirectangular panorama -> six cube faces.

   `kotoba.render.environment-bake` has carried a private `cube-direction`
   since it was written, for the same reason `kotoba.render.sampling` once
   held private copies of the Hammersley pair: it was the only caller. It is
   no longer. A projector that disagreed with the baker about which way a
   face points would produce environments that are wrong in a way nothing in
   the output can show you, so the direction lives here, public and portable,
   and the baker calls it.

   Faces are keyed and oriented as `kotoba.render.environment/cube-faces`
   requires -- WebGPU/D3D cube order +X, -X, +Y, -Y, +Z, -Z, each face a
   row-major RGBA8 byte vector, `y` increasing downward. That convention is
   left-handed on purpose: a face image is NOT what a camera pointed along
   that axis would photograph. Do not \"fix\" a face by mirroring it to look
   photographic -- mirroring some faces and not others is not a convention,
   it is an inconsistency, and it survives into the reconstruction.

   The equirect mapping is the standard one: `v = 0` is the top row of the
   panorama and it is the zenith, +Y. A panorama whose zenith is at the
   bottom is upside down; flip the source, not this mapping.")

(def faces
  "The six faces, in WebGPU/D3D cube order. Deliberately the same vector as
   `kotoba.render.environment/cube-faces`; `cubemap-test` asserts they are
   equal so the two cannot drift into disagreeing about face order."
  [:+x :-x :+y :-y :+z :-z])

(def ^:private tau (* 2.0 #?(:clj Math/PI :cljs js/Math.PI)))
(def ^:private pi #?(:clj Math/PI :cljs js/Math.PI))

(defn- atan2 [y x] #?(:clj (Math/atan2 y x) :cljs (js/Math.atan2 y x)))
(defn- sqrt [x] #?(:clj (Math/sqrt x) :cljs (js/Math.sqrt x)))

(defn- normalize [[x y z]]
  (let [len (sqrt (+ (* x x) (* y y) (* z z)))]
    (if (zero? len) [0.0 0.0 0.0] [(/ x len) (/ y len) (/ z len)])))

(defn face-coords
  "Face-local coordinates of the centre of pixel (`x`, `y`) on a `size` x
   `size` face, as `[u v]` in (-1, 1). `u` runs left to right, `v` runs
   bottom to top -- so `v` is +1 at row zero, because face rows are stored
   top-down."
  [size x y]
  [(- (* 2.0 (/ (+ x 0.5) size)) 1.0)
   (- 1.0 (* 2.0 (/ (+ y 0.5) size)))])

(defn direction
  "Unit direction through the centre of pixel (`x`, `y`) of `face` on a
   `size` x `size` cube face."
  [face size x y]
  (let [[u v] (face-coords size x y)]
    (normalize
     (case face
       :+x [1.0 v (- u)] :-x [-1.0 v u]
       :+y [u 1.0 (- v)] :-y [u -1.0 v]
       :+z [u v 1.0] :-z [(- u) v -1.0]))))

(defn equirect-uv
  "Equirectangular `[u v]` in [0, 1] for a direction. `u` = 0.5 looks at -Z,
   `v` = 0 is the zenith (+Y)."
  [[x y z]]
  [(+ 0.5 (/ (atan2 x (- z)) tau))
   (- 0.5 (/ (atan2 y (sqrt (+ (* x x) (* z z)))) pi))])

(defn equirect-pixel
  "Nearest source pixel `[px py]` of a `width` x `height` equirectangular
   panorama for a direction. Clamped, so a direction exactly on the seam or
   at a pole lands inside the image rather than one past its edge. Azimuth is
   degenerate at a pole -- straight up returns some column, deterministically,
   and which one carries no meaning."
  [width height dir]
  (let [[u v] (equirect-uv dir)]
    [(max 0 (min (dec width) (int (* u width))))
     (max 0 (min (dec height) (int (* v height))))]))

(defn face-source-indices
  "Row-major vector of `size` * `size` source pixel indices: for each pixel of
   `face`, which pixel of a `width` x `height` panorama it samples.

   This is the whole projection, minus the pixel format. It depends only on
   the geometry, so a caller projecting many panoramas at one size computes
   it once, and a test can check the sampling without an image."
  [face size width height]
  (into []
        (for [y (range size) x (range size)]
          (let [[px py] (equirect-pixel width height (direction face size x y))]
            (+ (* py width) px)))))

(defn project-face
  "One cube face as a row-major RGBA8 byte vector, nearest-neighbour sampled
   from `panorama` -- a `kotoba.render.texture/rgba8` descriptor, or any map
   with `:width`, `:height` and row-major RGBA8 `:data`."
  [panorama face size]
  (let [{:keys [width height data]} panorama]
    (when-not (pos-int? size)
      (throw (ex-info "cube face size must be a positive integer" {:size size})))
    (when-not (and (pos-int? width) (pos-int? height))
      (throw (ex-info "panorama dimensions must be positive integers"
                      {:width width :height height})))
    (when-not (= (* width height 4) (count data))
      (throw (ex-info "panorama RGBA8 byte count does not match dimensions"
                      {:width width :height height
                       :expected (* width height 4) :actual (count data)})))
    (let [data (vec data)]
      (into []
            (mapcat (fn [i]
                      (let [o (* i 4)]
                        [(nth data o) (nth data (+ o 1))
                         (nth data (+ o 2)) (nth data (+ o 3))])))
            (face-source-indices face size width height)))))

(defn project
  "All six faces of `panorama` at `size`, keyed for
   `kotoba.render.environment/cube-level`."
  [panorama size]
  (into {} (map (fn [face] [face (project-face panorama face size)])) faces))
