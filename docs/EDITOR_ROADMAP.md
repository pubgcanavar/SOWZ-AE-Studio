# SOWZ AE Studio — editor development roadmap

Updated: 2026-10-25

This plan uses only the public-facing Frameify Studio feature list as product research, plus licensed open-source format research. It does not rely on leaked credentials, private accounts, hidden user data, bypasses, or non-public source. Similar editor concepts are implemented independently.

## Milestone 0 — real single-clip video edit (added in 0.3.0)

- Pick a local video with Android's document picker.
- Preview with Media3/ExoPlayer.
- Set IN and OUT points using a timeline scrubber.
- Export the selected clip range into a user-selected MP4 file with Media3 Transformer.
- Model timeline layers, source ranges, frame/time conversion and float keyframes; unit tests cover the deterministic core.

This is deliberately narrow: it proves a genuine source → edit choice → rendered media file pipeline on device.

## Milestone 1 — project files and multi-track timeline

- Persist an editable project format (versioned JSON inside a `.sowzproj` package) without altering source media.
- Add multiple video, image, audio, text and shape tracks.
- Add layer visibility/lock, ordering, trim, split, duplicate, delete, timeline zoom and playhead snapping.
- Reopen the app and recover saved projects and their media references.
- Validate URI permissions and provide a relink flow when a source disappears.

Acceptance tests: create/edit/save/reopen a project; split a clip without losing source-time continuity; no UI action silently drops a layer.

## Milestone 2 — composition preview and keyframes

- Add transform controls (position, scale, rotation, opacity) with editable keyframes.
- Connect the timeline model to preview rendering, and show a visible keyframe lane/property inspector.
- Add canvas ratios: 9:16, 16:9, 1:1, 4:3, 4:5, 3:2, 21:9 and custom.
- Add undo/redo with command-based history.
- Keep time calculations in integer milliseconds/frame indexes to prevent floating-point drift.

Acceptance tests: inspect exact transform values at keyframes; save/reopen preserves timing and property values; preview matches the model at frame boundaries.

## Milestone 3 — original effect stack

- Implement mobile-compatible effects with deterministic parameters: brightness/contrast, saturation, hue, blur/sharpen, vignette and simple color adjustments.
- Use Media3/OpenGL effects for preview/export where supported, sharing the same effect definitions.
- Store each effect's parameters, order and enabled state in the project.
- Test effects against reference frames and verify output on physical devices.

These are SOWZ-native effects, not desktop plugin binaries. Effect names alone do not imply compatibility with an After Effects plug-in.

## Milestone 4 — FFX and After Effects interoperability

- Improve `.ffx` parsing from generic chunk inventory to typed effect blocks, parameter names/values, keyframes and plugin dependency reports.
- Research format differences and test fixtures from files users are entitled to use.
- Build an explicit mapping table from supported FFX properties to native SOWZ effects.
- For unsupported expressions/effects/plugins, show exact warnings and preserve the original file bytes. Never silently claim an unknown preset applied successfully.
- Improve AEPX XML extraction first; investigate binary AEP read support separately.
- Never execute scripts or expressions embedded in imported projects.

Acceptance tests: each claimed FFX conversion uses a known fixture, lists unsupported blocks, and has an exported output compared with a trusted reference. No write-back to original AEP unless the format writer is validated independently.

## Milestone 5 — expanded export pipeline

- Multi-layer rendering, audio mixing, transition boundaries, output aspect ratio, frame rate, bitrate and quality settings.
- Progress, cancel, recoverable failure messages and low-storage checks.
- Render at actual composition resolution; preview quality is a separate performance setting.
- Test varied codecs/resolutions and large files on real devices; provide memory limits and warnings.

## Milestone 6 — advanced tools (separate feasibility checks)

- Speed curves and slow motion; frame interpolation is a different technical feature and cannot be promised by a simple playback-rate change.
- Stabilisation, face/text tracking, background removal and upscaling only after choosing an actual on-device model/library or a transparent opt-in service. Do not add buttons that imply an AI tool exists when it is only a placeholder.
- Report supported input types, local/cloud processing, device requirements, license and performance before marking each tool complete.

## Engineering rules

1. Every feature in the UI must have a working action or be clearly labelled as planned.
2. CI must compile and test after each milestone; no "APK done" claim until the artifact is built successfully.
3. Keep source assets local by default and do not collect telemetry without an explicit design and privacy review.
4. Respect third-party licenses and trademarks. Reuse only code whose license permits it and preserve required notices.
5. Never use leaked private data, credentials, hidden endpoints, or access-control bypasses as development inputs.
