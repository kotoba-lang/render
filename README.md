# kotoba-lang/render

Zero-dep-ish (JVM-resource-loading only) portable `.cljc` — restored from the
legacy `kami-engine/kami-render` Rust crate as part of the **clj-wgsl
migration** (ADR-2607010930, `com-junkawasaki/root`, Phase 4/5).

`kami-render` was `kami-engine`'s wgpu unified renderer: PBR shading, mesh
generation + glTF/VRM loading, camera math, textures, KTX2/UASTC (Basis
Universal) transcoding, `EXT_meshopt_compression` decoding, 3D Gaussian
Splatting, and software-BVH ray tracing — one wgpu backend covering every
platform.

## What's ported (pure CPU, zero GPU dependency, tested)

| Namespace | From | What |
|---|---|---|
| `kotoba.render.camera` | `camera.rs` | Perspective/orthographic/map-view projection matrices, view matrix, camera-mode state machine (orbit/FPS/map-view/side-scroll), directional-light uniform, and all `MaterialUniform` PBR/SSS/hair/eye/clearcoat presets |
| `kotoba.render.mesh` | `mesh.rs` | Procedural mesh generators (sphere/plane/cube/hex-prism/cylinder-pipe/building-extrusion/hex-grid), vertex interleaving, MikkTSpace-lite tangent computation, grid-instance transform generation |
| `kotoba.render.asset` | `asset.rs` | `AssetCache` — the mesh/material handle lookup table (a pure map; GPU handles are opaque values here) |
| `kotoba.render.splat` | `splat.rs` | `SplatCloud` data + cull/bounds algorithms for 3D Gaussian Splatting |
| `kotoba.render.splat-loader` | `splat_loader.rs` | `.splat` (antimatter15), PLY (ASCII + binary), and SPZ (Niantic gzip container) Gaussian-splat parsers — bit-exact vs. the Rust source's own test fixtures |
| `kotoba.render.meshopt` | `meshopt.rs` | The **full** `EXT_meshopt_compression` scalar decoder port: vertex-buffer, index-buffer, index-sequence codecs + oct/quat/exponential vertex filters — validated bit-exact against real `zeux/meshoptimizer` C++-encoder output embedded in the Rust source's tests |
| `kotoba.render.gltf` | `gltf_loader.rs` | The pure pieces: `KHR_mesh_quantization` dequantization, GLB container framing (magic/chunks), base64 `data:` URI decode, per-triangle normal generation |
| `kotoba.render.pipeline-specs` | `pipeline_specs.rs` | The `PIPELINE_SPECS` cull/depth/blend table, ported to `resources/kotoba/render/pipeline_specs.edn` (this repo's authority copy) |
| `kotoba.render.texture` | `texture.rs` | Mip-level-count math + the CPU box-filter mipmap downsampler (pure byte-array transform) + the three 1x1 fallback-texture pixel constants |
| `kotoba.render.logo` | `logo.rs` | Boot-logo SVG + brand colors + splash-screen fade/progress timing state machine |
| `kotoba.render.raytrace` | `raytrace.rs` | `RtGlobals` uniform constructor (the CPU-side data the RT compute pass uploads) |
| `kotoba.render.bits` | (new) | Shared byte/IEEE754 helpers (`f32-le`, `half->f32`, `i24-le`, `i32-bits->f32`, ...) used across the loaders/decoders above |

`clojure -M:test` — **61 tests, 637 assertions, 0 failures.** Several suites
(`meshopt_test`, `splat_loader_test`) reuse the *exact byte fixtures* the Rust
source says were produced by the real reference encoders (`zeux/meshoptimizer`
C++, Niantic SPZ), so passing them is a genuine bit-exact-port proof, not just
"code runs."

## What's adapter-only (left unported, and why)

This is GPU/rendering code — per ADR-2607010930's own established pattern
("hot loop stays native/WGSL; CLJ authors + dispatches"), the actual wgpu
pipeline setup, shader compilation, and draw/dispatch calls are genuinely
host-adapter, not domain logic to port:

- **`pipeline.rs`, `bootstrap.rs`, `wgpu_renderer.rs`, `scene_pipelines.rs`,
  `splat_pipeline.rs`** — wgpu device/pipeline/bind-group/render-pass setup.
  205–277 `wgpu::` references per file; there is no CPU-side algorithm here
  to extract, it's GPU command recording.
- **`raytrace.rs`'s `RayTracePipeline`** (compute pipeline + BVH buffer
  upload + dispatch) — only its `RtGlobals` uniform constructor is portable
  (ported above); the dispatch itself is a wgpu compute pass.
- **`texture.rs`'s `GpuTexture`/`create_texture`/`default_*_texture`** —
  actual `wgpu::Device` texture/sampler creation and `queue.write_texture`
  upload. Their **pixel data** is ported (see table above).
- **`tests/rt_gpu.rs`** — a headless-GPU integration test that runs the real
  `rt_bvh_compute.wgsl` shader on an actual adapter. Nothing to port; it's a
  hardware test.
- **The 15 `shaders/*.wgsl` files** (1358 lines total) — PBR, MToon (VRM
  toon shading), metahuman skin/hair (dual-lobe SSS, anisotropic Marschner),
  scene atlas/character/particle/sky/terrain/vegetation/voxel/water,
  gaussian-splat rasterization, RT BVH compute, and hair-strand compute.
  These are genuinely WGSL — per ADR-2607010930 the compute/render hot loop
  belongs in WGSL, authored/dispatched by CLJ, never reimplemented as a CLJC
  interpreter loop. Summarized, not reproduced verbatim, per the porting
  brief for this task.
- **`gltf_loader.rs`'s `load_glb` document assembly** (materials/nodes/
  skins/primitives/morph-target iteration walking a `gltf::Document`) — this
  drives the external `gltf` Rust crate's parsed object model. Fully
  reimplementing that would mean porting a third-party glTF JSON-schema
  library, not `kami-render` itself; out of scope for a one-crate port. The
  genuinely pure CPU logic *inside* that function (dequantization scaling,
  GLB container framing, base64 decode, normal generation) IS ported to
  `kotoba.render.gltf`.
- **`basisu.rs` + `uastc_tables.rs` + `uastc_vectors.rs`** (1138 lines) — the
  UASTC LDR block decoder (Huffman mode table + ASTC endpoint/weight
  interpolation, `unpack_uastc` port) and KTX2 container parsing. This is
  pure CPU code with **no** GPU dependency and, like `meshopt.rs`, ships its
  own bit-exact reference vectors (`uastc_vectors.rs`) — genuinely portable
  and a good target for a **follow-up port**. It was left out of this pass
  because a correct UASTC decoder needs all ~19 block modes ported together
  (a partial decoder can't correctly decode real KTX2 assets — wrong output
  is worse than no decoder) and the reference-vector coverage needs
  auditing mode-by-mode before trusting a translation; that's a
  proportionally large, separate effort from the rest of this crate.
- **`decode_meshopt_glb`** (`meshopt.rs`) — the GLB-container/glTF-JSON
  orchestration that locates `EXT_meshopt_compression` buffer views and
  rewrites the JSON. This is plumbing *around* the codec (would need a JSON
  library this zero-dep repo doesn't take on) — the actual codec functions
  it calls (`decode_vertex_buffer`, `decode_index_buffer`, the vertex
  filters) ARE ported and are the substantive `EXT_meshopt_compression` port.
- **`mesh.rs`'s `instances_to_frame`** — built a `kami_core::ipc::Frame`;
  that type lives in the separate `kami-core` crate (KAMI IPC substrate),
  out of scope for a `kami-render`-only port. `grid_instances`, the pure
  transform-matrix generator upstream of it, IS ported.
- **`Renderer` trait / `DrawCmd` / `MeshHandle` / `MaterialHandle`** (in
  `lib.rs`) — the host-adapter interface contract itself (upload/draw/present/
  resize against a real GPU device). Not data to port; it's the boundary.

## Develop

```bash
clojure -M:test    # 61 tests, 637 assertions, 0 failures
clojure -M:lint     # clj-kondo, 0 errors/warnings
```

## License

Apache License 2.0.
