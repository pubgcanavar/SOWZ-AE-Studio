# SOWZ AE Android + EffectCraft integration

## What this branch does

The Android app keeps its native Kotlin video-editing screen and adds a separate EffectCraft editor screen. The workflow checks out EffectCraft at the immutable commit `a41ff5015c6687db15a6f074a47b870b8f09fece`, builds the upstream `effectcraft-web` WebAssembly application and bundles its static output and licence notices into the APK.

The WebView serves bundled assets through AndroidX WebViewAssetLoader on the reserved `appassets.androidplatform.net` HTTPS origin. This avoids a `file://` origin for WebAssembly, workers and IndexedDB. Selected project/media files are passed through the Android document picker. Blob downloads are streamed to a temporary file and then written to a location explicitly selected by the user.

## EffectCraft features exposed by the embedded editor

- EffectCraft project model, timeline, preview and renderer from the pinned upstream source.
- EffectCraft's `.aep/.aepx` importer and import-loss report.
- The pinned upstream effect registry and effect parameter controls.
- Browser storage is configured to IndexedDB; the editor is bundled locally and does not need a separate application server to start.

## Compatibility boundaries

This is not a claim of pixel-identical After Effects compatibility. The upstream importer maps supported fields/effects and reports losses. Unsupported Adobe fields, missing media, missing fonts and unrecognised effects may not be converted. Third-party Adobe `.aex` binaries are not loaded. Proprietary commercial plug-ins are not copied or bundled; an effect with a matching name is not proof that the same implementation or output exists.

The EffectCraft catalogue's 308 effect entries and 2,988 parameters are upstream catalogue figures, not an independently measured count of effects compatible with every AEP. The effect registry is useful for adding native equivalents, but S_Shake, Deep Glow, BCC and VideoCopilot effects still need a per-effect mapping, parameter translation and render comparison.

## Device constraints

The APK includes a WebAssembly editor and is significantly larger than the previous native-only APK. Runtime rendering uses the Android System WebView's WebGL2/WebAssembly implementation where available. Android WebView does not expose every desktop browser capability on every device. Touch ergonomics, GPU limits, large project memory use, and export speed must be checked on physical devices before calling this a stable mobile release.

## Build

GitHub Actions builds the pinned upstream WASM app, copies the upstream licence/notice/attribution files into the app assets, runs Android unit tests, builds the APK and verifies the editor's HTML/WASM/licence files are present in the resulting APK.

## Licence and attribution

EffectCraft is used under its upstream `MIT OR Apache-2.0` licence, at the recipient's option. The workflow includes the upstream MIT and Apache licence texts, NOTICE and ATTRIBUTION files inside the APK under `assets/effectcraft/licenses/`. EffectCraft/ArtCraft names, wordmarks and logos are not used as SOWZ AE branding.
