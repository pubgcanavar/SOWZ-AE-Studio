# SOWZ AE Studio

A native Android motion/video-editing project with a real local media pipeline and a deliberately explicit After Effects compatibility boundary.

## Current build (0.4.0)

- **Import and preview:** choose local video files with Android's document picker and preview the main clip using Media3/ExoPlayer.
- **Trim and sequence:** choose IN/OUT points for the main clip, then add up to 20 additional video clips to a sequential cut. Additional clips are used at full duration and exported after the main clip.
- **Real rendered effects:** brightness, contrast, 90° rotation steps, and a styled text/watermark overlay are passed to Media3 both for preview of the main clip and for MP4 export.
- **Project save/reopen:** save source URI references, trim points, effect settings, overlay text, and the ordered extra-clip list in a versioned `.sowzproj` JSON project. Source files themselves are not copied into the project.
- **Timeline model:** tested millisecond-based layer timing, source offsets, frame/time conversion, visibility, splitting and linearly/hold-interpolated float keyframes.
- **After Effects inspection:** import and inspect `.aep`, `.aepx`, `.aet` and ZIP project packages; import, inspect, store and export original `.ffx` files unchanged.
- Dark graphite/orange interface with the SOWZ fox identity.

## What is not implemented yet

This is **not a complete After Effects or Frameify replacement**.

- The export can join clips sequentially, but the combined sequence is not yet shown in one integrated multi-track preview. Only the main clip is live-previewed.
- Additional clips currently use their full duration; trim handles apply to the main clip. The UI queue is a simple ordered cut, not a multi-track layer timeline. There are no crossfades yet.
- Project files store URI references rather than copying media into a portable project package. Keep the original source videos available and readable on the device.
- `.ffx` is still inspected/stored read-only. The app does not yet decode and apply every preset parameter, animated property, expression or plug-in effect.
- Binary `.aep` inspection is structural and partial. `.aepx`/`.aet` use best-effort XML inspection. The app does not evaluate expressions or write edits back to original AEP projects.
- After Effects `.aex` plug-ins are desktop binaries and cannot simply be installed in an Android app. Individual effects must be implemented or ported to an Android-compatible renderer and validated separately.
- AI upscaling, background removal, face/text tracking, stabilisation, optical-flow interpolation and advanced speed ramps are not marked as available until real models/algorithms are integrated and tested.

A file being accepted is not proof that it can be rendered correctly. The UI and documentation distinguish implemented behavior from future work.

## Public feature research

The public-facing [Frameify Studio page](https://frameify.wasmer.app/) was reviewed for product-level workflows such as project import/export, aspect-ratio choices, preview, timeline/layers, inspector panels, keyframes, text, effects and export quality. We use those as independent product-design references, not as permission to copy private account data or proprietary source code.

The open-source [FFX Compatibility Tool](https://github.com/marbou92/FFX-Compatibility-Tool) is published under the MIT license and is a research candidate for preset metadata/format analysis. Its code has not been copied into the Android app; any future reuse needs a license and Android-portability review.

## Build and verification

Open [GitHub Actions](https://github.com/pubgcanavar/SOWZ-AE-Studio/actions) and run **Build SOWZ AE Studio APK**. CI runs unit tests, builds a debug APK, and uploads the artifact `SOWZ-AE-Studio-debug-apk` as a ZIP containing the APK.

## Privacy

Media import uses Android's document picker. The current editing/export pipeline runs locally and does not intentionally upload source video. The app writes exports only to the location chosen by the user. Imported media URIs remain references, so a project may stop reopening if source permissions are revoked or a file is moved/deleted.
