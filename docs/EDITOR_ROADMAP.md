# SOWZ AE Studio — editor development roadmap

Updated: 2026-11-09

This roadmap uses public product feature lists, official AndroidX Media3 APIs, and public/licensed file-format research. It does not use leaked private data, credentials, hidden endpoints or access-control bypasses.

## Delivered foundation — 1.3.0 (editable clip queue, audio mixer, HSL, blur and speed)

- Local source video import and main-clip playback preview.
- IN/OUT trim handles for the main clip.
- Ordered final cut with up to 20 additional video clips and MP4 composition export.
- Per-clip queue controls: move clips up/down, duplicate an entry, or remove an entry. Project serialization and MP4 export use the same list order.
- Independent IN/OUT source trim for each additional clip, read from source duration off the UI thread and applied to the corresponding Media3 ClippingConfiguration.
- Backward-compatible `.sowzproj` parsing: legacy string URI entries remain readable; new object entries store each clip's IN/OUT points.
- Bounded 50-snapshot Undo/Redo for queue edits (add, move, duplicate, remove and per-clip trim); a new edit clears the redo branch, and loading a project resets history.
- One independent background-audio sequence, imported through Android's document picker, looped to composition length and mixed into MP4 export with an adjustable linear gain.
- The project saves/restores the background audio URI and gain while remaining compatible with older `.sowzproj` files that omit these optional fields.
- Audio-bearing video and audio items use the same Media3 PCM16/stereo normalization chain before composition mixing.
- Original video audio has an independent 0–100% export gain, including a true mute setting at 0%; the setting is saved and restored with the project.
- Real brightness, contrast, hue, saturation and lightness grading, quarter-turn rotation and static text/watermark overlay effects on preview and export. HSL uses the native Media3 HslAdjustment GPU effect.
- Gaussian Blur with 0–40 sigma using Media3's native GPU effect in preview and export.
- Main-clip speed from 0.25x–4x using Media3 SpeedProvider in preview and export; value is persisted in the project.
- Per-added-clip speed from 0.25x–4x is stored alongside each clip's trim range, survives reorder/duplicate/undo, and is applied to that clip during export.
- Standard 3D `.cube` CC LUT import, parsing and application via Media3 `SingleColorLut`.
- Save/reopen `.sowzproj` with source URI references, edit settings, text, LUT and clip queue.
- Tested timeline/keyframe data model (time conversion, layer timing, split/visibility and float interpolation).
- After Effects project/preset inspector; `.ffx` effect-name and static-number decoding for recognized structures.
- Limited `.ffx` translation: static `ADBE Brightness & Contrast` values are applied to the native brightness/contrast controls; animated or third-party effects remain unsupported.

## Next milestone — multi-track timeline and audio preview

- Add background audio to the in-app CompositionPlayer preview when its API is stable enough for the supported device range; until then, preview/export parity is explicitly incomplete.
- Add independent audio clips, per-clip IN/OUT, gain automation and audio waveform display.
- Add multiple independently arranged audio clips, per-clip IN/OUT, gain automation and audio waveform display.

## Next milestone — independent clip editing

- Add a selected-clip inspector, frame-step controls and a zoomable time ruler.
- Persist and restore document permissions; detect missing/revoked source URIs and offer relinking before export.
- Add frame-accurate split, duplicate-at-playhead and clip snapping.
- Show export progress, cancel safely, check free storage and clean temporary files after failure.

Acceptance: every queued clip has its own editable source range; save/reopen preserves those ranges and export timing matches the edited sequence.

## Multi-track timeline and composite preview

- Connect the tested `TimelineProject` model to a real timeline UI with clip blocks, playhead, zoom, snapping, selection, z-order, visibility and locks.
- Add independent video/image, audio, text and shape layers with overlapping timing (the single background-audio export sequence is the first audio track, not a complete audio timeline).
- Render the same composition model in preview and export; no fake layer preview that differs from the actual render.
- Add undo/redo using command-based history.
- Keep timeline math in integer milliseconds/frame indexes to prevent drift.

Acceptance: overlapping layer timing, z-order, audio selection and project restoration match reference results frame by frame.

## Transform animation and canvas settings

- Canvas ratios: 9:16, 16:9, 1:1, 3:4, 4:3, 4:5, 3:2, 21:9 and custom.
- Position, scale, rotation and opacity with editable linear/hold/eased keyframes.
- Property-specific keyframe lanes and previous/next keyframe navigation.
- Text styling (font, size, colour, alignment, outline/background) and shape layers after preview/export parity exists.

## Native effect stack

- Add GPU Gaussian blur/sharpen, vignette, channel controls and a reorderable effect stack with per-effect enable/disable.
- Same effect definitions for preview/export; persist effect order, parameters and enabled state.
- Fixture-based image comparisons and physical-device export testing.

These are SOWZ-native effects, not desktop After Effects plug-in binaries.

## FFX / After Effects interoperability

- Expand typed FFX effect parsing and supported static/animated property handling, then map each individually verified native effect.
- Use valid test presets that the developer is entitled to use; compare decoded results with After Effects references.
- Build an explicit mapping from supported FFX properties to SOWZ-native effects. Preserve original bytes and warn on unknown blocks.
- Never claim an unsupported preset was applied. Never execute imported scripts/expressions.
- Improve AEPX XML inspection first; evaluate binary AEP read/write separately.

Acceptance: every supported FFX mapping is fixture-tested and its output compared with a trusted reference. Unknown expressions, plug-in effects or parameter types remain marked unsupported.

## Export and advanced tools

- Full multi-layer rendering, audio mixing, transitions, aspect ratio, frame rate, bitrate and quality controls.
- Slow motion/speed curves; optical-flow interpolation is a distinct algorithm.
- Stabilisation, tracking, background removal and upscaling only after selecting a real on-device model/library or transparent opt-in service and validating device requirements, licence and performance.
- No placeholder-only controls.

## Engineering rules

1. Every visible control performs a real action or is clearly marked as planned.
2. CI compiles and tests every milestone; call an APK ready only after its exact artifact builds successfully.
3. Keep source media local by default and do not collect telemetry without explicit design and privacy review.
4. Respect third-party licences and trademarks; reuse code only where the licence permits it and preserve required notices.

## 1.8.0 — Composition workspace, text layers and AEPX inspection

Implemented on feature/android-effectcraft-wasm:

- Native multi-track timeline with independent V/I/A/T/S lanes, time ruler, playhead, keyframe diamonds, touch selection and drag-to-move for supported video/text layers.
- Native composition model for video, audio and editable text layers with time ranges, z-order, enabled state, and transform properties.
- Per-layer transform keyframes for opacity, position X/Y, scale X/Y and rotation; linear and hold interpolation; previous/next keyframe navigation; transform evaluation from presentation timestamps.
- Versioned bounded JSON persistence for the complete composition graph, text content/style and animated values inside .sowzproj.
- Media3 CompositionPlayer preview for overlapping video/text layers and a background audio sequence; Transformer export uses independent sequences for overlapping video/text overlays.
- Text layers render as transparent PNG sources with cached output. Cache identity includes text, colour, point size and composition dimensions.
- Layer visibility toggle drives preview and export; the primary layer's own keyframes also activate the composition preview path.
- Binary .aep inspection continues to use the pinned Rust parser and maps only supported static effect properties.
- .aepx gets a bounded SAX structural scan with DTD/external-entity protections. It reports likely compositions, layers, footage and effect nodes but does not claim complete project reconstruction.

### Compatibility boundaries

- The composition renderer currently supports video and text visual tracks plus one background audio sequence. Image and shape track rendering, audio clip editing/automation, waveform display, effect-stack reorder and advanced easing are not complete.
- AEPX scanning remains structural; bdata blocks and full property reconstruction are not converted into editable SOWZ layers.
- Binary AEP scanning is not full-fidelity After Effects rendering. Unknown/animated/third-party AEP or FFX effects remain reported as unsupported unless a tested native mapping exists.
- Desktop Adobe .aex plug-ins cannot be directly installed as Android plug-ins. SOWZ Shake is an independent native implementation, not a Sapphire binary or exact behaviour clone.
- CI unit tests and APK build/packaging pass; physical Galaxy A25 preview, playback, UI layout and export performance still require device validation.