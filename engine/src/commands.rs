use std::collections::BTreeMap;

use crate::model::{
    Asset, AssetKind, AssetSource, Effect, Interpolation, Keyframe, Layer, LayerClipboard, LayerKind, ParameterValue, Project,
    ProjectError, PropertyValue, Track, TrackKind, Transform,
};

const MAX_HISTORY: usize = 100;

#[derive(Clone, Debug)]
pub enum Command {
    AddTrack { composition_id: u64, name: String, kind: TrackKind },
    SetTrackFlags { track_id: u64, muted: bool, locked: bool, visible: bool },
    AddAsset { name: String, kind: AssetKind, source: AssetSource },
    AddLayer {
        composition_id: u64,
        track_id: u64,
        name: String,
        kind: LayerKind,
        asset_id: Option<u64>,
        start_frame: u64,
        duration_frames: u64,
    },
    MoveLayer { layer_id: u64, new_start_frame: u64 },
    RenameLayer { layer_id: u64, name: String },
    SetLayerProperties {
        layer_id: u64,
        name: String,
        start_frame: u64,
        duration_frames: u64,
        transform: Transform,
    },
    DuplicateLayer { layer_id: u64, new_start_frame: u64 },
    SplitLayer { layer_id: u64, at_frame: u64 },
    ReorderLayer { layer_id: u64, new_index: usize },
    PasteLayer { composition_id: u64, track_id: u64, clipboard: LayerClipboard, new_start_frame: u64 },
    TrimLayer { layer_id: u64, new_duration_frames: u64 },
    SetLayerTransform { layer_id: u64, transform: Transform },
    SetLayerEnabled { layer_id: u64, enabled: bool },
    PutKeyframe {
        layer_id: u64,
        frame: u64,
        property: String,
        value: PropertyValue,
        interpolation: Interpolation,
    },
    AddEffect { layer_id: u64, effect_type: String },
    SetEffectParameter { effect_id: u64, name: String, value: ParameterValue },
    RemoveLayer { layer_id: u64 },
}

pub struct EditorEngine {
    project: Project,
    undo_stack: Vec<Project>,
    redo_stack: Vec<Project>,
}

impl EditorEngine {
    pub fn new(project: Project) -> Result<Self, ProjectError> {
        project.validate()?;
        Ok(Self { project, undo_stack: Vec::new(), redo_stack: Vec::new() })
    }

    pub fn project(&self) -> &Project {
        &self.project
    }

    pub fn copy_layer(&self, layer_id: u64) -> Result<LayerClipboard, ProjectError> {
        Ok(LayerClipboard::new(find_layer(&self.project, layer_id)?))
    }

    pub fn paste_layer(
        &mut self,
        composition_id: u64,
        track_id: u64,
        clipboard: LayerClipboard,
        new_start_frame: u64,
    ) -> Result<(), ProjectError> {
        self.execute(Command::PasteLayer {
            composition_id,
            track_id,
            clipboard,
            new_start_frame,
        })
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo_stack.is_empty()
    }

    /// Executes one atomic edit. A failed command leaves the live project unchanged.
    pub fn execute(&mut self, command: Command) -> Result<(), ProjectError> {
        let mut candidate = self.project.clone();
        apply_command(&mut candidate, command)?;
        candidate.validate()?;

        self.undo_stack.push(self.project.clone());
        if self.undo_stack.len() > MAX_HISTORY {
            self.undo_stack.remove(0);
        }
        self.redo_stack.clear();
        self.project = candidate;
        Ok(())
    }

    pub fn undo(&mut self) -> bool {
        let Some(previous) = self.undo_stack.pop() else {
            return false;
        };
        self.redo_stack.push(self.project.clone());
        self.project = previous;
        true
    }

    pub fn redo(&mut self) -> bool {
        let Some(next) = self.redo_stack.pop() else {
            return false;
        };
        self.undo_stack.push(self.project.clone());
        if self.undo_stack.len() > MAX_HISTORY {
            self.undo_stack.remove(0);
        }
        self.project = next;
        true
    }
}

fn apply_command(project: &mut Project, command: Command) -> Result<(), ProjectError> {
    match command {
        Command::AddTrack { composition_id, name, kind } => {
            let id = project.allocate_id()?;
            let composition = project.compositions.iter_mut()
                .find(|item| item.id == composition_id)
                .ok_or(ProjectError::NotFound)?;
            composition.tracks.push(Track {
                id, name, kind, muted: false, locked: false, visible: true, layers: Vec::new(),
            });
        }
        Command::AddAsset { name, kind, source } => {
            let id = project.allocate_id()?;
            project.assets.push(Asset { id, name, kind, source });
        }
        Command::SetTrackFlags { track_id, muted, locked, visible } => {
            let track = find_track_mut(project, track_id)?;
            track.muted = muted;
            track.locked = locked;
            track.visible = visible;
        }
        Command::AddLayer {
            composition_id, track_id, name, kind, asset_id, start_frame, duration_frames,
        } => {
            let id = project.allocate_id()?;
            let composition = project.compositions.iter_mut()
                .find(|item| item.id == composition_id)
                .ok_or(ProjectError::NotFound)?;
            let track = composition.tracks.iter_mut()
                .find(|item| item.id == track_id)
                .ok_or(ProjectError::NotFound)?;
            if track.locked {
                return Err(ProjectError::TrackLocked);
            }
            track.layers.push(Layer {
                id, name, kind, start_frame, duration_frames, asset_id,
                enabled: true, transform: Transform::default(), effects: Vec::new(), keyframes: Vec::new(),
            });
        }
        Command::MoveLayer { layer_id, new_start_frame } => {
            find_layer_mut(project, layer_id)?.start_frame = new_start_frame;
        }
        Command::RenameLayer { layer_id, name } => {
            find_layer_mut(project, layer_id)?.name = name;
        }
        Command::SetLayerProperties { layer_id, name, start_frame, duration_frames, transform } => {
            let layer = find_layer_mut(project, layer_id)?;
            layer.name = name;
            layer.start_frame = start_frame;
            layer.duration_frames = duration_frames;
            layer.transform = transform;
        }
        Command::DuplicateLayer { layer_id, new_start_frame } => {
            let (composition_index, track_index, _, original) = find_layer_location(project, layer_id)?;
            if project.compositions[composition_index].tracks[track_index].locked {
                return Err(ProjectError::TrackLocked);
            }
            let mut duplicate = original;
            duplicate.id = project.allocate_id()?;
            duplicate.start_frame = new_start_frame;
            for effect in &mut duplicate.effects {
                effect.id = project.allocate_id()?;
            }
            for keyframe in &mut duplicate.keyframes {
                keyframe.id = project.allocate_id()?;
            }
            project.compositions[composition_index].tracks[track_index].layers.push(duplicate);
        }
        Command::SplitLayer { layer_id, at_frame } => {
            let (composition_index, track_index, layer_index, original) =
                find_layer_location(project, layer_id)?;
            if project.compositions[composition_index].tracks[track_index].locked {
                return Err(ProjectError::TrackLocked);
            }
            let split_offset = at_frame.checked_sub(original.start_frame)
                .ok_or_else(|| ProjectError::Invalid("split point is before the layer".to_owned()))?;
            if split_offset == 0 || split_offset >= original.duration_frames {
                return Err(ProjectError::Invalid("split point must be inside the layer".to_owned()));
            }

            let mut right = original.clone();
            right.id = project.allocate_id()?;
            right.start_frame = at_frame;
            right.duration_frames = original.duration_frames - split_offset;
            right.name = original.name.clone();

            for effect in &mut right.effects {
                effect.id = project.allocate_id()?;
            }
            right.keyframes.retain(|keyframe| keyframe.frame >= split_offset);
            for keyframe in &mut right.keyframes {
                keyframe.id = project.allocate_id()?;
                keyframe.frame -= split_offset;
            }

            let left = &mut project.compositions[composition_index].tracks[track_index].layers[layer_index];
            left.duration_frames = split_offset;
            left.keyframes.retain(|keyframe| keyframe.frame <= split_offset);
            project.compositions[composition_index].tracks[track_index]
                .layers.insert(layer_index + 1, right);
        }
        Command::ReorderLayer { layer_id, new_index } => {
            let (composition_index, track_index, layer_index, _) = find_layer_location(project, layer_id)?;
            if project.compositions[composition_index].tracks[track_index].locked {
                return Err(ProjectError::TrackLocked);
            }
            let layers = &mut project.compositions[composition_index].tracks[track_index].layers;
            let layer = layers.remove(layer_index);
            let destination = new_index.min(layers.len());
            layers.insert(destination, layer);
        }
        Command::PasteLayer { composition_id, track_id, clipboard, new_start_frame } => {
            if clipboard.schema_version != LayerClipboard::SCHEMA_VERSION {
                return Err(ProjectError::Invalid("unsupported clipboard schema version".to_owned()));
            }
            let mut layer = clipboard.layer;
            layer.id = project.allocate_id()?;
            layer.start_frame = new_start_frame;
            for effect in &mut layer.effects {
                effect.id = project.allocate_id()?;
            }
            for keyframe in &mut layer.keyframes {
                keyframe.id = project.allocate_id()?;
            }
            let composition = project.compositions.iter_mut()
                .find(|item| item.id == composition_id)
                .ok_or(ProjectError::NotFound)?;
            let track = composition.tracks.iter_mut()
                .find(|item| item.id == track_id)
                .ok_or(ProjectError::NotFound)?;
            if track.locked {
                return Err(ProjectError::TrackLocked);
            }
            track.layers.push(layer);
        }
        Command::TrimLayer { layer_id, new_duration_frames } => {
            find_layer_mut(project, layer_id)?.duration_frames = new_duration_frames;
        }
        Command::SetLayerTransform { layer_id, transform } => {
            find_layer_mut(project, layer_id)?.transform = transform;
        }
        Command::SetLayerEnabled { layer_id, enabled } => {
            find_layer_mut(project, layer_id)?.enabled = enabled;
        }
        Command::PutKeyframe { layer_id, frame, property, value, interpolation } => {
            let id = project.allocate_id()?;
            let layer = find_layer_mut(project, layer_id)?;
            if let Some(existing) = layer.keyframes.iter_mut()
                .find(|item| item.frame == frame && item.property == property) {
                existing.value = value;
                existing.interpolation = interpolation;
            } else {
                layer.keyframes.push(Keyframe { id, frame, property, value, interpolation });
                layer.keyframes.sort_by_key(|item| item.frame);
            }
        }
        Command::AddEffect { layer_id, effect_type } => {
            let id = project.allocate_id()?;
            let layer = find_layer_mut(project, layer_id)?;
            layer.effects.push(Effect {
                id, effect_type, enabled: true, parameters: BTreeMap::new(),
            });
        }
        Command::SetEffectParameter { effect_id, name, value } => {
            let effect = find_effect_mut(project, effect_id)?;
            effect.parameters.insert(name, value);
        }
        Command::RemoveLayer { layer_id } => {
            for composition in &mut project.compositions {
                for track in &mut composition.tracks {
                    if let Some(index) = track.layers.iter().position(|item| item.id == layer_id) {
                        if track.locked {
                            return Err(ProjectError::TrackLocked);
                        }
                        track.layers.remove(index);
                        return Ok(());
                    }
                }
            }
            return Err(ProjectError::NotFound);
        }
    }
    Ok(())
}

fn find_track_mut(project: &mut Project, track_id: u64) -> Result<&mut Track, ProjectError> {
    for composition in &mut project.compositions {
        if let Some(track) = composition.tracks.iter_mut().find(|track| track.id == track_id) {
            return Ok(track);
        }
    }
    Err(ProjectError::NotFound)
}

fn find_layer<'a>(project: &'a Project, layer_id: u64) -> Result<&'a Layer, ProjectError> {
    for composition in &project.compositions {
        for track in &composition.tracks {
            if let Some(layer) = track.layers.iter().find(|item| item.id == layer_id) {
                return Ok(layer);
            }
        }
    }
    Err(ProjectError::NotFound)
}

fn find_layer_location(
    project: &Project,
    layer_id: u64,
) -> Result<(usize, usize, usize, Layer), ProjectError> {
    for (composition_index, composition) in project.compositions.iter().enumerate() {
        for (track_index, track) in composition.tracks.iter().enumerate() {
            if let Some((layer_index, layer)) = track.layers.iter().enumerate()
                .find(|(_, item)| item.id == layer_id) {
                return Ok((composition_index, track_index, layer_index, layer.clone()));
            }
        }
    }
    Err(ProjectError::NotFound)
}

fn find_layer_mut(project: &mut Project, layer_id: u64) -> Result<&mut Layer, ProjectError> {
    for composition in &mut project.compositions {
        for track in &mut composition.tracks {
            if let Some(layer) = track.layers.iter_mut().find(|item| item.id == layer_id) {
                if track.locked {
                    return Err(ProjectError::TrackLocked);
                }
                return Ok(layer);
            }
        }
    }
    Err(ProjectError::NotFound)
}

fn find_effect_mut(project: &mut Project, effect_id: u64) -> Result<&mut Effect, ProjectError> {
    for composition in &mut project.compositions {
        for track in &mut composition.tracks {
            for layer in &mut track.layers {
                if let Some(effect) = layer.effects.iter_mut().find(|item| item.id == effect_id) {
                    if track.locked {
                        return Err(ProjectError::TrackLocked);
                    }
                    return Ok(effect);
                }
            }
        }
    }
    Err(ProjectError::NotFound)
}
