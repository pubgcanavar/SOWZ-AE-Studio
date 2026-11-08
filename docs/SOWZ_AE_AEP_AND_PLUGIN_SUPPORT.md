# SOWZ AE — After Effects import and plug-in compatibility

## AEP / AEPX project import

The Windows desktop frontend is built on the reviewed EffectCraft engine crates pinned at:

- Repository: https://github.com/storytold/effectcraft
- Commit: `a41ff5015c6687db15a6f074a47b870b8f09fece`
- Import crate: `effectcraft-aep`
- Engine command: `file.importAep`

Use **File → Import → After Effects Project…** and select an `.aep` or `.aepx` file. The importer analyses the project and shows a loss report before applying a conversion that would omit or approximate data. Cancel leaves the current project unchanged. Continue is an explicit decision to accept the report.

The importer maps the supported project tree, folders, compositions, footage references, solids, nulls, adjustment layers, precompositions, text and shape layers, transforms, keyframes, supported expressions, masks, markers, time remapping, track mattes, cameras, lights, blend modes, selected layer styles and a table of built-in effects. It preserves missing-footage references as placeholders and reports missing fonts. Exact coverage depends on the specific field layout and effect parameters found in the input.

The importer is intentionally conservative: unrecognised or unsupported effects, unknown property layouts, unsupported text/shape fields, unsupported layer styles, expressions that cannot be converted safely, unsupported keyframe data and missing dependencies are reported. A successful parse is not a promise of pixel-identical After Effects rendering.

## Third-party Adobe plug-ins

A native Adobe `.aex` binary plug-in is not a portable plug-in format for this application. S_Shake, Deep Glow, BCC/Boris FX, Sapphire, VideoCopilot Twitch, Omino and similar commercial plug-ins are not bundled, copied, loaded or represented as working merely because their names occur in an AEP project.

The current AEP importer converts supported built-in After Effects effects that have a verified mapping in its effect table. Other plug-in instances appear in the conversion report. Their source data remains in the original AEP file; the app must not silently replace them with a different effect.

SOWZ AE can add original effects and compatible open effect modules. EffectCraft's WebAssembly plug-in host is a separate sandboxed plug-in system; it does not load Adobe `.aex` binaries. A future SOWZ effect adapter must document its API, permissions, licence and preview/export parity.

## Nova Editor feature benchmark

Nova Editor's public feature page is used as a feature-level benchmark (timeline ergonomics, path/pen tools, blend modes, motion and glow effects, and 3D workflow). This project does not copy Nova's APK, proprietary code, graphics, branding or closed plug-in integrations.

## Native project format

The current EffectCraft-based desktop editor saves its native project in EffectCraft's project format (`.ecproj` / `.ecprojx`). The earlier SOWZ `.sowzae` shared-core prototype remains separate; automatic round-trip conversion between the two schemas is not yet implemented. Do not rename an `.ecproj` file to `.sowzae` or vice versa.

## Interchange status

- `.aep` / `.aepx`: partial, report-driven import.
- `.mp4`, `.mov`, common audio and image formats: media import is handled by the media engine; codec support depends on the pinned FilmCraft codec stack and the actual file.
- `.ffx`: not currently imported as an After Effects animation preset.
- `.mogrt`: not currently imported as an editable After Effects / Premiere template.
- `.jsx`: not executed; scripts are not auto-run on import.
- Adobe `.aex`: not loaded.
- `.psd`, `.svg`, Lottie and timeline interchange formats: use the upstream importer paths where available; verify support and conversion reports per file.

## Important operating advice

Keep the original AEP and its footage/plugin dependencies backed up. If After Effects plug-ins are essential to the look, render a reference movie or image sequence in a licensed After Effects installation and compare it with the imported result. The AEP import report is the authority for what this build did and did not convert.
