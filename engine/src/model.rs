use std::collections::HashSet;

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const PROJECT_SCHEMA_VERSION: u32 = 1;
pub const MAX_COMPOSITIONS: usize = 128;
pub const MAX_TRACKS_PER_COMPOSITION: usize = 128;
pub const MAX_LAYERS_PER_COMPOSITION: usize = 10_000;
pub const MAX_ASSETS: usize = 10_000;
pub const MAX_EFFECTS_PER_LAYER: usize = 256;
pub const MAX_KEYFRAMES_PER_LAYER: usize = 100_000;

#[derive(Debug, Error)]
pub enum ProjectError {
    #[error("Unsupported project schema version: {0}")]
    UnsupportedSchema(u32),
    #[error("Invalid project data: {0}")]
    Invalid(String),
    #[error("Identifier space exhausted")]
    IdExhausted,
    #[error("The requested item was not found")]
    NotFound,
    #[error("The target track is locked")]
    TrackLocked,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Project {
    pub schema_version: u32,
    pub id: u64,
    pub name: String,
    pub next_id: u64,
    pub assets: Vec<Asset>,
    pub compositions: Vec<Composition>,
}

impl Project {
    pub fn new(name: impl Into<String>, width: u32, height: u32, fps_num: u32, fps_den: u32, duration_frames: u64) -> Self {
        let name = name.into();
        Self {
            schema_version: PROJECT_SCHEMA_VERSION,
            id: 1,
            name,
            next_id: 4,
            assets: Vec::new(),
            compositions: vec![Composition {
                id: 2,
                name: "Main Composition".to_owned(),
                width,
                height,
                fps_num,
                fps_den,
                duration_frames,
                tracks: vec![Track {
                    id: 3,
                    name: "Video 1".to_owned(),
                    kind: TrackKind::Video,
                    muted: false,
                    locked: false,
                    visible: true,
                    layers: Vec::new(),
                }],
            }],
        }
    }

    pub fn allocate_id(&mut self) -> Result<u64, ProjectError> {
        let id = self.next_id;
        self.next_id = self.next_id.checked_add(1).ok_or(ProjectError::IdExhausted)?;
        Ok(id)
    }

    pub fn validate(&self) -> Result<(), ProjectError> {
        if self.schema_version != PROJECT_SCHEMA_VERSION {
            return Err(ProjectError::UnsupportedSchema(self.schema_version));
        }
        validate_name("project", &self.name)?;
        if self.compositions.is_empty() || self.compositions.len() > MAX_COMPOSITIONS {
            return Err(ProjectError::Invalid("composition count is outside the supported range".to_owned()));
        }
        if self.assets.len() > MAX_ASSETS {
            return Err(ProjectError::Invalid("asset limit exceeded".to_owned()));
        }

        let mut ids = HashSet::new();
        insert_id(&mut ids, self.id)?;
        for asset in &self.assets {
            insert_id(&mut ids, asset.id)?;
            validate_name("asset", &asset.name)?;
            match &asset.source {
                AssetSource::Linked { uri } if uri.trim().is_empty() => {
                    return Err(ProjectError::Invalid("linked asset URI is empty".to_owned()));
                }
                AssetSource::Embedded { path } if !is_safe_relative_path(path) => {
                    return Err(ProjectError::Invalid("embedded asset path is unsafe".to_owned()));
                }
                _ => {}
            }
        }

        for composition in &self.compositions {
            insert_id(&mut ids, composition.id)?;
            validate_name("composition", &composition.name)?;
            if composition.width == 0 || composition.height == 0 || composition.width > 16_384 || composition.height > 16_384 {
                return Err(ProjectError::Invalid("composition dimensions must be between 1 and 16384".to_owned()));
            }
            if composition.fps_num == 0 || composition.fps_den == 0 {
                return Err(ProjectError::Invalid("frame rate numerator and denominator must be non-zero".to_owned()));
            }
            let fps = f64::from(composition.fps_num) / f64::from(composition.fps_den);
            if !(1.0..=240.0).contains(&fps) {
                return Err(ProjectError::Invalid("frame rate must be between 1 and 240 fps".to_owned()));
            }
            if composition.duration_frames == 0 {
                return Err(ProjectError::Invalid("composition duration must be non-zero".to_owned()));
            }
            if composition.tracks.is_empty() || composition.tracks.len() > MAX_TRACKS_PER_COMPOSITION {
                return Err(ProjectError::Invalid("track count is outside the supported range".to_owned()));
            }

            let mut layer_count = 0usize;
            for track in &composition.tracks {
                insert_id(&mut ids, track.id)?;
                validate_name("track", &track.name)?;
                layer_count = layer_count.saturating_add(track.layers.len());
                if layer_count > MAX_LAYERS_PER_COMPOSITION {
                    return Err(ProjectError::Invalid("layer limit exceeded".to_owned()));
                }
                for layer in &track.layers {
                    insert_id(&mut ids, layer.id)?;
                    validate_name("layer", &layer.name)?;
                    if layer.duration_frames == 0 || layer.start_frame.checked_add(layer.duration_frames).is_none() {
                        return Err(ProjectError::Invalid("layer timing is invalid or overflows".to_owned()));
                    }
                    if let Some(asset_id) = layer.asset_id {
                        if !self.assets.iter().any(|asset| asset.id == asset_id) {
                            return Err(ProjectError::Invalid("layer references a missing asset".to_owned()));
                        }
                    }
                    validate_transform(&layer.transform)?;
                    if layer.effects.len() > MAX_EFFECTS_PER_LAYER || layer.keyframes.len() > MAX_KEYFRAMES_PER_LAYER {
                        return Err(ProjectError::Invalid("layer effect or keyframe limit exceeded".to_owned()));
                    }
                    for effect in &layer.effects {
                        insert_id(&mut ids, effect.id)?;
                        validate_name("effect", &effect.effect_type)?;
                        for (key, value) in &effect.parameters {
                            validate_name("effect parameter", key)?;
                            validate_parameter(value)?;
                        }
                    }
                    for keyframe in &layer.keyframes {
                        insert_id(&mut ids, keyframe.id)?;
                        validate_name("keyframe property", &keyframe.property)?;
                        validate_property_value(&keyframe.value)?;
                        if keyframe.frame > layer.duration_frames {
                            return Err(ProjectError::Invalid("keyframe lies outside its layer duration".to_owned()));
                        }
                    }
                }
            }
        }
        let highest_id = ids.iter().copied().max().unwrap_or(0);
        if self.next_id <= highest_id {
            return Err(ProjectError::Invalid("next_id must be greater than every allocated identifier".to_owned()));
        }
        Ok(())
    }
}

fn insert_id(ids: &mut HashSet<u64>, id: u64) -> Result<(), ProjectError> {
    if id == 0 || !ids.insert(id) {
        return Err(ProjectError::Invalid("identifiers must be unique and non-zero".to_owned()));
    }
    Ok(())
}

fn validate_name(kind: &str, value: &str) -> Result<(), ProjectError> {
    if value.trim().is_empty() || value.len() > 256 || value.chars().any(char::is_control) {
        return Err(ProjectError::Invalid(format!("{kind} name is empty, too long, or contains control characters")));
    }
    Ok(())
}

pub fn is_safe_relative_path(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.starts_with('\\') || path.contains('\\') || path.contains(':') {
        return false;
    }
    path.split('/').all(|part| !part.is_empty() && part != "." && part != "..")
}

fn finite(values: &[f64]) -> bool {
    values.iter().all(|value| value.is_finite())
}

fn validate_transform(value: &Transform) -> Result<(), ProjectError> {
    if !finite(&[
        value.anchor_x, value.anchor_y, value.position_x, value.position_y,
        value.scale_x, value.scale_y, value.rotation_degrees, value.opacity,
    ]) || !(0.0..=1.0).contains(&value.opacity) {
        return Err(ProjectError::Invalid("layer transform contains a non-finite value or invalid opacity".to_owned()));
    }
    Ok(())
}

fn validate_property_value(value: &PropertyValue) -> Result<(), ProjectError> {
    match value {
        PropertyValue::Number(v) if !v.is_finite() => Err(ProjectError::Invalid("keyframe value is not finite".to_owned())),
        PropertyValue::Vector2(x, y) if !finite(&[*x, *y]) => Err(ProjectError::Invalid("keyframe vector is not finite".to_owned())),
        PropertyValue::Color(r, g, b, a) if !finite(&[*r, *g, *b, *a]) => Err(ProjectError::Invalid("keyframe colour is not finite".to_owned())),
        _ => Ok(()),
    }
}

fn validate_parameter(value: &ParameterValue) -> Result<(), ProjectError> {
    match value {
        ParameterValue::Number(v) if !v.is_finite() => Err(ProjectError::Invalid("effect parameter is not finite".to_owned())),
        ParameterValue::Vector2(x, y) if !finite(&[*x, *y]) => Err(ProjectError::Invalid("effect vector is not finite".to_owned())),
        ParameterValue::Color(r, g, b, a) if !finite(&[*r, *g, *b, *a]) => Err(ProjectError::Invalid("effect colour is not finite".to_owned())),
        _ => Ok(()),
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Asset {
    pub id: u64,
    pub name: String,
    pub kind: AssetKind,
    pub source: AssetSource,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AssetKind { Video, Audio, Image, Vector, Font, Other }

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum AssetSource {
    Linked { uri: String },
    Embedded { path: String },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Composition {
    pub id: u64,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub fps_num: u32,
    pub fps_den: u32,
    pub duration_frames: u64,
    pub tracks: Vec<Track>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TrackKind { Video, Audio, Text, Shape, Adjustment }

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Track {
    pub id: u64,
    pub name: String,
    pub kind: TrackKind,
    pub muted: bool,
    pub locked: bool,
    pub visible: bool,
    pub layers: Vec<Layer>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Layer {
    pub id: u64,
    pub name: String,
    pub kind: LayerKind,
    pub start_frame: u64,
    pub duration_frames: u64,
    pub asset_id: Option<u64>,
    pub enabled: bool,
    pub transform: Transform,
    pub effects: Vec<Effect>,
    pub keyframes: Vec<Keyframe>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LayerKind { Footage, Audio, Text, Shape, Solid, Precomposition, Adjustment }

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Transform {
    pub anchor_x: f64,
    pub anchor_y: f64,
    pub position_x: f64,
    pub position_y: f64,
    pub scale_x: f64,
    pub scale_y: f64,
    pub rotation_degrees: f64,
    pub opacity: f64,
}

impl Default for Transform {
    fn default() -> Self {
        Self {
            anchor_x: 0.0, anchor_y: 0.0, position_x: 0.0, position_y: 0.0,
            scale_x: 1.0, scale_y: 1.0, rotation_degrees: 0.0, opacity: 1.0,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct LayerClipboard {
    pub schema_version: u32,
    pub layer: Layer,
}

impl LayerClipboard {
    pub const SCHEMA_VERSION: u32 = 1;
    pub const MAX_BYTES: usize = 4 * 1024 * 1024;

    pub fn new(layer: &Layer) -> Self {
        Self { schema_version: Self::SCHEMA_VERSION, layer: layer.clone() }
    }

    pub fn encode(&self) -> Result<Vec<u8>, ProjectError> {
        let bytes = serde_json::to_vec(self)
            .map_err(|error| ProjectError::Invalid(format!("clipboard encoding failed: {error}")))?;
        if bytes.len() > Self::MAX_BYTES {
            return Err(ProjectError::Invalid("clipboard payload exceeds the size limit".to_owned()));
        }
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, ProjectError> {
        if bytes.len() > Self::MAX_BYTES {
            return Err(ProjectError::Invalid("clipboard payload exceeds the size limit".to_owned()));
        }
        let clipboard: Self = serde_json::from_slice(bytes)
            .map_err(|error| ProjectError::Invalid(format!("clipboard data is invalid: {error}")))?;
        if clipboard.schema_version != Self::SCHEMA_VERSION {
            return Err(ProjectError::Invalid("unsupported clipboard schema version".to_owned()));
        }
        validate_clipboard_layer(&clipboard.layer)?;
        Ok(clipboard)
    }
}

fn validate_clipboard_layer(layer: &Layer) -> Result<(), ProjectError> {
    validate_name("layer", &layer.name)?;
    if layer.duration_frames == 0 || layer.start_frame.checked_add(layer.duration_frames).is_none() {
        return Err(ProjectError::Invalid("clipboard layer timing is invalid".to_owned()));
    }
    validate_transform(&layer.transform)?;
    if layer.effects.len() > MAX_EFFECTS_PER_LAYER || layer.keyframes.len() > MAX_KEYFRAMES_PER_LAYER {
        return Err(ProjectError::Invalid("clipboard layer exceeds the effect or keyframe limit".to_owned()));
    }
    let mut ids = HashSet::new();
    insert_id(&mut ids, layer.id)?;
    for effect in &layer.effects {
        insert_id(&mut ids, effect.id)?;
        validate_name("effect", &effect.effect_type)?;
        for (key, value) in &effect.parameters {
            validate_name("effect parameter", key)?;
            validate_parameter(value)?;
        }
    }
    for keyframe in &layer.keyframes {
        insert_id(&mut ids, keyframe.id)?;
        validate_name("keyframe property", &keyframe.property)?;
        validate_property_value(&keyframe.value)?;
        if keyframe.frame > layer.duration_frames {
            return Err(ProjectError::Invalid("clipboard keyframe lies outside its layer duration".to_owned()));
        }
    }
    Ok(())
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Effect {
    pub id: u64,
    pub effect_type: String,
    pub enabled: bool,
    pub parameters: std::collections::BTreeMap<String, ParameterValue>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "value_type", content = "value", rename_all = "snake_case")]
pub enum ParameterValue {
    Number(f64),
    Boolean(bool),
    Text(String),
    Vector2(f64, f64),
    Color(f64, f64, f64, f64),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Keyframe {
    pub id: u64,
    pub frame: u64,
    pub property: String,
    pub value: PropertyValue,
    pub interpolation: Interpolation,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Interpolation { Hold, Linear, Bezier, EaseIn, EaseOut, EaseInOut }

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "value_type", content = "value", rename_all = "snake_case")]
pub enum PropertyValue {
    Number(f64),
    Boolean(bool),
    Text(String),
    Vector2(f64, f64),
    Color(f64, f64, f64, f64),
}
