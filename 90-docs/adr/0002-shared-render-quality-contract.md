# ADR 0002: Shared render-quality contract

- Status: Accepted
- Date: 2026-07-15

## Context

KAMI samples need shadows, physically based materials, post-processing, and
large-scene LOD/density control. Encoding those decisions in one GPU backend
would make WebGPU, WebGL2, native wgpu, Studio previews, and SDK clients diverge.

## Decision

`kotoba-lang/render` owns pure portable `.cljc` contracts and planning logic:

- `kotoba.render.material`: metallic/roughness PBR values and packing shape.
- `kotoba.render.shadow`: cascaded-shadow splits, atlas and render passes.
- `kotoba.render.post-process`: ordered HDR frame-graph passes.
- `kotoba.render.lod`: projected-size LOD, hysteresis and density budgets.
- `kotoba.render.quality`: named cross-feature profiles and versioned plans.

Backends own GPU resources, shaders, draw/compute commands, and capability
fallback. They consume the shared data plan and report unsupported features.

## Consequences

SDK, Studio, samples and runtimes share deterministic decisions. Pure algorithms
remain GPU-independent and testable. Visual correctness still requires backend
shader, golden-image, hardware and performance tests. Temporal passes require
motion vectors and history buffers supplied by the backend.
