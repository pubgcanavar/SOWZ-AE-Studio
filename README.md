# SOWZ AE Studio

A native Android video-editing foundation. This project now performs real local video processing; it is still not a complete After Effects or Nova Editor replacement.

## Current build — 0.5.0

- **Import and preview:** pick a local video using Android's document picker and preview the main clip with Media3/ExoPlayer.
- **Trim and sequential cut:** set IN/OUT points for the main clip, add up to 20 additional video clips, then render a sequential MP4 composition. Additional clips play at full duration after the trimmed main clip.
- **Applied colour controls:** brightness and contrast adjustments; quarter-turn rotation (0°, 90°, 180°, 270°); text/title/watermark overlay.
- **CC / 3D LUT:** import a standard `.cube` 3D colour lookup table. The LUT is applied to the main-clip preview and rendered output using Media3 `SingleColorLut`.
- **Save/reopen projects:** the versioned `.sowzproj` file stores trim points, effects, text, LUT and source media URI references.
- **After Effects file inspection:** inspect `.aep`, `.aepx`, `.aet` and ZIP project packages; import/inspect/store/export original `.ffx` bytes without modifying them.
- Dark graphite/orange UI with the SOWZ fox identity.

## Important compatibility limits

This is an incremental working editor, **not a full After Effects replacement**.

- The sequential clip queue is not yet a full multi-track timeline. Only the main clip has trim handles and live preview; added clips play at full duration. There are no overlapping visual tracks or transitions yet.
- The timeline/keyframe model has unit tests, but animated properties are not yet connected to a fully composited renderer/export timeline.
- **`.cube` CC LUT support is real but scoped:** the current importer supports standalone 3D LUTs with sizes 2–64 and the standard `DOMAIN_MIN 0 0 0` / `DOMAIN_MAX 1 1 1` domain. It rejects 1D shaper tables and nonstandard domains rather than silently producing an incorrect result. Colour results may differ from After Effects because the host colour pipeline and LUT conventions are not universally identical.
- **`.ffx` presets are not applied yet.** They are currently imported and inspected/stored read-only. After Effects presets can include specific effects, parameters, keyframes, expressions and plug-in dependencies; a native SOWZ translator still needs to decode each supported part and map it to an actual Android effect. The app does not claim arbitrary FFX compatibility.
- Binary `.aep` inspection is structural and partial; `.aepx`/`.aet` inspection is best-effort XML. Original AEP projects are not edited or rendered.
- After Effects `.aex` plug-ins are desktop binaries and cannot simply be installed into Android. Effects need Android-compatible implementations and individual compatibility tests.
- AI upscaling, background removal, tracking, stabilisation, optical-flow interpolation and advanced speed curves are not implemented merely because they appear in the roadmap.

A preset/file being accepted is not proof that its effect can be reproduced correctly. The UI and documentation distinguish implemented functions from planned work.

## Research sources

The public-facing [Frameify Studio page](https://frameify.wasmer.app/) was reviewed for product-level workflows such as canvas ratios, preview, timeline/layers, keyframes, text, effects, After Effects preset access and export settings. These features are independent design targets; this repository does not use private accounts, leaked credentials or non-public user data.

For the CC LUT implementation, the app uses AndroidX Media3's public `SingleColorLut` API. See the [official Media3 editing guide](https://developer.android.com/media/implement/editing-app) and [SingleColorLut reference](https://developer.android.com/reference/androidx/media3/effect/SingleColorLut).

The [FFX Compatibility Tool](https://github.com/marbou92/FFX-Compatibility-Tool) is a public MIT-licensed research candidate for understanding FFX metadata. Its desktop code has not been copied into this Android app.

## Build

Open [GitHub Actions](https://github.com/pubgcanavar/SOWZ-AE-Studio/actions) and run **Build SOWZ AE Studio APK**. CI runs unit tests, builds a debug APK and uploads the artifact `SOWZ-AE-Studio-debug-apk` as a ZIP containing the APK.

## Privacy

The editor processes selected videos on-device and does not intentionally upload source media. Export files are written to the location selected by the user. Project files store URI references instead of copying the source media, so original videos must remain accessible on the device for reopening.
