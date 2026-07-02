(ns kotoba.render.texture
  "Pure CPU-side texture helpers ported from `kami-render/src/texture.rs`:
   mip-level-count math and the box-filter CPU mipmap downsampler (both were
   plain byte-array transforms in the Rust source, unconditionally compiled
   whether or not you're on the GPU path).

   NOT ported: `GpuTexture`, `create_texture`, `default_white_texture`,
   `default_normal_texture`, `default_mr_texture` — actual `wgpu::Device`
   texture/sampler creation and `queue.write_texture` upload. Those are
   host-adapter GPU calls; only their **pixel data** is portable, and is
   provided below as [[white-pixel]] / [[normal-pixel]] / [[mr-pixel]]."
  )

(defn mip-level-count
  "Number of mip levels for a `width` x `height` texture (full chain).
   `(1 + floor(log2(max(width, height))))`. Matches the Rust
   `(width.max(height) as f32).log2().floor() as u32 + 1`."
  [width height]
  (inc (long (Math/floor (/ (Math/log (double (max width height))) (Math/log 2.0))))))

(defn generate-mipmaps-cpu
  "Generate the full CPU-side mip chain via 2x2 box-filter downsampling.
   `base-data` is a flat RGBA8 pixel vector (unsigned ints 0..255, 4 per
   pixel, row-major). Returns a vector of `{:level :width :height :data}`
   maps for levels `1..mip-levels-1` (level 0 is the input, not repeated).
   Faithful port of `generate_mipmaps_cpu`'s box-filter downsample loop
   (clamped edge sampling — the last row/col repeats, matching
   `.min(w-1)`/`.min(h-1)` in the Rust source)."
  [base-data base-width base-height mip-levels]
  (loop [level 1 w base-width h base-height prev base-data out []]
    (if (>= level mip-levels)
      out
      (let [new-w (max 1 (quot w 2))
            new-h (max 1 (quot h 2))
            stride (* w 4)
            new-data
            (vec
             (for [y (range new-h) x (range new-w) c (range 4)]
               (let [sx (* x 2) sy (* y 2)
                     sum (reduce +
                                 (for [dy (range 2) dx (range 2)]
                                   (let [px (min (+ sx dx) (dec w))
                                         py (min (+ sy dy) (dec h))]
                                     (nth prev (+ (* py stride) (* px 4) c)))))]
                 (quot sum 4))))]
        (recur (inc level) new-w new-h new-data
               (conj out {:level level :width new-w :height new-h :data new-data}))))))

(def white-pixel
  "1x1 white pixel (fallback for untextured albedo)."
  [255 255 255 255])

(def normal-pixel
  "1x1 flat normal map (0.5, 0.5, 1.0 = up in tangent space)."
  [128 128 255 255])

(def mr-pixel
  "1x1 default metallic-roughness (metallic=0, roughness=0.5).
   glTF convention: G=roughness, B=metallic."
  [0 128 0 255])
