(ns kotoba.render.procedural
  "Deterministic CPU-side PBR material baking.

   Bakers return the existing portable RGBA8 descriptors. They use only
   specified 32-bit integer operations, so CLJ build tools and CLJS authoring
   surfaces produce identical bytes without global RNG or floating point."
  (:require [kotoba.render.texture :as texture]))

(def material-kinds #{:steel :masonry :ground})

(defn- i32 [n]
  #?(:clj (unchecked-int n)
     :cljs (bit-or n 0)))

(defn- imul32 [a b]
  #?(:clj (unchecked-multiply-int (i32 a) (i32 b))
     :cljs (js/Math.imul a b)))

(defn- u-shift [x n]
  #?(:clj (bit-shift-right (Integer/toUnsignedLong (i32 x)) n)
     :cljs (unsigned-bit-shift-right x n)))

(defn- xor32 [a b]
  #?(:clj (bit-xor (i32 a) (i32 b))
     :cljs (bit-xor a b)))

(defn coordinate-hash
  "Stable unsigned 32-bit hash of a seed, texel coordinate and channel salt.
   Inputs are interpreted modulo 2^32. This is public so native adapters can
   verify compatible procedural bakers."
  [seed x y salt]
  (let [h (-> (i32 seed)
              (xor32 (imul32 x -1640531527))
              (xor32 (imul32 y -2048144789))
              (xor32 (imul32 salt -1028477387)))
        h (imul32 (xor32 h (u-shift h 16)) -2048144789)
        h (imul32 (xor32 h (u-shift h 13)) -1028477387)]
    (u-shift (xor32 h (u-shift h 16)) 0)))

(defn- noise-byte [seed x y salt]
  (bit-and (coordinate-hash seed x y salt) 255))

(defn- clamp-byte [n]
  (int (min 255 (max 0 n))))

(defn- vary [base amplitude noise]
  (clamp-byte (+ base (quot (* amplitude (- noise 128)) 128))))

(defn- normal-pixel [seed x y strength]
  ;; Pre-quantized tangent-space perturbation: no backend-dependent normalize.
  [(vary 128 strength (noise-byte seed x y 31))
   (vary 128 strength (noise-byte seed x y 37))
   252
   255])

(defn- steel-pixel [seed scale x y]
  (let [n (noise-byte seed x y 1)
        seam? (or (zero? (mod x scale)) (zero? (mod y scale)))
        base (if seam? 92 150)]
    {:albedo [(vary base 20 n) (vary (+ base 8) 18 n) (vary (+ base 18) 16 n) 255]
     :normal (if seam? [128 128 238 255] (normal-pixel seed x y 15))
     :metallic-roughness [0 (if seam? 112 (vary 62 24 n)) (vary 226 18 n) 255]}))

(defn- masonry-pixel [seed scale x y]
  (let [row (quot y scale)
        offset (if (odd? row) (quot scale 2) 0)
        bx (mod (+ x offset) scale)
        by (mod y scale)
        mortar? (or (zero? bx) (zero? by))
        n (noise-byte seed x y 7)]
    (if mortar?
      {:albedo [(vary 104 10 n) (vary 94 9 n) (vary 82 8 n) 255]
       :normal [128 128 232 255]
       :metallic-roughness [0 238 0 255]}
      {:albedo [(vary 164 32 n) (vary 94 22 n) (vary 56 16 n) 255]
       :normal (normal-pixel seed x y 20)
       :metallic-roughness [0 (vary 205 26 n) 4 255]})))

(defn- ground-pixel [seed scale x y]
  (let [cell-x (quot x scale)
        cell-y (quot y scale)
        coarse (noise-byte seed cell-x cell-y 13)
        fine (noise-byte seed x y 17)]
    {:albedo [(vary 72 22 coarse) (vary 104 34 fine) (vary 62 20 coarse) 255]
     :normal (normal-pixel seed x y 24)
     :metallic-roughness [0 (vary 220 24 fine) 0 255]}))

(defn- validate-options! [{:keys [kind width height seed scale]}]
  (when-not (material-kinds kind)
    (throw (ex-info "unsupported procedural material kind"
                    {:kind kind :supported material-kinds})))
  (when-not (and (pos-int? width) (pos-int? height))
    (throw (ex-info "material dimensions must be positive integers"
                    {:width width :height height})))
  (when-not (and (integer? seed) (<= 0 seed 4294967295))
    (throw (ex-info "material seed must be an unsigned 32-bit integer" {:seed seed})))
  (when-not (pos-int? scale)
    (throw (ex-info "material scale must be a positive integer" {:scale scale}))))

(defn bake-pbr-material
  "Bake a complete glTF metallic-roughness texture set.

   `kind` is :steel, :masonry or :ground. `seed` and integer texel coordinates
   fully determine the bytes. `scale` controls feature size and defaults to 8.
   Albedo is sRGB; tangent normals and G-roughness/B-metallic data are linear."
  [{:keys [kind width height seed scale] :or {seed 0 scale 8} :as options}]
  (validate-options! (assoc options :seed seed :scale scale))
  (let [pixel-fn (case kind
                   :steel steel-pixel
                   :masonry masonry-pixel
                   :ground ground-pixel)
        pixels (for [y (range height) x (range width)]
                 (pixel-fn seed scale x y))
        channel (fn [k] (vec (mapcat k pixels)))]
    {:albedo (texture/rgba8 width height (channel :albedo) :srgb)
     :normal (texture/rgba8 width height (channel :normal) :linear)
     :metallic-roughness
     (texture/rgba8 width height (channel :metallic-roughness) :linear)}))
