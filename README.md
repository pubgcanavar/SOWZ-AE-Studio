# SOWZ AE Studio

A native Android editing project with an honest compatibility boundary: a real single-clip video edit/export path, a tested timeline/keyframe model, and separate After Effects project/preset inspection tools.

## Current build (0.3.0)

- **Real video workflow:** choose a local video through Android's document picker, preview with Media3/ExoPlayer, set IN/OUT trim points and export the selected range as an MP4 through Media3 Transformer.
- **Timeline foundation:** deterministic millisecond-based composition model, layer timing/source offsets, layer visibility, split/move/remove operations, frame/time conversion, and linearly/hold-interpolated animated float properties. Unit tests cover these operations.
- Import and inspect `.aep`, `.aepx`, `.aet` and ZIP project packages.
- Import, inspect, keep, delete and export original `.ffx` preset files without changing their bytes.
- Dark graphite/orange UI with the SOWZ fox identity.

## Compatibility — what is and isn't working yet

This is **not a complete After Effects replacement yet**.

- Current export is a single video clip trimmed to an IN/OUT range. It does not yet composite multiple timeline layers, render keyframes, or apply the timeline model to exported frames.
- The timeline model is an engine foundation; it is not yet a fully connected multi-track UI.
- `.ffx` is currently inspected/stored read-only by the app. The app does not yet translate all FFX parameters, expressions, plugin effects or animated values into native Android effects.
- Binary `.aep` inspection is structural and partial. `.aepx`/`.aet` use best-effort XML inspection. The app does not evaluate expressions or write edits back into original AEP projects.
- After Effects `.aex` plug-ins are desktop binaries and cannot simply be installed into an Android app. Effects must be implemented or ported to an Android-compatible renderer, and compatibility must be tested per effect.

A file being accepted is not proof that it can be rendered correctly. The UI and docs should continue to distinguish implemented features from future work.

## Public feature research

The public-facing Frameify Studio page was reviewed for common editor workflows: project import/export, aspect-ratio presets, media preview, timeline/layers, keyframes, inspector tabs, effect panel and quality/export settings. These are product-level references only; this repository does not copy private data, account content, secrets, or proprietary source code. See [the development roadmap](docs/EDITOR_ROADMAP.md).

An open-source FFX investigation candidate is [FFX Compatibility Tool](https://github.com/marbou92/FFX-Compatibility-Tool), published under MIT. Any code reuse must preserve the license notices and be reviewed for Android portability; the current app does not claim to include that tool's parser.

## Build on GitHub

Open Actions and run **Build SOWZ AE Studio APK**. CI runs unit tests, builds a debug APK and uploads the artifact `SOWZ-AE-Studio-debug-apk` as a ZIP containing the APK.

## Privacy

Imported videos are read via Android's document picker and processed locally for the current single-clip export flow. The editor does not intentionally upload source videos. The existing AEP/FFX inspector also works locally. Export files are written to the user-selected destination.
