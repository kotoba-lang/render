# kotoba-lang/render

## Heightfield terrain patches

`kotoba.render.terrain` bakes deterministic heightfield patches with high,
medium and low canonical-grid LODs, analytic normals, continuous UVs, explicit
bounds and crack-hiding skirts. `webgpu-registration` emits portable `:mesh`
entries for the shared WebGPU/WebGL geometry registry.

```clojure
(terrain/webgpu-registration
 :island {:patch [0 0] :size 64 :base-segments 32
          :amplitude 9 :seed 2654435769 :skirt-depth 3})
```

## Procedural vegetation

`kotoba.render.vegetation` provides deterministic broadleaf, conifer and shrub
meshes with high/low screen-space LODs, explicit bounds and generic WebGPU mesh
registration data. Mesh tuples remain compatible with the existing loaded-mesh
and tangent-interleaving pipeline.

## Procedural building silhouettes

`kotoba.render.building/building-mesh` generates deterministic stepped-tower
and industrial-block geometry as the existing `[positions normals uvs indices]`
tuple. `building-lods` supplies high, medium and low forms ordered for the
shared screen-space LOD selector. Generated geometry can flow directly through
`loaded-mesh`, tangent computation and 48-byte tangent interleaving.

## Deterministic procedural materials

`kotoba.render.procedural/bake-pbr-material` builds complete steel, masonry and
ground PBR texture sets from integer dimensions, scale and seed. The pure CLJC
baker uses a specified coordinate hash rather than global randomness, and emits
the existing `:kotoba.render/texture-rgba8-v1` descriptors consumed by browser
and native adapters. Bake these descriptors into release assets; runtime hosts
remain responsible only for upload and mip handling.

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
| `kotoba.render.streaming` | portable | Deterministic camera-centred residency, class radii, hysteretic LOD, and memory/draw evidence |
| `kotoba.render.splat-loader` | `splat_loader.rs` | `.splat` (antimatter15), PLY (ASCII + binary), and SPZ (Niantic gzip container) Gaussian-splat parsers — bit-exact vs. the Rust source's own test fixtures |
| `kotoba.render.meshopt` | `meshopt.rs` | The **full** `EXT_meshopt_compression` scalar decoder port: vertex-buffer, index-buffer, index-sequence codecs + oct/quat/exponential vertex filters — validated bit-exact against real `zeux/meshoptimizer` C++-encoder output embedded in the Rust source's tests |
| `kotoba.render.gltf` | `gltf_loader.rs` | The pure pieces: `KHR_mesh_quantization` dequantization, GLB container framing (magic/chunks), base64 `data:` URI decode, per-triangle normal generation |
| `kotoba.render.pipeline-specs` | `pipeline_specs.rs` | The `PIPELINE_SPECS` cull/depth/blend table, ported to `resources/kotoba/render/pipeline_specs.edn` (this repo's authority copy) |
| `kotoba.render.texture` | `texture.rs` | Mip-level-count math + the CPU box-filter mipmap downsampler (pure byte-array transform) + the three 1x1 fallback-texture pixel constants |
| `kotoba.render.logo` | `logo.rs` | Boot-logo SVG + brand colors + splash-screen fade/progress timing state machine |
| `kotoba.render.raytrace` | `raytrace.rs` | `RtGlobals` uniform constructor (the CPU-side data the RT compute pass uploads) |
| `kotoba.render.bits` | (new) | Shared byte/IEEE754 helpers (`f32-le`, `half->f32`, `i24-le`, `i32-bits->f32`, ...) used across the loaders/decoders above |
| `kotoba.render.material` | shared contract | Validated metallic/roughness PBR material data and fixed-shape GPU uniform packing |
| `kotoba.render.environment-bake` | offline tool | Deterministic irradiance convolution, GGX specular prefilter and split-sum BRDF LUT bake to gzip EDN |
| `kotoba.render.shadow` | shared contract | Cascaded directional-shadow split, atlas, bias, and pass planning |
| `kotoba.render.post-process` | shared contract | HDR frame graphs: SSAO/SSR/bloom/DoF/motion blur/tone mapping/AA/color grading |
| `kotoba.render.lod` | shared contract | Screen-space LOD with hysteresis and deterministic density budgets |
| `kotoba.render.quality` | shared contract | Versioned mobile/balanced/high/cinematic plans for SDK, Studio, and backends |
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

### Offline production IBL bake

The runtime environment contract intentionally contains already-convolved
bytes. Generate a production-sized analytic studio environment offline rather
than checking thousands of handwritten integers into a scene:

```bash
clojure -M:ibl-bake --out target/ibl/studio-pbr-environment.edn.gz
```

The deterministic defaults produce a daylight-balanced 32px diffuse irradiance cube, a 128px
GGX-prefiltered specular cube with its complete 128→1 roughness mip chain, and
a 128×128 split-sum BRDF LUT. The gzip EDN expands directly to the existing
`:kotoba.render/pbr-environment-v1` map and is validated again by
`kotoba.render.environment-bake/read-baked`.

Applications should publish the generated artifact and reference its URL from
scene data. A build adapter can call `read-baked`; a browser adapter can fetch,
decompress and EDN-read it before renderer initialization. In both cases the
decoded value is passed through the existing `:environment` option—there is no
new runtime render contract and no convolution on the render thread.

The analytic daylight base is bounded linear RGB with a cool sky and warm
ground bounce. It is calibrated for the renderer's default exposure (`1.0`);
applications should adjust exposure only for artistic intent, not to recover
black-crushed detail from the environment asset.

For a custom offline profile, call
`kotoba.render.environment-bake/bake-environment` with the same keys as
`production-config`, then `write-baked!`. Fixed Hammersley samples and an
analytic seam-free source make identical configuration produce identical
bytes across runs.

```bash
clojure -M:test     # full portable + bake suite
clojure -M:lint     # clj-kondo, 0 errors/warnings
```

## Portable combat-character silhouettes

`kotoba.render.character` generates grounded high/low combat operators from
head, torso, pelvis, limb, shoulder and weapon primitives. Its WebGPU registry
uses the generic portable `:mesh` contract and retains normalized bounds plus
stable joint and weapon/back socket metadata under
`:kotoba.render/character-rig-v1`. The current mesh is static; adapters can use
that metadata for a future segmented or skinned animation path without changing
scene geometry keys.

## License

Apache License 2.0.

## Terrain-following road ribbons

`kotoba.render.road` bakes a whole polyline into one continuous indexed ribbon.
It samples `kotoba.render.terrain/height-at` through a canonical bilinear sampler,
shares junction rows, keeps distance-based UVs continuous, and limits sharp-corner
miters. `road-mesh-parts` exposes separate `:surface` and `:shoulder` meshes with
byte-equal shared boundaries so executors can bind distinct PBR materials without
reintroducing seams. `webgpu-registration` emits high/medium/low keys per part.

The `:marking` material part is generated from the same centerline, cumulative
distance and height sampler. Its deterministic `:dash-length`, `:gap-length`,
`:phase`, `:offsets`, `:clearance` and per-LOD `:budget` contract keeps paint on
sloped terrain without bridging dash gaps or sharing the asphalt material.
# Shared world material presets

`kotoba.render.world-presets` owns renderer-neutral vegetation and architecture
looks. They do not belong to a WebGPU or native executor. A scene resolves one
role or a complete role palette as pure EDN:

```clojure
(require '[kotoba.render.world-presets :as presets])

(presets/resolve-preset
 {:family :stylized :domain :vegetation :role :foliage :entity-id "tree-42"})

(presets/role-palette
 {:family :stylized :domain :architecture :entity-id "depot-7"})
```

The result uses `:kotoba.render/material-preset-v1`, with stable preset IDs such
as `:stylized/vegetation-foliage` and `:photoreal/architecture-wall`. Both
families keep the same domain/role, outline, variation and LOD boundary;
`:stylized` selects complete toon-PBR overrides while `:photoreal` selects PBR.
Palette jitter is deterministic from `:entity-id`, never frame state or platform
hashing. `resolution-evidence` reports selected IDs/roles without GPU handles or
content payloads.

## Modular architecture and foreground detail kits

`kotoba.render.detail-kit/detail-kit` expands one building identity into portable
`:hero`, `:gameplay`, or `:crowd` detail. The EDN contains bevel-aware wall, roof,
window, trim and utility parts; bounded non-colliding foreground props; explicit
triangle budgets; generated high/medium/low building meshes; and
`:kotoba.render/material-preset-v1` role references. Variation is derived only
from the unsigned seed, so Studio, WebGPU and native executors receive identical
layouts. The stylized family is implemented. The photoreal sibling intentionally
returns the same keys with `:implementation-status :boundary-only` and
`:quality-claim :unimplemented` until photoreal assets meet that quality bar.

## Vegetation and ground-cover clusters

`kotoba.render.vegetation-cluster/vegetation-cluster` composes broadleaf,
conifer, shrub, grass, rock and flower instances into reusable `:foreground`,
`:midground` and `:background` density tiers. A deterministic golden-angle
best-candidate sequence avoids most footprint overlap without runtime RNG.
Every instance carries collision-none, wind, alpha-mask, outline and shared
`material-preset-v1` role data. The mesh library contains actual high/mid/low
portable tuples generated by the existing vegetation and mesh generators, while
instance/draw/triangle caps make density decisions auditable. Photoreal uses the
same API boundary but remains explicitly `:boundary-only` / `:unimplemented`.

## Stylized settlement composition

`kotoba.render.settlement/settlement` replaces repeated blockout towers with a
portable depot, habitat, industrial, utility and landmark composition. Hero,
mid and background tiers share an orthogonal street/block layout, deterministic
height bands and explicit constraints that reject consecutive identical
silhouettes and cap landmark/spire frequency. Circle and AABB clear regions are
excluded before placement. Each instance separates a navigation collision shell
from visual-only roof/window/trim/utility detail, while prototype entries retain
actual building LOD meshes and `detail-kit-v1` parts. Instance, draw and triangle
budgets are emitted with the result. Photoreal keeps the same top-level API but
is honestly marked `:boundary-only` and `:unimplemented`.

## Semantic facade articulation

`kotoba.render.facade/facade-kit` converts settlement archetypes into semantic
base, plinth, corner, floor-band, window bay/frame, recess, door, canopy,
signage, parapet, vent and pipe parts. Depot, habitat, industrial, utility and
landmark patterns have distinct bay/floor rhythms across hero, mid and
background tiers. Every visual-only part uses a deterministic
`material-preset-v1` role; window bays add seeded emissive variation. Rhythm
evidence enforces a bounded blank-wall ratio, and the result includes actual box
and cylinder mesh tuples, transforms and draw/triangle budgets.
`for-settlement-instance` derives dimensions and archetype directly from a
settlement collision shell. Photoreal returns the same boundary without claiming
an implementation.

## Character grounding and contact presentation

`kotoba.render.grounding/grounding-presentation` returns a portable stylized
contact-shadow presentation tied to the existing character rig. It includes
left/right foot anchors, an actual indexed XZ ellipse mesh, unlit multiply/AO
material data, renderer-ready transforms, and near/mid/far distance policy.
Near characters receive one restrained body ellipse plus grounded foot patches;
mid characters use one lower-resolution ellipse; far contact geometry is
removed. Evidence reports contact counts, opacity, clearance and shadow-to-body
ratios, including explicit no-floating/no-oversized checks. The photoreal family
uses the same API boundary but is marked `:unsupported-future`.

## Stylized material readability profiles

`kotoba.render.material-readability/material-profile` resolves actual portable
PBR plus toon records for skin, cloth, metal, visor and team accent. Records
retain the existing `kotoba.render.material` core while adding shade colour,
toon bands, rim and specular controls that executors may lower progressively.
Near/mid/far distance profiles reduce band/specular complexity without merging
semantic values. Evidence measures role luminance gaps, portable validity and
team contrast; blue/orange/neutral identity is redundantly encoded through hue,
value, emissive and distinct patterns for colour-blind readability.
`character-palette` maps existing fabric/armour/weapon/visor/accent mesh ranges
directly to these material records. Photoreal remains explicitly future work.

## Close-character and production framing contracts

`kotoba.render.close-character/presentation` provides actual portable PBR+toon
materials for face, eye, mouth, weapon receiver, barrel and optic roles. Close,
gameplay and crowd tiers scale rim/specular/toon treatment from projected
character height, while evidence enforces semantic contrast, face/weapon
occlusion and minimum silhouette feature budgets. `character-palette` maps
existing character ranges to these records.

`production-framing-evidence` is a renderer-independent, fail-closed capture
gate. Measured ground, environmental context, sky, floating-landmark and subject
ratios plus ground contact are checked against a versioned requirement map. A
sky-only crop, floating landmark, missing ground/context or implausible subject
scale is rejected as data before an image can claim production evidence.
Photoreal retains the same boundary and remains `:unsupported-future`.

## Neighborhood and road-junction composition v2

`kotoba.render.neighborhood/neighborhood` composes a coherent T or cross
junction in one coordinate system. The result contains actual terrain-following
arm, shoulder and marking meshes plus a central junction mesh; curb, sidewalk
and verge transforms; grounded building collision shells with generated LOD
meshes and semantic facades; and visual-only foreground prop/vegetation anchor
zones. Hero, mid and background tiers expose draw/triangle budgets. Evidence
proves shells and landmarks are grounded, below a safe skyline height and framed
with visible ground/junction context. Photoreal uses the same data boundary but
remains explicitly `:unsupported-future`.

`:safe-height` is an authoring constraint, not evidence-only metadata: generated
building meshes, collision shells and facade dimensions reserve parapet headroom
and are resolved below the cap. Evidence derives its shell and facade extents
from that actual output. Each anchor zone also includes deterministic directly
renderable descriptors—geometry tuple/reference, full material, transform and
collision-none policy—for foreground props and vegetation.

## Foreground density and material layering

`kotoba.render.foreground-density/foreground-kit` authors deterministic
foreground/midground camera zones containing shrubs, grass, crates, bollards,
rocks and debris. Every source mesh is normalized to X/Z `[-0.5,0.5]` and
grounded Y `[0,1]`; only the descriptor's `:world-size` transform owns scale,
preventing accidental double scaling. The same result includes directly
renderable road edge-wear, patch/decal and facade base/trim/window layers with
actual materials, geometry references, grounded transforms and depth bias.
Hero, mid and background tiers expose exact density, draw and triangle budgets.
Photoreal retains the boundary as explicit future work.

Each density descriptor also carries a deterministic balanced
`:composition-region` (`:foreground-left`, `:foreground-right`,
`:midground-left`, or `:midground-right`) and matching projection intent as
`:screen-side` (`:left` or `:right`). The renderer is responsible for validating
that intent against actual camera projection. Authoritative selection hints are
top-level `:ground-contact-screen-y-range`, `:screen-extent-range`,
`:cluster-id`, and `:cluster-role`. Foreground candidates use the normalized
screen ground band `[0.58,0.90]`, midground uses `[0.42,0.72]`, and per-kind
extent ranges prevent isolated oversized blobs or unreadably thin props. The
grass range permits readable blade clusters through `0.11` screen extent while
shrubs remain capped at `0.16`. Supplying normalized-or-normalizable
`:camera-facing-direction [x z]`—the ground-plane direction from the composition
origin/target **toward the camera**, not camera-forward from camera to look-at—
moves foreground vegetation into the near half-space independent of world
orientation. This keeps grounded clusters in the lower frame without weakening
the shared contact gate. Omitting it preserves the deterministic radial layout.
Deterministic paired clusters give every left/right hero and mid-tier foreground
region both vegetation and a solid prop/rock. Material layers count toward the
same actual instance budget. Road layers attach in `:neighborhood-world` space;
facade base/trim/window transforms attach to explicit `:building-facade`
anchors in `:facade-local` space and must be composed with a building transform.

Wave 13 enriches the normalized meshes rather than relying on selection
metadata alone. Low-detail grass contains five crossed blades with lateral and
depth mass; shrubs contain three overlapping canopy lobes; rocks use four
smaller angular vertex-deformed families. Every density descriptor exposes the
actual top-level `:geometry-variant`, and the effective seed includes entity and
origin context so adjacent kits do not repeat the same blobs.

The six-layer budget remains exact: one seven-island road-breakup mesh plus
facade-local base, high-value trim, a physically separated three-pane recessed
window bank, door, and stepped roof silhouette. Road breakup publishes a
top-level final-world `:bounds` and requires subject exclusion in
`:junction-center`. Facade descriptors publish `:facade-layer-bounds` in
`:facade-local-to-building`; a building candidate must compose these with its
building transform before submitting the resulting final-world AABB to KAMI's
projection gate. `:attachment-eligibility` uses the same target, space, and
anchor vocabulary as `:attachment`.
