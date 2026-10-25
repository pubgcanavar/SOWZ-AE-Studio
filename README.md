# SOWZ AE Studio

Native Android project and preset inspector for Adobe After Effects project files.

## Current build

- Import .aep, .aepx, .aet and .zip project packages.
- Inspect .ffx presets in a dedicated local preset library.
- Preserve imported FFX bytes unchanged; inspect the RIFX/FaFX/header layout, raw version marker, chunk inventory and SHA-256.
- Export original FFX files and remove the private app copy.
- Secure XML parser settings; bounded input sizes, ZIP scanning, binary chunk count and recursion depth.
- Dark graphite/orange interface with a fox mark, recent projects, inspector, preset library and report export.
- GitHub Actions runs unit tests and builds a downloadable debug APK.

## Compatibility, honestly

This application is not Adobe After Effects. The proprietary binary AEP and FFX formats are version-sensitive. Binary AEP support in this build is structural inspection and limited readable-string extraction; FFX support is read-only inspection and local preset-file management. AEPX / AET XML detection is heuristic. This build does not evaluate expressions, render every AE effect, run third-party plugins, apply FFX presets to an AE-compatible renderer, or write edits back into AEP.

An accepted file header is not proof that the entire file can be rendered or applied correctly. See docs/FFX_PRESET_SUPPORT.md and docs/PARSER_LIMITS.md.

## Build on GitHub

Open Actions and run Build SOWZ AE Studio APK. The workflow runs parser unit tests, builds a debug APK, and uploads artifact SOWZ-AE-Studio-debug-apk. Download the artifact ZIP and extract the APK from it.

## Privacy

Project and preset bytes are processed locally by the app. Imported presets are saved to the app's private directory; reports may include file names and readable strings, so review before sharing.
