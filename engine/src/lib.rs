//! Shared, UI-independent SOWZ AE project engine.
//!
//! This crate is the common project and timeline layer intended for the Windows
//! desktop application and the Android application. It deliberately does not
//! claim to render video yet: preview and export are separate engine milestones.

#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

mod commands;
mod model;
mod package;

pub use commands::{Command, EditorEngine};
pub use model::{
    Asset, AssetKind, AssetSource, Composition, Effect, Interpolation, Keyframe,
    Layer, LayerClipboard, LayerKind, ParameterValue, Project, ProjectError, PropertyValue,
    Track, TrackKind, Transform,
};
pub use package::{LoadedPackage, PackageError, load_package, save_package};

#[cfg(test)]
mod tests;
