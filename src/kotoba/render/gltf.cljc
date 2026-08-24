(ns kotoba.render.gltf
  "Pure CPU-side glTF/GLB helpers ported from `kami-render/src/gltf_loader.rs`.

   NOT ported: `load_glb` and the `GltfScene`/`GltfNode`/`GltfSkin`/etc.
   document walk. That function's substance is *driving the `gltf` Rust
   crate's parsed `Document`* (materials/nodes/skins/primitives/morph-target
   iteration) — a full glTF JSON-schema object model this zero-dep port
   doesn't reimplement (that would mean porting the external `gltf` crate
   itself, out of scope for a one-crate port). What WAS genuinely pure CPU
   logic *within* that function is ported below as standalone, testable
   pieces:
     - [[generate-normals-from-tris]] — per-triangle face-normal accumulation
     - [[base64-decode]] — `data:` URI payload decode
     - [[glb-header]] / [[glb-chunks]] — GLB binary-container framing
       (magic/version/length + JSON/BIN chunk offsets), independent of any
       glTF *semantics*
     - [[dequantize-attr]] — `KHR_mesh_quantization` component-type decode
       (BYTE/UBYTE/SHORT/USHORT normalized-integer scaling), generalized to
       take a raw byte buffer + accessor shape instead of a `gltf::Accessor`"
  (:require [kotoba.render.bits :as bits]))

;; ---------------------------------------------------------------------------
;; base64 (data: URI payload)
;; ---------------------------------------------------------------------------

;; Code points as literals, and `char-code` instead of `int`.
;;
;; `(int \A)` is 65 on the JVM and 0 in ClojureScript -- `cljs.core/int` is
;; `(bit-or x 0)`, and `"A" | 0` is 0. Iterating a string gives Characters on
;; the JVM and one-character strings in ClojureScript, so `(int (first chars))`
;; was 0 for EVERY character. `(= c (int \=))` then read `0 = 0` -> true, so
;; every byte was skipped as padding and `base64-decode` returned `[]` for all
;; input on ClojureScript. It threw nothing: a glTF `data:` URI simply
;; decoded to an empty buffer.
;;
;; This is the defect `splat-loader/end-header` documents (2026-08-19), in the
;; file next to it. It survived because `gltf_test.cljc` was `.cljc` and
;; `run-tests.cljs` named four namespaces out of thirty-nine, so the
;; ClojureScript path was never run here. Measured 2026-08-24.
(def ^:private code-A 65)
(def ^:private code-Z 90)
(def ^:private code-a 97)
(def ^:private code-z 122)
(def ^:private code-0 48)
(def ^:private code-9 57)
(def ^:private code-plus 43)
(def ^:private code-slash 47)
(def ^:private code-eq 61)

(defn- char-code [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn- b64-val [c]
  (cond
    (and (>= c code-A) (<= c code-Z)) (- c code-A)
    (and (>= c code-a) (<= c code-z)) (+ (- c code-a) 26)
    (and (>= c code-0) (<= c code-9)) (+ (- c code-0) 52)
    (= c code-plus) 62
    (= c code-slash) 63
    :else nil))

(defn- ascii-whitespace? [c]
  (contains? #{9 10 11 12 13 32} c))

(defn base64-decode
  "Minimal standard-alphabet base64 decode (ignores padding/whitespace).
   Returns a vector of unsigned bytes, or nil on invalid input (matching
   the Rust source's `Option<Vec<u8>>`)."
  [s]
  (loop [chars (seq s) acc 0 bits-n 0 out []]
    (if (empty? chars)
      out
      (let [c (char-code (first chars))]
        (if (or (= c code-eq) (ascii-whitespace? c))
          (recur (rest chars) acc bits-n out)
          (let [v (b64-val c)]
            (if (nil? v)
              nil
              (let [acc' (bit-or (bit-shift-left acc 6) v)
                    bits' (+ bits-n 6)]
                (if (>= bits' 8)
                  (let [bits'' (- bits' 8)]
                    (recur (rest chars) acc' bits'' (conj out (bit-and (unsigned-bit-shift-right acc' bits'') 0xff))))
                  (recur (rest chars) acc' bits' out))))))))))

;; ---------------------------------------------------------------------------
;; GLB container framing (magic/version/length + chunk offsets)
;; ---------------------------------------------------------------------------

(def glb-magic 0x46546c67) ;; ASCII "glTF"
(def glb-chunk-json 0x4e4f534a) ;; ASCII "JSON"
(def glb-chunk-bin 0x004e4942) ;; ASCII "BIN\0"

(defn glb-header
  "Parse the 12-byte GLB header: `{:magic :version :length}`, or nil if
   `bytes` is too short / not GLB."
  [bytes]
  (when (>= (count bytes) 12)
    (let [magic (bits/u32-le bytes 0)]
      (when (= magic glb-magic)
        {:magic magic :version (bits/u32-le bytes 4) :length (bits/u32-le bytes 8)}))))

(defn glb-chunks
  "Walk the chunk stream after the 12-byte header, returning a vector of
   `{:type :offset :length}` (offset points at the chunk's payload, i.e.
   past its own 8-byte type+length prefix). Chunk types are compared
   against [[glb-chunk-json]] / [[glb-chunk-bin]] by the caller."
  [bytes]
  (loop [pos 12 out []]
    (if (> (+ pos 8) (count bytes))
      out
      (let [clen (bits/u32-le bytes pos)
            ctype (bits/u32-le bytes (+ pos 4))
            payload-off (+ pos 8)]
        (if (> (+ payload-off clen) (count bytes))
          out
          (recur (+ payload-off clen) (conj out {:type ctype :offset payload-off :length clen})))))))

(defn glb-json-chunk-bytes
  "Return the raw bytes (a subvec) of the first JSON chunk in a GLB, or nil."
  [bytes]
  (when (glb-header bytes)
    (when-let [chunk (first (filter #(= (:type %) glb-chunk-json) (glb-chunks bytes)))]
      (subvec (vec bytes) (:offset chunk) (+ (:offset chunk) (:length chunk))))))

;; ---------------------------------------------------------------------------
;; KHR_mesh_quantization dequantization
;; ---------------------------------------------------------------------------

(def component-sizes
  "Accessor componentType byte sizes (glTF component-type keyword -> bytes)."
  {:i8 1 :u8 1 :i16 2 :u16 2 :u32 4 :f32 4})

(defn dequantize-attr
  "Read a `comps`-wide vertex attribute from a `KHR_mesh_quantization`
   buffer, returning f32-range values (as doubles) per the glTF
   normalized-integer scaling rules. Generalizes Rust's
   `read_quantized_attr`, decoupled from `gltf::Accessor` — the caller
   supplies the accessor shape directly:

   `component-type` — one of `:i8 :u8 :i16 :u16 :u32 :f32`
   `normalized?`     — whether integer components are normalized-integer scaled
   `comps`           — components per element (e.g. 3 for VEC3)
   `count`           — element count
   `stride`          — bytes between consecutive elements (defaults to
                        `comp-size * comps` when nil, i.e. tightly packed)
   `base`            — byte offset of the first element in `bytes`"
  [bytes component-type normalized? comps count stride base]
  (let [comp-size (get component-sizes component-type)
        elem-size (* comp-size comps)
        stride (or stride elem-size)]
    (vec
     (for [i (range count) c (range comps)]
       (let [o (+ base (* i stride) (* c comp-size))]
         (case component-type
           :f32 (bits/f32-le bytes o)
           :u32 (double (bits/u32-le bytes o))
           :i16 (let [x (bits/i16-le bytes o)]
                  (if normalized? (max (/ x 32767.0) -1.0) (double x)))
           :u16 (let [x (bits/u16-le bytes o)]
                  (if normalized? (/ x 65535.0) (double x)))
           :i8 (let [xi (bits/u8 bytes o)
                     signed (if (>= xi 128) (- xi 256) xi)]
                 (if normalized? (max (/ signed 127.0) -1.0) (double signed)))
           :u8 (let [x (bits/u8 bytes o)]
                 (if normalized? (/ x 255.0) (double x)))))))))

;; ---------------------------------------------------------------------------
;; Normal generation (flat/face-weighted, matches generate_normals_from_tris)
;; ---------------------------------------------------------------------------

(defn generate-normals-from-tris
  "Generate per-vertex normals from `positions` (flat xyz vector) and
   triangle `indices` (or nil for no accumulation, matching the Rust
   `Option<&[u32]>`). Accumulates unnormalized per-triangle face normals
   (cross product, CCW winding) at each triangle's 3 vertices, then
   normalizes; degenerate (all-zero) accumulations default to +Y."
  [positions indices]
  (let [vertex-count (quot (count positions) 3)
        p (fn [i] [(nth positions (* i 3)) (nth positions (inc (* i 3))) (nth positions (+ 2 (* i 3)))])
        v- (fn [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
        cross (fn [[ax ay az] [bx by bz]]
                [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
        acc (if indices
              (reduce
               (fn [acc [i0 i1 i2]]
                 (let [e1 (v- (p i1) (p i0)) e2 (v- (p i2) (p i0)) fn3 (cross e1 e2)]
                   (reduce (fn [a idx] (update a idx #(mapv + % fn3))) acc [i0 i1 i2])))
               (vec (repeat vertex-count [0.0 0.0 0.0]))
               (partition 3 indices))
              (vec (repeat vertex-count [0.0 0.0 0.0])))]
    (vec
     (mapcat
      (fn [n]
        (let [len (Math/sqrt (double (reduce + (map * n n))))]
          (if (< len 1e-12) [0.0 1.0 0.0] (mapv #(/ % len) n))))
      acc))))
