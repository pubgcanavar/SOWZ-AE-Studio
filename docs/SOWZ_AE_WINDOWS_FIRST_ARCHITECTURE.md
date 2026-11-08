# SOWZ AE — Windows-first, shared-engine architecture

## Product decision

SOWZ AE will be one editing product with two native frontends:

1. **Windows EXE first:** the primary precision-editing workspace, with mouse/keyboard interaction, a wide multi-track timeline, property panels, graph editor, project bin and render queue.
2. **Android APK second:** a touch-first frontend using the same project schema and editing commands. The Android app remains available while the desktop editor is being built.

This is not a choice to abandon Android. It is a build-order decision: the first complete editing workflow is easier to author and debug on a desktop-sized interface, then adapt for touch.

## Shared-core boundary

The new `engine/` Rust crate is deliberately independent of any UI. It owns:

- Versioned project, composition, track, layer, asset, effect and keyframe models.
- Validation of IDs, timing, dimensions, finite numeric values and asset references.
- Atomic edit commands: an invalid command cannot partially alter the open project.
- A bounded undo/redo history.
- The first `.sowzae` ZIP package reader/writer, with manifest and project JSON plus retained extra files.
- Defensive limits for ZIP entry count, per-file size, total uncompressed content and relative paths.

Windows and Android frontends must call the same command API. A UI control is not complete until it changes a real project object and save/reopen preserves that change.

## Windows target

- Windows 10 x64 and Windows 11 x64 are the first desktop targets.
- Prefer a native Rust UI for the desktop editor; avoid introducing a webview just to make the app appear cross-platform.
- Build a CPU-correct render path first. Add GPU paths only when output can be compared against the CPU reference.
- Include a low-memory / low-preview-quality mode for older Intel integrated graphics.
- Use proxy previews, frame caching, resolution scaling and explicit render settings rather than promising real-time full-quality playback on every PC.
- Package as a portable x64 EXE/ZIP first; add a signed installer only after repeatable release builds and update handling are ready.

## Android target

- Keep the current Kotlin/Media3 application and its touch-oriented UI.
- Add the shared core through a small Rust `cdylib` and JNI bridge after the model API settles.
- Keep Media3 and Android hardware codecs where they provide a better device-native path.
- Build arm64-v8a first, then add more ABIs only after CI and device tests.
- Use preview proxies, bounded frame caches and render-quality controls to protect memory, heat and battery.

## EffectCraft integration policy

EffectCraft is an upstream Rust motion-graphics project with MIT OR Apache-2.0 licensing. Its public repository advertises a native Windows build, a reusable crate architecture and a large effect catalogue. It also describes itself as alpha and estimates its readiness for full professional work at roughly 45%; breadth is not the same as production reliability.

Integration must therefore be selective and measurable:

- Pin a reviewed upstream commit, not a floating branch.
- Audit every crate, dependency, font, icon and other asset separately.
- Preserve MIT/Apache notices and upstream attribution.
- Remove EffectCraft/ArtCraft logos and marks from any SOWZ-branded fork or UI; those marks are not covered by the software licence.
- Evaluate time, geometry, colour, keyframe, project, effects, CPU render, GPU compositor, PSD, SVG, Lottie and AEP crates independently.
- Keep SOWZ project files and conversion reports explicit; do not imply `.ecproj` and `.sowzae` are interchangeable.
- Keep a compatibility matrix for each imported file type and each effect.

The public source does not justify claims that every After Effects project, preset, third-party plug-in or clipboard payload will work. No Adobe `.aex` binary is loaded as an Android plug-in. Imported scripts are inspected, never auto-executed.

## Feature-completion gate

A feature is only marked **implemented** when all of the following exist:

1. A real UI action and editable project data.
2. A preview implementation.
3. Export output with matching semantics.
4. Save/reopen persistence.
5. Automated tests, including malformed input where relevant.
6. At least one real-device or real-Windows build/performance check appropriate to the feature.

A parser accepting a file is not proof that its effects were reproduced. Lossy conversions must show a report before changing the project. Unsupported effects, fonts, media and expressions are preserved in metadata or reported; they must not be silently discarded.

## Delivery sequence

### Foundation
- [x] Add a shared Rust project model.
- [x] Add typed multi-track timeline entities.
- [x] Add atomic editing commands and a bounded undo/redo history.
- [x] Add a versioned `.sowzae` package with resource limits and extra-file preservation.
- [ ] Run native CI and fix any compile/test failures.
- [ ] Add Windows desktop executable shell and project browser.
- [ ] Connect the desktop shell to real file import and preview.

### Editing engine
- [ ] Multi-track video, audio, image, text and shape composition.
- [ ] Split, trim, move, ripple, snapping, markers and nesting.
- [ ] Transform animation and temporal/spatial Bezier curves.
- [ ] Property inspector, graph editor, effect stack and copy/paste of supported typed data.
- [ ] CPU compositor with masks, blend modes, mattes and layer parenting.
- [ ] Preview/export parity and render queue.

### Interchange and effects
- [ ] FFX parser fixtures and per-effect conversion reports.
- [ ] AEP/AEPX dry-run import using a reviewed EffectCraft AEP implementation.
- [ ] PSD/PSB, SVG and Lottie adapters.
- [ ] MOGRT and JSX inspection adapters; JSX remains static analysis only.
- [ ] Effect registry integration after licence, Android and Windows compatibility tests.
- [ ] Sandboxed WASM effect plug-ins with explicit permissions and resource limits.

### Mobile and release
- [ ] JNI bridge to the shared model and command layer.
- [ ] Android timeline adaptation and Media3 preview.
- [ ] Device profiling for memory, temperature, battery and export stability.
- [ ] Windows portable EXE and reproducible signed installer.
- [ ] Android APK release and cross-platform project round-trip tests.

## Current status

The new shared core currently contains project/timeline data structures, validation, editing commands, undo/redo, and a ZIP package implementation. It does **not** yet contain a video compositor, media decoder, live preview, export renderer, Windows UI, Android JNI bridge, FFX/AEP import integration or the EffectCraft effects catalogue. Those remain explicit next milestones.
