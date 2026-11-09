# EffectCraft attribution

SOWZ AE Studio 1.6.0 includes a native Android workspace visually adapted from the public
EffectCraft desktop editor's panel organization and dark-theme design tokens.

- Upstream: https://github.com/storytold/effectcraft
- Upstream revision referenced during this port: 81a12ffffc06e04038a33665b125875fc4aa6fb7
- Relevant upstream UI files reviewed: `crates/ui-egui/src/theme.rs`,
  `crates/ui-egui/src/dock_ui.rs`, `crates/ui-egui/src/panels/viewer.rs`,
  `crates/ui-egui/src/panels/timeline.rs`, `crates/ui-egui/src/panels/layer_panel.rs`,
  and `crates/ui-egui/src/panels/effect_controls/panel.rs`.

The Android timeline drawing component is a Kotlin implementation. EffectCraft's desktop
`egui` widgets, renderer, project engine, logos, and bundled fonts are not included in this
UI-only port. The Android app keeps its own SOWZ branding and native Media3 editing pipeline.

EffectCraft is Copyright (c) 2026 ArtCraft Team and the EffectCraft contributors and is made
available under either the MIT License or Apache License, Version 2.0. Copies of both upstream
license texts are stored in this directory. The ArtCraft name and logos are not used.
