# SOWZ AE Studio — competitive research and technical direction

Updated: 2026-11-09

This document records a source-based comparison and an implementation direction for the Android SOWZ AE Studio repository. The goal is a genuinely editable motion-graphics/video app, not a static UI replica.

## 1. Products reviewed

### NovaVision Hub — Nova Editor

Official product page: https://www.novavisionhub.com/novaeditor

The page lists Nova Editor v0.756.0, updated 2026-10-22, for Android 8.0+, with a 195 MB APK. Its release notes describe mobile video editing with After Effects-style effects. The v0.756.0 notes include S_Shake fixes on adjustment layers, a Pen Tool, 28 Alight Motion blending modes, YY_Ramp+, BCC Magic Sharp, Radial Rays, Linear Streaks, and compositing/performance improvements.

Important boundary: those release notes do not establish general .aep project import or execution of desktop After Effects plug-ins. Do not infer project-format compatibility from the presence of AE-style effects.

### EffectCraft — ArtCraft project

Official repository: https://github.com/storytold/effectcraft  
Project overview: https://effectcraft.org/

The repository describes a pure-Rust motion-graphics/compositing application with compositions, footage/shape/text layers, keyframes, graph editing, effects, 3D cameras/lights, and a render queue. Its project format is versioned JSON (.ecproj), not .aep. The project explicitly says .aep/.aepx files and After Effects plug-ins are not supported.

The repository is dual-licensed MIT OR Apache-2.0. Its architecture documentation describes an engine-first crate stack: time, geometry and colour primitives are independent of the UI; a shared Session/command registry is driven by the desktop UI, CLI and automation interfaces. That separation is the strongest lesson for SOWZ.

EffectCraft also documents clean-room rules and individual attribution for bundled assets. This SOWZ change does not copy EffectCraft source, icons, artwork, fonts, presets or brand assets. It uses public product and architecture descriptions as design research only. If code is ever reused later, review the exact crate and asset licences, retain required notices, and check Android portability and dependency licences first.

### AndroidX Media3

Official multi-asset editing guide: https://developer.android.com/media/media3/transformer/composition

Media3 Composition contains one or more EditedMediaItemSequence tracks. This can represent sequential video, an overlapping video overlay, or a separate audio sequence. EditedMediaItem supports per-item effects. The official guide documents preview and export workflows, but lists crossfading tracks as unsupported by the Transformer Composition API at the time of the guide update (2026-09-08).

This is the most practical near-term route for the current Android application because Media3 Transformer is already a dependency in this repository.

## 2. What exists in SOWZ today

Verified from the current repository source:

- Native Android/Kotlin app; minSdk 24, compileSdk 36, Java/Kotlin target 17.
- Media3 ExoPlayer preview and Transformer MP4 export.
- Main-clip IN/OUT trim, sequential additional video clips, brightness, contrast, quarter-turn rotation, static text overlay and .cube 3D LUT.
- .sowzproj JSON save/reopen using source URI references.
- AEP/AEPX/AET structural inspection and an FFX parser. The only FFX value translation currently applied to the video editor is the recognized static ADBE Brightness & Contrast subset.
- TimelineModel.kt already contains a testable layer/keyframe model, but the README correctly notes it is not yet wired into a full composited preview/export timeline.

The editor currently exports a single ordered sequence; it is not yet a true overlapping multi-track compositor.

## 3. Architecture direction

### Keep Android first; separate the engine from the UI

Do not replace the current Android app with a desktop egui frontend. Instead, carry over the useful architectural principle from EffectCraft:

1. core: platform-independent project, track, clip, time, keyframe, effect and command models.
2. media: Android Media3 adapters for probing, preview, audio processing and render/export.
3. ui: Android screens and panels that issue core commands; no timeline math hidden inside view callbacks.
4. formats: isolated import/export adapters, each with explicit support levels and test fixtures.

All edits should be commands with deterministic inputs/results. That enables undo/redo, reliable autosave, tests and later scripting without coupling the model to the UI.

### Multi-track rendering

Build toward one EditedMediaItemSequence per track. Keep clip start, source in/out and track order in the project model. Add CompositionPlayer preview before claiming preview/export parity. Export must use the exact same clip trims and effects as preview.

Do not promise crossfades until they are implemented by a supported Media3 path or a separate tested compositor. A sequence queue is useful, but it is not an overlapping timeline.

### Effect system

Use native, individually tested effects first. Every effect definition should contain:

- stable effect ID and version;
- typed parameters with ranges/defaults;
- effect order and enabled state;
- a preview implementation;
- an export implementation with matching behaviour;
- a fixture and visual regression test.

Start with colour, blur/sharpen, transform, text, mask and blend primitives. Third-party AE plug-in binaries are not an Android effect API. Reimplement compatible behaviours natively only where there is a lawful specification/behavioural basis and a testable implementation.

### UI direction

EffectCraft's docked Project/Composition/Timeline/Effect Controls/Effects & Presets panel model is a good workflow reference. On phones, use a mobile adaptation rather than copying desktop density:

- viewer as the visual anchor;
- horizontally scrollable timeline with distinct video/audio/overlay tracks;
- selected clip controls in a bottom inspector sheet;
- compact asset/effect browser;
- gesture-safe handles, snap guides, undo/redo and clear export progress.

No control should appear functional unless it performs a real edit.

## 4. File-format compatibility policy

| Format | Current repository status | Honest target |
|---|---|---|
| MP4 / common video | Local preview and MP4 export work for supported device codecs | Probe codec/profile per device; report unsupported sources clearly |
| MOV / MKV / WebM | No universal guarantee | Import where Android decoder support exists; test by codec/container fixture |
| WAV / MP3 / audio | Not yet a separate audio track in the editor | Add an independent audio sequence, waveform and volume controls |
| PNG / JPEG | Not a full image-layer workflow yet | Still-image layer with duration/transform support |
| PSD | No current layered PSD import | Consider a licensed PSD parser; preserve layers only when parser coverage is verified |
| SVG | No current SVG layer | Secure SVG parsing and raster/vector preview as a separate adapter |
| .cube | Implemented 3D LUT import and application | Keep fixtures for size/domain variations |
| .aep | Partial structural inspection only | Never claim full opening/editing; import only confidently recognized public structures |
| .aepx / .aet | Best-effort XML inspection | XML subset importer with warnings and unsupported-property report |
| .ffx | Parser recognises some effect metadata/static values; static Brightness & Contrast mapping only | Add mappings one by one with reference renders; do not execute payloads or claim arbitrary preset compatibility |
| .aex plug-ins | Not supported | Desktop plug-in binaries cannot simply run in Android; implement native equivalents individually |
| .mogrt | Not supported | At most inspect package metadata/assets first; real template import depends on contained project/effects/fonts |
| .veg (VEGAS Pro) | Not supported | Proprietary project handling needs a documented interchange path or a separately validated parser |

A file extension is not proof that the editor can reproduce the file's content. Importers must report what was imported, what was skipped, and why. Never silently flatten an editable project and label it fully supported.

## 5. Prioritised implementation sequence

### 0.7.x — safer sequence editing

Delivered in this branch:
- move queued clips up/down;
- duplicate an individual sequence entry;
- remove an entry without deleting its source file;
- unit-tested immutable ordering operations;
- export consumes the edited order; .sowzproj already serializes that order.

### 0.8 — per-clip edits

- source duration probing off the UI thread;
- per-clip IN/OUT trim and split;
- clip relinking when a persisted URI is no longer accessible;
- clip-level effects and volume;
- undo/redo command history.

### 0.9 — actual multi-track timeline

- multiple video/overlay/audio tracks with overlapping timing;
- timeline zoom, snapping, playhead, track mute/lock/visibility;
- CompositionPlayer preview and Media3 Composition export;
- fixture tests proving the preview and export use the same composition.

### 1.x — motion graphics

- property keyframe lanes for position, scale, rotation, opacity and effect parameters;
- Bezier/easing graph editor;
- shape/text layers, masks and blend modes;
- reusable native effect stack and preset format;
- Lottie import/export as a portable interchange format, with a report of unsupported features.

### Long-term — interoperability, not false promises

- AEPX subset importer first; .aep binary parsing stays inspection-only until independently verified.
- FFX mappings only where a matching native SOWZ effect exists and reference output tests pass.
- Evaluate an interchange workflow (for example, render a supported intermediate from desktop After Effects) rather than pretending desktop AE plugins are Android libraries.
- Evaluate VEG/MOGRT/PSD/SVG separately, with a fixture corpus and a precise feature matrix.

## 6. Acceptance criteria for this change

- The queue shows every additional clip and its current export order.
- Up/down controls change the in-memory list consumed by beginExport.
- Duplicate creates a second sequence entry for the same source URI, subject to the 20-entry cap.
- Remove deletes only the sequence entry; it never deletes the original media.
- Saving and reopening .sowzproj preserves the exact queue order and intentional duplicate entries.
- Pure clip-order operations have unit tests and CI runs the Android unit-test task before building the debug APK.

## 7. Licence and attribution notes

- EffectCraft: MIT OR Apache-2.0 for project code, with separate notices/attribution for bundled assets; ArtCraft trademarks are not part of the open-source licence.
- EffectCraft's documented clean-room policy forbids copying GPL/LGPL/AGPL source and Adobe proprietary material. This patch does not copy any EffectCraft code.
- AndroidX Media3 uses its own published licence and dependency notices; keep dependency review in the release process.
- The current SOWZ repository tree has no root LICENSE file. Before inviting outside contributions or copying any third-party source, choose and publish an explicit SOWZ licence and retain all required third-party notices.

## Sources

- NovaVision Hub — Nova Editor: https://www.novavisionhub.com/novaeditor
- EffectCraft official repository: https://github.com/storytold/effectcraft
- EffectCraft architecture: https://github.com/storytold/effectcraft/blob/main/docs/architecture.md
- EffectCraft contributing and clean-room rules: https://github.com/storytold/effectcraft/blob/main/CONTRIBUTING.md
- AndroidX Media3 Composition guide: https://developer.android.com/media/media3/transformer/composition
- Existing SOWZ source and roadmap: https://github.com/pubgcanavar/SOWZ-AE-Studio


## 8. Implemented background audio sequence in SOWZ 1.0.0

The Android editor now exposes one separate background audio source, a linear gain control, and an MP4 export path that builds an audio-only Media3 sequence alongside the video sequence. The audio sequence loops to composition length. The source URI and gain are saved as optional .sowzproj fields, so earlier project files remain readable.

The Media3 Transformer start(Composition, path) contract requires every item that contains audio to output 16-bit PCM with the same channel count. SOWZ applies ToInt16PcmAudioProcessor and ChannelMixingAudioProcessor with 1–6 input channels downmixed to stereo for every audio-bearing item; a GainProcessor applies the selected gain to the background track. Inputs with more than six audio channels may need to be downmixed before importing.

The live editor preview still uses the source video in ExoPlayer and does not yet mix the background track. The separate track is included only in composition export in this milestone. Media3 documents composition preview through CompositionPlayer, but it is still an experimental/preview-level API for this use case. Sequence crossfades are not supported by the current Transformer Composition API.

Official references:
- Media3 Composition guide: https://developer.android.com/media/media3/transformer/composition
- Transformer audio format requirements: https://developer.android.com/reference/androidx/media3/transformer/Transformer
- Channel mixing matrix: https://developer.android.com/reference/androidx/media3/common/audio/ChannelMixingMatrix
- Channel mixing processor: https://developer.android.com/reference/androidx/media3/common/audio/ChannelMixingAudioProcessor
- Gain processor: https://developer.android.com/reference/androidx/media3/common/audio/GainProcessor
- Default gain provider: https://developer.android.com/reference/androidx/media3/common/audio/DefaultGainProvider


## 9. Source-audio mix control in SOWZ 1.1.0

The editor now applies a separately saved source-video audio gain to each video sequence item, so users can lower or mute original clip audio while keeping the separate background music track. A gain of zero mutes the source audio; one is full gain. The same Media3 PCM16/stereo conversion chain is applied before mixing.

This remains a project-level source-audio control, not yet a per-clip volume envelope or a complete multi-track audio mixer. The live viewer also does not yet reflect the export mix.


## 10. Native HSL colour grading in SOWZ 1.2.0

The editor now adds hue rotation (-180°..180°), saturation (-100..100) and lightness (-100..100) controls through AndroidX Media3 HslAdjustment. These settings feed the same buildVideoEffects() list used by ExoPlayer preview and each Transformer export item. Values are stored in optional .sowzproj JSON properties; projects created before this version default to neutral HSL values when opened.

This is a native SOWZ colour effect, not an After Effects plug-in and not a claim of exact colour-management parity with Adobe applications. Visual output depends on the Media3/GPU colour pipeline and should be checked on physical devices and reference frames.

Official API references:
- HslAdjustment: https://developer.android.com/reference/androidx/media3/effect/HslAdjustment
- HslAdjustment.Builder: https://developer.android.com/reference/androidx/media3/effect/HslAdjustment.Builder
- Media3 Effect APIs: https://developer.android.com/reference/androidx/media3/common/Effect
