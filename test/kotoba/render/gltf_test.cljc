(ns kotoba.render.gltf-test
  "New tests for the pure CPU-side glTF/GLB pieces ported from
   `kami-render/src/gltf_loader.rs` (that file's own `#[cfg(test)]` block
   tested full `load_glb` document assembly, which is NOT ported here —
   see the ns docstring in `kotoba.render.gltf`). The
   `khr-mesh-quantization-dequantizes` case below reuses the exact byte
   layout from the Rust source's
   `khr_mesh_quantization_dequantizes_positions_and_normals` test, applied
   to the standalone [[kotoba.render.gltf/dequantize-attr]] function."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.render.gltf :as gltf]))

(deftest base64-decode-roundtrip
  ;; "Man" -> base64 "TWFu" (classic example)
  (is (= (gltf/base64-decode "TWFu") [77 97 110])))

(deftest base64-decode-ignores-padding-and-whitespace
  (is (= (gltf/base64-decode "TWE=\n") [77 97])))

(deftest glb-header-and-chunks
  (let [json-bytes (map int "{}  ") ;; already 4-byte aligned
        total (+ 12 8 (count json-bytes))
        hdr [(bit-and gltf/glb-magic 0xff) (bit-and (bit-shift-right gltf/glb-magic 8) 0xff)
             (bit-and (bit-shift-right gltf/glb-magic 16) 0xff) (bit-and (bit-shift-right gltf/glb-magic 24) 0xff)
             2 0 0 0
             (bit-and total 0xff) (bit-and (bit-shift-right total 8) 0xff)
             (bit-and (bit-shift-right total 16) 0xff) (bit-and (bit-shift-right total 24) 0xff)]
        chunk-len (count json-bytes)
        chunk-hdr [(bit-and chunk-len 0xff) (bit-and (bit-shift-right chunk-len 8) 0xff) 0 0
                   (bit-and gltf/glb-chunk-json 0xff) (bit-and (bit-shift-right gltf/glb-chunk-json 8) 0xff)
                   (bit-and (bit-shift-right gltf/glb-chunk-json 16) 0xff) (bit-and (bit-shift-right gltf/glb-chunk-json 24) 0xff)]
        bytes (vec (concat hdr chunk-hdr json-bytes))]
    (is (some? (gltf/glb-header bytes)))
    (is (= (:version (gltf/glb-header bytes)) 2))
    (let [chunks (gltf/glb-chunks bytes)]
      (is (= (count chunks) 1))
      (is (= (:type (first chunks)) gltf/glb-chunk-json)))
    (is (= (gltf/glb-json-chunk-bytes bytes) (vec json-bytes)))))

(deftest khr-mesh-quantization-dequantizes
  ;; One triangle. POSITION = i16 (non-normalized). NORMAL = i8 normalized
  ;; (127 -> 1.0). Same byte layout as the Rust source's test.
  (let [verts [[0 0 0] [1000 0 0] [0 1000 0]]
        i16-le (fn [v] (let [v (if (neg? v) (+ v 65536) v)] [(bit-and v 0xff) (bit-and (bit-shift-right v 8) 0xff)]))
        pos-bytes (vec (mapcat (fn [[x y z]] (concat (i16-le x) (i16-le y) (i16-le z) [0 0])) verts))
        norm-bytes (vec (mapcat (fn [_] [0 0 127 0]) (range 3)))
        positions (gltf/dequantize-attr pos-bytes :i16 false 3 3 8 0)
        normals (gltf/dequantize-attr norm-bytes :i8 true 3 3 4 0)]
    (is (= (subvec positions 0 3) [0.0 0.0 0.0]))
    (is (= (subvec positions 3 6) [1000.0 0.0 0.0]))
    (is (= (subvec positions 6 9) [0.0 1000.0 0.0]))
    (is (< (Math/abs (- (nth normals 2) 1.0)) 1e-4))))

(deftest generate-normals-flat-quad-is-unit-and-axis-aligned
  ;; Flat quad on the XZ plane: face normal must be unit-length and
  ;; purely along Y (sign depends on winding — (0,1,2)=(0,0,0)(1,0,0)(1,0,1)
  ;; is CW as seen from +Y, so cross(e1,e2) points -Y here).
  (let [pos [0.0 0.0 0.0  1.0 0.0 0.0  1.0 0.0 1.0  0.0 0.0 1.0]
        idx [0 1 2 0 2 3]
        norms (gltf/generate-normals-from-tris pos idx)]
    (doseq [[nx ny nz] (partition 3 norms)]
      (is (< (Math/abs nx) 1e-6))
      (is (< (Math/abs (- (Math/abs ny) 1.0)) 1e-6))
      (is (< (Math/abs nz) 1e-6)))))

(deftest generate-normals-no-indices-defaults-up
  (is (= (gltf/generate-normals-from-tris [0.0 0.0 0.0] nil) [0.0 1.0 0.0])))
