# SOWZ AE Studio

A native Android project inspector for Adobe After Effects project files.

**Important compatibility note:** This app is not Adobe After Effects and does not render every composition, effect, expression, or third-party plugin. The binary AEP format is private and version-sensitive. This first build inspects the RIFX/chunk structure and readable strings; AEPX XML files receive heuristic composition/layer detection. Unsupported features are reported honestly.

## Build
Open Actions and run **Build SOWZ AE Studio APK**, or use the workflow artifact created after each push to main. It builds a debug APK and runs unit tests.

## Features
- File picker for AEP, AEPX, AET and ZIP project packages
- Local-only file processing; no broad storage permission
- Safe XML inspection with external entities disabled
- Bounded binary RIFX chunk inspection
- ZIP project scan and likely-media inventory
- Dark graphite/orange interface, recent projects, report export
- GitHub Actions APK build pipeline

## Roadmap
Validate parser behavior against authorized project files from multiple After Effects versions; improve the typed binary project graph; then add timeline and preview support for a clearly defined subset. Never treat parsing as proof of exact rendering.
