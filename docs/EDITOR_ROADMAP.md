# SOWZ AE Studio — editor development roadmap

Updated: 2026-10-25

This roadmap uses public product feature lists, official AndroidX Media3 APIs, and public/licensed file-format research. It does not use leaked private data, credentials, hidden endpoints or access-control bypasses.

## Delivered foundation — 0.6.0

- Local source video import and main-clip playback preview.
- IN/OUT trim handles for the main clip.
- Ordered final cut with up to 20 additional video clips and MP4 composition export.
- Real brightness, contrast, quarter-turn rotation and static text/watermark overlay effects on preview and export.
- Standard 3D `.cube` CC LUT import, parsing and application via Media3 `SingleColorLut`.
- Save/reopen `.sowzproj` with source URI references, edit settings, text, LUT and clip queue.
- Tested timeline/keyframe data model (time conversion, layer timing, split/visibility and float interpolation).
- After Effects project/preset inspector; `.ffx` effect-name and static-number decoding for recognized structures.
- Limited `.ffx` translation: static `ADBE Brightness & Contrast` values are applied to the native brightness/contrast controls; animated or third-party effects remain unsupported.

## Next milestone — independent clip controls

- Add per-clip trim ranges, source duration and metadata, reorder controls, duplicate and delete.
- Persist and restore document permissions; detect missing/revoked source URIs and offer relinking before export.
- Add frame-accurate split and clip snapping; support audio-only assets only when final mix/export is tested.
- Show export progress, cancel safely, check free storage and clean temporary files after failure.

Acceptance: each queued clip can be edited and reordered, reopened from a saved project, and exported in the same order with measured timing.

## Multi-track timeline and composite preview

- Connect the tested `TimelineProject` model to a real timeline UI with clip blocks, playhead, zoom, snapping, selection, z-order, visibility and locks.
- Add independent video/image, audio, text and shape layers with overlapping timing.
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

- Saturation, hue, blur/sharpen, vignette and additional colour controls where Media3/OpenGL supports them.
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
