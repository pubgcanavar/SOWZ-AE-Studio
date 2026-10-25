# SOWZ AE Studio — editor development roadmap

Updated: 2026-10-25

This roadmap uses only the public-facing Frameify Studio feature list and licensed open-source format research. It does not rely on leaked credentials, private account content, hidden user data, bypasses, or non-public source. Similar editor features are implemented independently.

## Milestone 0 — real on-device editing (0.4.0)

- Pick a main video through Android's document picker and preview it with Media3/ExoPlayer.
- Mark IN/OUT points for the main clip.
- Add up to 20 extra videos to a sequential cut; export them after the trimmed main clip.
- Apply brightness, contrast, quarter-turn rotation and a styled text/watermark overlay to the preview and rendered output.
- Save and reopen a `.sowzproj` JSON project with source URI references, main-clip trim, effect parameters, overlay and extra-clip order.
- Provide cancel/error handling for export. Keep original source media local.
- Include a tested, deterministic timeline/keyframe data model.

This proves a genuine source → edit settings → rendered MP4 pipeline. The ordered clip queue is not a full multi-track editor, and only the main clip has trim handles.

## Milestone 1 — editable project format and clip controls

- Add robust project schema migrations, source URI relink, permission recovery, and pre-export checks for unavailable media.
- Give each queued clip its own trim range, duration/metadata and reorder/remove controls.
- Add image and audio assets only after a render path and track behavior are tested.
- Keep projects portable where possible by supporting user-selected media folders or packaging a project with assets; don't silently duplicate very large video files.

Acceptance tests: create/edit/save/reopen a project; reorder/remove/split clips without losing source-time continuity; no UI action silently discards media references.

## Milestone 2 — real multi-track timeline and composite preview

- Connect the tested `TimelineProject` model to a timeline UI with clip blocks, a playhead, zoom, snapping, selection, track ordering, visibility and lock state.
- Add independent video/image, audio, text and shape tracks with explicit z-order and overlapping timing.
- Render the same composition model in preview and export. Do not display a fake composite preview that cannot match the exported file.
- Add undo/redo with command-based history.
- Keep time calculations in integer milliseconds/frame indexes to prevent floating-point drift.

Acceptance tests: overlapping layer timing, visible order, audio selection and project restoration match reference results frame by frame.

## Milestone 3 — transform animation and canvas settings

- Add canvas ratios: 9:16, 16:9, 1:1, 3:4, 4:3, 4:5, 3:2, 21:9 and custom.
- Add position, scale, rotation and opacity properties with editable linear/hold/eased keyframes.
- Show property-specific keyframe lanes and previous/next keyframe navigation.
- Support text editing (font, size, colour, alignment, outline/background) and shape layers after preview/export parity is implemented.

Acceptance tests: exact property values at keyframe times; save/reopen preserves timing; preview matches output at frame boundaries.

## Milestone 4 — native effect stack

- Implement Android-native effects such as brightness, contrast, saturation, hue, blur/sharpen and vignette where Media3/OpenGL supports them.
- Share identical effect definitions between preview and export.
- Store each effect's parameters, order, enabled state and supported range in the project.
- Test effects against reference frames and verify output on physical devices.

These are SOWZ-native effects, not desktop plug-in binaries. An effect name alone does not imply compatibility with a commercial After Effects plug-in.

## Milestone 5 — FFX and After Effects interoperability

- Move `.ffx` parsing from generic chunk inventory toward typed effect blocks, parameter names/values, keyframes and plug-in dependency reports.
- Research format differences and validate using test fixtures the developer is entitled to use.
- Build an explicit mapping table from supported FFX parameters to SOWZ-native effects. Preserve unknown fields and original bytes.
- Show exact warnings for unsupported expressions, plug-ins and effect parameters. Never claim an unsupported preset was applied.
- Improve AEPX XML extraction first; investigate binary AEP read/write separately.
- Never execute scripts or expressions embedded in imported projects.

Acceptance tests: every claimed FFX mapping uses a known fixture; unsupported blocks are listed; exported output is compared with a trusted reference. No write-back to original AEP unless the writer is independently validated.

## Milestone 6 — expanded output pipeline

- Multi-track rendering, audio mixing, transitions where supported, canvas/aspect-ratio control, frame rate, bitrate and quality settings.
- Export progress, cancellation, recoverable failure messages and low-storage checks.
- Render at actual composition resolution; preview quality is a separate performance setting.
- Test codecs/resolutions and large files on real devices; set memory limits and useful warnings.

Media3 Transformer supports trimming, scaling/rotation, effects and compositions for sequential/multi-asset workflows; crossfades still need specific handling. See the [official editing guide](https://developer.android.com/media/implement/editing-app) and [composition guide](https://developer.android.com/media/media3/transformer/composition).

## Milestone 7 — advanced/AI tools (separate feasibility checks)

- Speed ramps and slow motion; optical-flow interpolation is a separate algorithm, not just a speed setting.
- Stabilisation, face/text tracking, background removal and upscaling only after a real on-device model/library or transparent opt-in service is selected and validated.
- Record supported inputs, local/cloud processing, device requirements, licence and performance before exposing a tool as ready.

## Engineering rules

1. Every visible control performs a real action or is clearly marked as planned.
2. CI compiles and tests after each milestone; an APK is called ready only after its exact artifact is built successfully.
3. Keep media local by default and collect no telemetry without explicit design and privacy review.
4. Respect third-party licences and trademarks; reuse only code whose licence permits it, preserving required notices.
5. Never use leaked private data, credentials, hidden endpoints or access-control bypasses as development inputs.
