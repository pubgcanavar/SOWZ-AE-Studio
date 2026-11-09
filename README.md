# SOWZ AE Studio

SOWZ AE Studio is a native Android editing app. The launcher opens the SOWZ workspace, not a website or a desktop-sized embedded editor.

## Current native workflow — 1.5.0

- Home, Studio, FFX Presets and Settings are separate mobile sections.
- Video import, local preview, source IN/OUT trim and a sequential clip queue.
- Add clips, reorder them, duplicate/remove entries, and undo/redo queue changes.
- Export the edited sequence to MP4.
- Original-video audio gain plus a separate background music sequence for export.
- Native colour controls: brightness, contrast, HSL hue/saturation/lightness, Gaussian blur and quarter-turn rotation.
- Text overlay and standard 3D .cube LUT import.
- Save and reopen editable .sowzproj settings and source URI references.
- Local .ffx preset library: import and preserve original files, export them unchanged, delete them from the app library, and apply the currently supported static parameter subset from the editor.

## FFX support

The app currently applies only non-animated ADBE Brightness & Contrast values when those parameters can be decoded from an FFX preset. Other effects can remain in the saved original preset, but are not rendered by SOWZ unless a native mapping is implemented and tested. Animation, expressions, masks and third-party plug-ins are not executed.

This means FFX import is not universal plug-in compatibility. The UI distinguishes a stored preset from a preset whose values were actually applied.

## What this app does not do

- It does not open or edit .aep files as working After Effects projects. The old AEP structure-report screen has been removed from the main app workflow.
- It does not run desktop .aex plug-ins on Android.
- It does not bundle the EffectCraft web app. The previous WebView screen was removed so it cannot overflow the phone layout or appear as a separate site inside SOWZ.
- It is not yet a full overlapping multi-track motion-graphics compositor. The clip sequence is sequential; background audio is included in export, while the live preview is still video-source preview.
- It does not claim full Nova Editor or After Effects feature parity.

## Build

GitHub Actions runs unit tests, builds the native debug APK and uploads the artifact named SOWZ-AE-Studio-native-debug-apk.

## Privacy

Selected source media stays on the device and is referenced by URI in .sowzproj. Imported FFX presets are copied into the app's private local storage. Export files are written to the location selected by the user.