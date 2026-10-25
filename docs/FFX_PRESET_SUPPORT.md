# FFX preset support

## What this version does

- Accepts .ffx through the Android Storage Access Framework.
- Validates the expected RIFX / FaFX / head signature before import.
- Stores the original preset in the app's private storage directory.
- Shows file size, SHA-256, raw candidate format-version marker, chunk inventory, and a limited set of explicitly labeled readable strings.
- Exports the stored bytes unchanged and can remove the app-local copy.
- Uses size, recursion-depth and chunk-count limits to protect the app from malformed files.

## What it does not do

It does not yet decode every effect, property, plugin payload or animated keyframe; it does not modify FFX version markers; it does not apply a preset to an internal render engine; and it does not convert the preset to native AE effects. Full preset decoding requires validated mapping data from known-good fixtures generated in controlled After Effects versions, plus comparisons against AE's own behavior.

## Test methodology

The synthetic tests verify signature recognition, rejection and hash stability. They do not prove compatibility with every real preset. Please test using FFX files you own or are authorized to inspect. When a preset fails, keep the original unchanged and export the diagnostic report.

## Format research references

- After Effects Internals Guide, FFX preset format: https://ken-eizo.github.io/after-effects-internals-guide/persistence/ffx/
- FFX Converter (community research on RIFX/FaFX headers and version markers): https://github.com/chilleditzaep/ffx-downgrader.
- Adobe After Effects supported file formats: https://helpx.adobe.com/after-effects/desktop/get-started/supported-file-formats/supported-file-formats.html

These references are research material; this repository's parser is independently written and conservative. No third-party preset converter or byte-patching routine has been copied into the app.
