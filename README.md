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
| `kotoba.render.uastc` + `kotoba.render.basisu` | `basisu.rs` + `uastc_tables.rs` + `uastc_vectors.rs` | The **UASTC (Universal ASTC) LDR 4×4 block decoder** — a faithful `unpack_uastc` port (Huffman mode table, subset/partition patterns, dual-plane, trit/quint BISE endpoint decode, ASTC endpoint unquantization + weight interpolation) — plus KTX2 container parsing (`KHR_texture_basisu`, UASTC-only; ETC1S reported unsupported). Lookup tables machine-extracted verbatim from the Rust source. Validated bit-exact against the Rust source's own reference-encoder vectors (`uastc_vectors.rs`) — **see the UASTC mode-coverage note below** |

`clojure -M:test` — **66 tests, 1068 assertions, 0 failures.** Several suites
(`meshopt_test`, `splat_loader_test`, `uastc_test`) reuse the *exact byte
fixtures* the Rust source says were produced by the real reference encoders
(`zeux/meshoptimizer` C++, Niantic SPZ, basis_universal), so passing them is a
genuine bit-exact-port proof, not just "code runs."

### UASTC mode coverage (be precise — a wrong texture decoder is worse than none)

The decoder is a line-by-line port of `basisu.rs`'s `decode_uastc_block`
(itself a port of basis_universal's `unpack_uastc`) and *implements all 19
UASTC LDR block modes* — the mode dispatch, every subset/partition table
(`PATTERNS2`/`PATTERNS3`/`PATTERNS2_BC7M3`), dual-plane, luminance+alpha, and
the full trit/quint BISE endpoint decode are all present, driven by lookup
tables extracted verbatim (byte-for-byte, machine-parsed) from
`uastc_tables.rs`.

**Fixture-verified bit-exact (7 modes):** the `uastc_vectors.rs` reference set
(120 real encoder→`unpack_uastc` block pairs) covers modes **0, 4, 6, 8, 9,
11, 15**, and this port reproduces all 120 byte-for-byte. Crucially, those 7
modes already exercise the hardest, most error-prone shared machinery end to
end: **trit** endpoint BISE (modes 0, 11), **quint** endpoint BISE (modes 4,
6), **dual-plane** decode (modes 6, 11), **2-subset** partitioning (modes 4,
9), **luminance+alpha** endpoints (mode 15), and solid-color (mode 8).

**Implemented but not fixture-verified (12 modes):** 1, 2, 3, 5, 7, 10, 12,
13, 14, 16, 17, 18. No reference vectors exist for these in the Rust source, so
they are only validated by construction (faithful translation reusing the
same verified code paths + verified tables), not by golden bytes. Most reuse
paths already covered above; the genuinely *unexercised-by-fixture* pieces are
narrow and specific: the **3-subset** anchor path + `PATTERNS3` table (mode
3), the `PATTERNS2_BC7M3` partition table (mode 7), the 5-bit weight table
`WEIGHT_TABLES[5]` (mode 18), and the LA-plus-dual-plane / 2-subset-LA combos
(modes 16, 17). These are table-selection differences fed through
fixture-verified decode logic, but they have **not** been proven byte-exact
against a reference encoder here — treat modes outside {0,4,6,8,9,11,15} as
high-confidence-but-unverified until golden vectors for them are added.

## What's adapter-only (left unported, and why)

(UASTC/KTX2 note: the UASTC block decoder + KTX2 container parsing are now
**ported** — see above. Matching the Rust source's own scope, **ETC1S/BasisLZ**
supercompressed KTX2 and **Zstandard** level supercompression are deliberately
*not* decoded — they are detected and reported unsupported, exactly as
`basisu.rs` did; `ZLIB` level supercompression is supported on the JVM only,
like this repo's SPZ gzip.)

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
clojure -M:test    # 66 tests, 1068 assertions, 0 failures
clojure -M:lint     # clj-kondo, 0 errors/warnings
```

## License

Apache License 2.0.
