#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::{
    collections::BTreeMap,
    path::PathBuf,
};

use eframe::egui::{self, Align2, Color32, FontId, Id, Rect, Sense, Stroke, StrokeKind, pos2, vec2};
use sowz_ae_core::{
    AssetKind, AssetSource, Command, EditorEngine, Interpolation, Layer, LayerClipboard, LayerKind, LoadedPackage,
    Project, PropertyValue, TrackKind, Transform, load_package, save_package,
};

const DEFAULT_WIDTH: u32 = 1920;
const DEFAULT_HEIGHT: u32 = 1080;
const DEFAULT_FPS: u32 = 30;
const DEFAULT_DURATION: u64 = 300;

struct SowzAeApp {
    engine: EditorEngine,
    selected_track_id: u64,
    selected_layer_id: Option<u64>,
    selected_asset_id: Option<u64>,
    playhead_frame: u64,
    clipboard: Option<LayerClipboard>,
    package_files: BTreeMap<String, Vec<u8>>,
    save_path: Option<PathBuf>,
    status: String,
    rename_target: Option<u64>,
    name_draft: String,
    start_draft: u64,
    duration_draft: u64,
    transform_draft: Transform,
}

impl SowzAeApp {
    fn new() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let project = Project::new(
            "Untitled Project",
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            DEFAULT_FPS,
            1,
            DEFAULT_DURATION,
        );
        let engine = EditorEngine::new(project)?;
        Ok(Self {
            engine,
            selected_track_id: 3,
            selected_layer_id: None,
            selected_asset_id: None,
            playhead_frame: 0,
            clipboard: None,
            package_files: BTreeMap::new(),
            save_path: None,
            status: "Ready".to_owned(),
            rename_target: None,
            name_draft: String::new(),
            start_draft: 0,
            duration_draft: DEFAULT_DURATION,
            transform_draft: Transform::default(),
        })
    }

    fn composition(&self) -> Option<&sowz_ae_core::Composition> {
        self.engine.project().compositions.first()
    }

    fn selected_layer(&self) -> Option<Layer> {
        let layer_id = self.selected_layer_id?;
        self.engine.project().compositions.iter()
            .flat_map(|composition| composition.tracks.iter())
            .flat_map(|track| track.layers.iter())
            .find(|layer| layer.id == layer_id)
            .cloned()
    }

    fn sync_inspector(&mut self) {
        if self.rename_target == self.selected_layer_id {
            return;
        }
        self.rename_target = self.selected_layer_id;
        if let Some(layer) = self.selected_layer() {
            self.name_draft = layer.name;
            self.start_draft = layer.start_frame;
            self.duration_draft = layer.duration_frames;
            self.transform_draft = layer.transform;
        } else {
            self.name_draft.clear();
            self.start_draft = 0;
            self.duration_draft = DEFAULT_DURATION;
            self.transform_draft = Transform::default();
        }
    }

    fn new_project(&mut self) {
        let project = Project::new(
            "Untitled Project",
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            DEFAULT_FPS,
            1,
            DEFAULT_DURATION,
        );
        match EditorEngine::new(project) {
            Ok(engine) => {
                self.engine = engine;
                self.selected_track_id = 3;
                self.selected_layer_id = None;
                self.selected_asset_id = None;
                self.playhead_frame = 0;
                self.clipboard = None;
                self.package_files.clear();
                self.save_path = None;
                self.status = "New project created".to_owned();
                self.sync_inspector();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn open_project(&mut self) {
        let picked = rfd::FileDialog::new()
            .add_filter("SOWZ AE Project", &["sowzae"])
            .pick_file();
        let Some(path) = picked else { return; };
        match load_package(&path) {
            Ok(package) => match EditorEngine::new(package.project) {
                Ok(engine) => {
                    self.engine = engine;
                    self.package_files = package.files;
                    self.save_path = Some(path.clone());
                    self.selected_track_id = self.composition()
                        .and_then(|composition| composition.tracks.first())
                        .map(|track| track.id)
                        .unwrap_or(0);
                    self.selected_layer_id = None;
                    self.selected_asset_id = None;
                    self.playhead_frame = 0;
                    self.status = format!("Opened {}", path.file_name().and_then(|name| name.to_str()).unwrap_or("project"));
                    self.sync_inspector();
                }
                Err(error) => self.status = format!("Project validation failed: {error}"),
            },
            Err(error) => self.status = format!("Could not open project: {error}"),
        }
    }

    fn save_project(&mut self) {
        let path = self.save_path.clone().or_else(|| {
            rfd::FileDialog::new()
                .add_filter("SOWZ AE Project", &["sowzae"])
                .set_file_name("Untitled.sowzae")
                .save_file()
        });
        let Some(path) = path else { return; };
        self.write_project(path);
    }

    fn save_project_as(&mut self) {
        let path = rfd::FileDialog::new()
            .add_filter("SOWZ AE Project", &["sowzae"])
            .set_file_name("SOWZ-Project.sowzae")
            .save_file();
        if let Some(path) = path {
            self.write_project(path);
        }
    }

    fn write_project(&mut self, path: PathBuf) {
        let package = LoadedPackage {
            project: self.engine.project().clone(),
            files: self.package_files.clone(),
        };
        match save_package(&path, &package) {
            Ok(()) => {
                self.save_path = Some(path.clone());
                self.status = format!("Saved {}", path.file_name().and_then(|name| name.to_str()).unwrap_or("project"));
            }
            Err(error) => self.status = format!("Save failed: {error}"),
        }
    }

    fn execute(&mut self, command: Command) {
        match self.engine.execute(command) {
            Ok(()) => self.status = "Project updated".to_owned(),
            Err(error) => self.status = error.to_string(),
        }
        self.sync_inspector();
    }

    fn add_track(&mut self, kind: TrackKind) {
        let Some(composition_id) = self.composition().map(|composition| composition.id) else {
            self.status = "No composition is available".to_owned();
            return;
        };
        let name = match kind {
            TrackKind::Video => "Video",
            TrackKind::Audio => "Audio",
            TrackKind::Text => "Text",
            TrackKind::Shape => "Shape",
            TrackKind::Adjustment => "Adjustment",
        };
        let count = self.composition()
            .map(|composition| composition.tracks.iter().filter(|track| track.kind == kind).count() + 1)
            .unwrap_or(1);
        let result = self.engine.execute(Command::AddTrack {
            composition_id,
            name: format!("{name} {count}"),
            kind,
        });
        match result {
            Ok(()) => {
                if let Some(track) = self.composition().and_then(|composition| composition.tracks.last()) {
                    self.selected_track_id = track.id;
                }
                self.status = "Track added".to_owned();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn add_layer(&mut self) {
        let Some(composition) = self.composition() else {
            self.status = "No composition is available".to_owned();
            return;
        };
        let composition_id = composition.id;
        let composition_duration = composition.duration_frames;
        let Some(track) = composition.tracks.iter().find(|track| track.id == self.selected_track_id) else {
            self.status = "Select a track first".to_owned();
            return;
        };
        let kind = match track.kind {
            TrackKind::Video => LayerKind::Footage,
            TrackKind::Audio => LayerKind::Audio,
            TrackKind::Text => LayerKind::Text,
            TrackKind::Shape => LayerKind::Shape,
            TrackKind::Adjustment => LayerKind::Adjustment,
        };
        let name = match kind {
            LayerKind::Footage => "Video Layer",
            LayerKind::Audio => "Audio Layer",
            LayerKind::Text => "Text Layer",
            LayerKind::Shape => "Shape Layer",
            LayerKind::Solid => "Solid Layer",
            LayerKind::Precomposition => "Precomposition",
            LayerKind::Adjustment => "Adjustment Layer",
        };
        let start = self.playhead_frame.min(composition_duration.saturating_sub(1));
        let duration = composition_duration.saturating_sub(start).max(1);
        let result = self.engine.execute(Command::AddLayer {
            composition_id,
            track_id: self.selected_track_id,
            name: name.to_owned(),
            kind,
            asset_id: None,
            start_frame: start,
            duration_frames: duration,
        });
        match result {
            Ok(()) => {
                self.selected_layer_id = self.composition()
                    .and_then(|composition| composition.tracks.iter().find(|track| track.id == self.selected_track_id))
                    .and_then(|track| track.layers.last())
                    .map(|layer| layer.id);
                self.status = "Layer added".to_owned();
                self.sync_inspector();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn import_media(&mut self) {
        let paths = rfd::FileDialog::new()
            .add_filter("Media and graphics", &[
                "mp4", "mov", "m4v", "mkv", "webm", "avi", "mxf",
                "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "exr", "gif",
                "psd", "psb", "svg", "json", "lottie",
                "wav", "mp3", "m4a", "aac", "aiff", "flac", "opus",
            ])
            .pick_files();
        let Some(paths) = paths else { return; };
        let mut imported = 0usize;
        for path in paths {
            let extension = path.extension()
                .and_then(|value| value.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            let kind = match extension.as_str() {
                "mp4" | "mov" | "m4v" | "mkv" | "webm" | "avi" | "mxf" => AssetKind::Video,
                "wav" | "mp3" | "m4a" | "aac" | "aiff" | "flac" | "opus" => AssetKind::Audio,
                "png" | "jpg" | "jpeg" | "webp" | "bmp" | "tif" | "tiff" | "exr" | "gif" | "psd" | "psb" => AssetKind::Image,
                "svg" | "json" | "lottie" => AssetKind::Vector,
                _ => AssetKind::Other,
            };
            let name = path.file_name()
                .and_then(|value| value.to_str())
                .unwrap_or("Imported Media")
                .to_owned();
            let source = AssetSource::Linked { uri: path.to_string_lossy().into_owned() };
            match self.engine.execute(Command::AddAsset { name, kind, source }) {
                Ok(()) => {
                    self.selected_asset_id = Some(self.engine.project().assets.last().map(|asset| asset.id).unwrap_or(0));
                    imported += 1;
                }
                Err(error) => self.status = error.to_string(),
            }
        }
        if imported > 0 {
            self.status = format!("Imported {imported} linked asset(s). Original files stay at their current paths.");
        }
    }

    fn add_asset_to_timeline(&mut self) {
        let Some(asset_id) = self.selected_asset_id else {
            self.status = "Select a media asset first".to_owned();
            return;
        };
        let Some(asset) = self.engine.project().assets.iter().find(|asset| asset.id == asset_id).cloned() else {
            self.status = "Selected media asset was not found".to_owned();
            return;
        };
        let (track_kind, layer_kind) = match asset.kind {
            AssetKind::Video => (TrackKind::Video, LayerKind::Footage),
            AssetKind::Audio => (TrackKind::Audio, LayerKind::Audio),
            AssetKind::Image | AssetKind::Vector => (TrackKind::Video, LayerKind::Footage),
            AssetKind::Font | AssetKind::Other => {
                self.status = "This asset type cannot be placed on the timeline yet".to_owned();
                return;
            }
        };
        let Some(composition) = self.composition() else {
            self.status = "No composition is available".to_owned();
            return;
        };
        let composition_id = composition.id;
        let composition_duration = composition.duration_frames;
        let existing_track = composition.tracks.iter()
            .find(|track| track.kind == track_kind)
            .map(|track| track.id);
        if let Some(track_id) = existing_track {
            self.selected_track_id = track_id;
        } else {
            self.add_track(track_kind);
        }

        let start = self.playhead_frame.min(composition_duration.saturating_sub(1));
        let duration = composition_duration.saturating_sub(start).max(1);
        let layer_id = self.engine.project().next_id;
        match self.engine.execute(Command::AddLayer {
            composition_id,
            track_id: self.selected_track_id,
            name: asset.name,
            kind: layer_kind,
            asset_id: Some(asset_id),
            start_frame: start,
            duration_frames: duration,
        }) {
            Ok(()) => {
                self.selected_layer_id = Some(layer_id);
                self.status = "Media reference added to timeline. Preview decoding is not connected yet.".to_owned();
                self.sync_inspector();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn split_selected(&mut self) {
        let Some(layer_id) = self.selected_layer_id else { return; };
        self.execute(Command::SplitLayer { layer_id, at_frame: self.playhead_frame });
    }

    fn duplicate_selected(&mut self) {
        let Some(layer) = self.selected_layer() else { return; };
        let new_start_frame = layer.start_frame.saturating_add(layer.duration_frames);
        let result = self.engine.execute(Command::DuplicateLayer {
            layer_id: layer.id,
            new_start_frame,
        });
        match result {
            Ok(()) => {
                self.selected_layer_id = self.composition()
                    .and_then(|composition| composition.tracks.iter().find(|track| track.id == self.selected_track_id))
                    .and_then(|track| track.layers.last())
                    .map(|layer| layer.id);
                self.status = "Layer duplicated".to_owned();
                self.sync_inspector();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn remove_selected(&mut self) {
        let Some(layer_id) = self.selected_layer_id else { return; };
        self.execute(Command::RemoveLayer { layer_id });
        self.selected_layer_id = None;
        self.sync_inspector();
    }

    fn copy_selected(&mut self) {
        let Some(layer_id) = self.selected_layer_id else { return; };
        match self.engine.copy_layer(layer_id) {
            Ok(clipboard) => {
                self.clipboard = Some(clipboard);
                self.status = "Layer copied to SOWZ AE clipboard".to_owned();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn paste_layer(&mut self) {
        let Some(clipboard) = self.clipboard.clone() else {
            self.status = "SOWZ AE clipboard is empty".to_owned();
            return;
        };
        let Some(composition_id) = self.composition().map(|composition| composition.id) else {
            self.status = "No composition is available".to_owned();
            return;
        };
        match self.engine.paste_layer(composition_id, self.selected_track_id, clipboard, self.playhead_frame) {
            Ok(()) => {
                self.selected_layer_id = self.composition()
                    .and_then(|composition| composition.tracks.iter().find(|track| track.id == self.selected_track_id))
                    .and_then(|track| track.layers.last())
                    .map(|layer| layer.id);
                self.status = "Layer pasted".to_owned();
                self.sync_inspector();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn reorder_selected(&mut self, delta: isize) {
        let Some(layer) = self.selected_layer() else { return; };
        let Some(track) = self.composition().and_then(|composition| {
            composition.tracks.iter().find(|track| track.layers.iter().any(|item| item.id == layer.id))
        }) else { return; };
        let current_index = track.layers.iter().position(|item| item.id == layer.id).unwrap_or(0);
        let destination = (current_index as isize + delta).clamp(0, track.layers.len().saturating_sub(1) as isize) as usize;
        self.execute(Command::ReorderLayer { layer_id: layer.id, new_index: destination });
    }

    fn apply_inspector(&mut self) {
        let Some(layer_id) = self.selected_layer_id else { return; };
        let command = Command::SetLayerProperties {
            layer_id,
            name: self.name_draft.clone(),
            start_frame: self.start_draft,
            duration_frames: self.duration_draft,
            transform: self.transform_draft.clone(),
        };
        self.status = match self.engine.execute(command) {
            Ok(()) => "Layer properties applied".to_owned(),
            Err(error) => error.to_string(),
        };
        self.sync_inspector();
    }

    fn add_numeric_keyframe(&mut self, property: &'static str) {
        let Some(layer) = self.selected_layer() else { return; };
        let Some(frame) = self.playhead_frame.checked_sub(layer.start_frame) else {
            self.status = "Move the playhead onto the selected layer first".to_owned();
            return;
        };
        if frame > layer.duration_frames {
            self.status = "Playhead is outside the selected layer".to_owned();
            return;
        }
        let value = match property {
            "position_x" => layer.transform.position_x,
            "position_y" => layer.transform.position_y,
            "scale_x" => layer.transform.scale_x,
            "scale_y" => layer.transform.scale_y,
            "rotation_degrees" => layer.transform.rotation_degrees,
            "opacity" => layer.transform.opacity,
            _ => {
                self.status = "Unsupported animated property".to_owned();
                return;
            }
        };
        self.execute(Command::PutKeyframe {
            layer_id: layer.id,
            frame,
            property: property.to_owned(),
            value: PropertyValue::Number(value),
            interpolation: Interpolation::Bezier,
        });
    }

    fn handle_shortcuts(&mut self, ctx: &egui::Context) {
        let (save, undo, redo, copy, paste) = ctx.input(|input| {
            let command = input.modifiers.command;
            (
                command && input.key_pressed(egui::Key::S),
                command && input.key_pressed(egui::Key::Z) && !input.modifiers.shift,
                command && input.key_pressed(egui::Key::Z) && input.modifiers.shift,
                command && input.key_pressed(egui::Key::C),
                command && input.key_pressed(egui::Key::V),
            )
        });
        if save { self.save_project(); }
        if undo {
            if self.engine.undo() { self.status = "Undo".to_owned(); self.sync_inspector(); }
        }
        if redo {
            if self.engine.redo() { self.status = "Redo".to_owned(); self.sync_inspector(); }
        }
        if copy { self.copy_selected(); }
        if paste { self.paste_layer(); }
    }
}

impl eframe::App for SowzAeApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.handle_shortcuts(ctx);
        self.sync_inspector();

        let accent = Color32::from_rgb(255, 145, 74);
        let panel = Color32::from_rgb(27, 29, 34);
        let panel_alt = Color32::from_rgb(35, 38, 45);
        let text = Color32::from_rgb(230, 232, 238);

        egui::TopBottomPanel::top("toolbar")
            .frame(egui::Frame::new().fill(Color32::from_rgb(20, 22, 26)).inner_margin(10.0))
            .show(ctx, |ui| {
                ui.horizontal_wrapped(|ui| {
                    ui.heading(egui::RichText::new("SOWZ AE").strong().color(accent));
                    ui.separator();
                    if ui.button("New").clicked() { self.new_project(); }
                    if ui.button("Open").clicked() { self.open_project(); }
                    if ui.button("Save").clicked() { self.save_project(); }
                    if ui.button("Save As").clicked() { self.save_project_as(); }
                    ui.separator();
                    if ui.add_enabled(self.engine.can_undo(), egui::Button::new("Undo")).clicked() {
                        if self.engine.undo() { self.status = "Undo".to_owned(); self.sync_inspector(); }
                    }
                    if ui.add_enabled(self.engine.can_redo(), egui::Button::new("Redo")).clicked() {
                        if self.engine.redo() { self.status = "Redo".to_owned(); self.sync_inspector(); }
                    }
                    ui.separator();
                    if ui.button("Add Track").clicked() { self.add_track(TrackKind::Video); }
                    if ui.button("Add Text Track").clicked() { self.add_track(TrackKind::Text); }
                    if ui.button("Add Audio Track").clicked() { self.add_track(TrackKind::Audio); }
                    if ui.button("Add Shape Track").clicked() { self.add_track(TrackKind::Shape); }
                    if ui.button("Add Adjustment Track").clicked() { self.add_track(TrackKind::Adjustment); }
                });
            });

        egui::TopBottomPanel::bottom("status")
            .frame(egui::Frame::new().fill(Color32::from_rgb(20, 22, 26)).inner_margin(7.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(&self.status).color(text));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(egui::RichText::new(format!("SOWZ AE {}", env!("CARGO_PKG_VERSION"))).weak());
                    });
                });
            });

        egui::SidePanel::left("project_panel")
            .resizable(true)
            .default_width(230.0)
            .min_width(185.0)
            .frame(egui::Frame::new().fill(panel).inner_margin(10.0))
            .show(ctx, |ui| {
                ui.heading("Project");
                ui.label(egui::RichText::new(&self.engine.project().name).color(text));
                ui.separator();
                ui.horizontal(|ui| {
                    if ui.button("＋ Layer").clicked() { self.add_layer(); }
                    if ui.button("Paste").clicked() { self.paste_layer(); }
                });
                let tracks = self.composition().map(|composition| composition.tracks.clone()).unwrap_or_default();
                egui::ScrollArea::vertical().show(ui, |ui| {
                    for track in tracks {
                        ui.horizontal(|ui| {
                            let marker = match track.kind {
                                TrackKind::Video => "V",
                                TrackKind::Audio => "A",
                                TrackKind::Text => "T",
                                TrackKind::Shape => "S",
                                TrackKind::Adjustment => "FX",
                            };
                            if ui.selectable_label(self.selected_track_id == track.id, format!("{marker}  {}", track.name)).clicked() {
                                self.selected_track_id = track.id;
                            }
                            let mut muted = track.muted;
                            if ui.checkbox(&mut muted, "M").changed() {
                                self.execute(Command::SetTrackFlags {
                                    track_id: track.id, muted, locked: track.locked, visible: track.visible,
                                });
                            }
                            let mut locked = track.locked;
                            if ui.checkbox(&mut locked, "Lock").changed() {
                                self.execute(Command::SetTrackFlags {
                                    track_id: track.id, muted: track.muted, locked, visible: track.visible,
                                });
                            }
                            let mut visible = track.visible;
                            if ui.checkbox(&mut visible, "Show").changed() {
                                self.execute(Command::SetTrackFlags {
                                    track_id: track.id, muted: track.muted, locked: track.locked, visible,
                                });
                            }
                        });
                        for layer in &track.layers {
                            ui.horizontal(|ui| {
                                ui.add_space(18.0);
                                if ui.selectable_label(self.selected_layer_id == Some(layer.id), &layer.name).clicked() {
                                    self.selected_track_id = track.id;
                                    self.selected_layer_id = Some(layer.id);
                                    self.sync_inspector();
                                }
                            });
                        }
                    }
                    ui.separator();
                    ui.heading("Media");
                    ui.horizontal_wrapped(|ui| {
                        if ui.button("Import Media").clicked() { self.import_media(); }
                        if ui.button("Add to Timeline").clicked() { self.add_asset_to_timeline(); }
                    });
                    let assets = self.engine.project().assets.clone();
                    for asset in assets {
                        let label = format!("{}  ·  {:?}", asset.name, asset.kind);
                        if ui.selectable_label(self.selected_asset_id == Some(asset.id), label).clicked() {
                            self.selected_asset_id = Some(asset.id);
                        }
                    }
                });
            });

        egui::SidePanel::right("inspector")
            .resizable(true)
            .default_width(270.0)
            .min_width(235.0)
            .frame(egui::Frame::new().fill(panel).inner_margin(10.0))
            .show(ctx, |ui| {
                ui.heading("Inspector");
                let Some(layer) = self.selected_layer() else {
                    ui.label("Select a layer to edit its properties.");
                    return;
                };
                ui.label(egui::RichText::new(format!("{:?}", layer.kind)).weak());
                ui.label("Layer name");
                ui.text_edit_singleline(&mut self.name_draft);
                ui.separator();
                ui.label("Timing (frames)");
                ui.add(egui::DragValue::new(&mut self.start_draft).prefix("Start "));
                ui.add(egui::DragValue::new(&mut self.duration_draft).prefix("Duration ").range(1..=1_000_000));
                ui.separator();
                ui.label("Transform");
                ui.add(egui::DragValue::new(&mut self.transform_draft.position_x).speed(1.0).prefix("X "));
                ui.add(egui::DragValue::new(&mut self.transform_draft.position_y).speed(1.0).prefix("Y "));
                ui.add(egui::DragValue::new(&mut self.transform_draft.scale_x).speed(0.01).prefix("Scale X "));
                ui.add(egui::DragValue::new(&mut self.transform_draft.scale_y).speed(0.01).prefix("Scale Y "));
                ui.add(egui::DragValue::new(&mut self.transform_draft.rotation_degrees).speed(0.5).prefix("Rotation "));
                ui.add(egui::Slider::new(&mut self.transform_draft.opacity, 0.0..=1.0).text("Opacity"));
                if ui.button("Apply Properties").clicked() { self.apply_inspector(); }
                ui.separator();
                ui.label("Animation keyframes");
                ui.horizontal_wrapped(|ui| {
                    if ui.button("K Position X").clicked() { self.add_numeric_keyframe("position_x"); }
                    if ui.button("K Position Y").clicked() { self.add_numeric_keyframe("position_y"); }
                    if ui.button("K Scale X").clicked() { self.add_numeric_keyframe("scale_x"); }
                    if ui.button("K Scale Y").clicked() { self.add_numeric_keyframe("scale_y"); }
                    if ui.button("K Rotation").clicked() { self.add_numeric_keyframe("rotation_degrees"); }
                    if ui.button("K Opacity").clicked() { self.add_numeric_keyframe("opacity"); }
                });
                if layer.keyframes.is_empty() {
                    ui.label(egui::RichText::new("No keyframes on this layer").weak());
                } else {
                    egui::ScrollArea::vertical().max_height(105.0).show(ui, |ui| {
                        for keyframe in &layer.keyframes {
                            ui.horizontal(|ui| {
                                ui.label(format!("f{}  {} = {:?}", keyframe.frame, keyframe.property, keyframe.value));
                                if ui.small_button("×").clicked() {
                                    self.execute(Command::RemoveKeyframe { layer_id: layer.id, keyframe_id: keyframe.id });
                                }
                            });
                        }
                    });
                }
                ui.separator();
                ui.label("Layer operations");
                ui.horizontal_wrapped(|ui| {
                    if ui.button("Copy").clicked() { self.copy_selected(); }
                    if ui.button("Duplicate").clicked() { self.duplicate_selected(); }
                    if ui.button("Split at Playhead").clicked() { self.split_selected(); }
                    if ui.button("Remove").clicked() { self.remove_selected(); }
                });
                ui.horizontal(|ui| {
                    if ui.button("Move Up").clicked() { self.reorder_selected(-1); }
                    if ui.button("Move Down").clicked() { self.reorder_selected(1); }
                });
            });

        egui::TopBottomPanel::bottom("timeline")
            .resizable(true)
            .default_height(285.0)
            .min_height(180.0)
            .frame(egui::Frame::new().fill(panel_alt).inner_margin(10.0))
            .show(ctx, |ui| {
                let Some(composition) = self.composition().cloned() else {
                    ui.label("No composition");
                    return;
                };
                ui.horizontal(|ui| {
                    ui.heading("Timeline");
                    ui.separator();
                    ui.label(format!("{} × {}  |  {}/{} fps", composition.width, composition.height, composition.fps_num, composition.fps_den));
                    ui.separator();
                    ui.label("Playhead");
                    ui.add(egui::Slider::new(&mut self.playhead_frame, 0..=composition.duration_frames).show_value(true));
                });
                ui.horizontal_wrapped(|ui| {
                    if ui.button("Add Layer to Selected Track").clicked() { self.add_layer(); }
                    if ui.button("Split").clicked() { self.split_selected(); }
                    if ui.button("Duplicate").clicked() { self.duplicate_selected(); }
                    if ui.button("Copy").clicked() { self.copy_selected(); }
                    if ui.button("Paste").clicked() { self.paste_layer(); }
                });
                ui.separator();

                egui::ScrollArea::both().auto_shrink([false, false]).show(ui, |ui| {
                    for track in composition.tracks {
                        ui.horizontal(|ui| {
                            ui.add_sized(vec2(125.0, 34.0), egui::Label::new(&track.name));
                            let width = ui.available_width().max(240.0);
                            let (rect, _) = ui.allocate_exact_size(vec2(width, 34.0), Sense::hover());
                            let painter = ui.painter_at(rect);
                            painter.rect_filled(rect, 4, Color32::from_rgb(25, 27, 32));
                            let scale = rect.width() / composition.duration_frames.max(1) as f32;
                            for layer in &track.layers {
                                let left = rect.left() + layer.start_frame as f32 * scale;
                                let right = rect.left() + layer.start_frame.saturating_add(layer.duration_frames).min(composition.duration_frames) as f32 * scale;
                                let block = Rect::from_min_max(
                                    pos2(left, rect.top() + 4.0),
                                    pos2(right.max(left + 5.0).min(rect.right()), rect.bottom() - 4.0),
                                );
                                let selected = self.selected_layer_id == Some(layer.id);
                                let fill = if selected { accent } else { Color32::from_rgb(74, 104, 135) };
                                painter.rect_filled(block, 4, fill);
                                painter.rect_stroke(block, 4, Stroke::new(1.0, Color32::from_rgb(15, 17, 20)), StrokeKind::Inside);
                                painter.text(block.left_center() + vec2(6.0, 0.0), Align2::LEFT_CENTER, &layer.name, FontId::proportional(11.0), Color32::WHITE);
                                let response = ui.interact(block, Id::new(("timeline_layer", layer.id)), Sense::click());
                                if response.clicked() {
                                    self.selected_track_id = track.id;
                                    self.selected_layer_id = Some(layer.id);
                                    self.sync_inspector();
                                }
                            }
                            let playhead_x = rect.left() + self.playhead_frame as f32 * scale;
                            painter.line_segment([pos2(playhead_x, rect.top()), pos2(playhead_x, rect.bottom())], Stroke::new(2.0, accent));
                        });
                    }
                });
            });

        egui::CentralPanel::default()
            .frame(egui::Frame::new().fill(Color32::from_rgb(17, 19, 23)).inner_margin(16.0))
            .show(ctx, |ui| {
                ui.heading("Viewer");
                ui.label(egui::RichText::new("Composition workspace").weak());
                ui.add_space(12.0);
                let available = ui.available_size();
                let canvas_size = vec2(available.x.max(180.0), (available.y - 100.0).max(100.0));
                let (canvas, _) = ui.allocate_exact_size(canvas_size, Sense::hover());
                ui.painter().rect_filled(canvas, 5, Color32::from_rgb(10, 11, 14));
                ui.painter().rect_stroke(canvas, 5, Stroke::new(1.0, Color32::from_rgb(55, 59, 67)), StrokeKind::Inside);
                ui.painter().text(
                    canvas.center(),
                    Align2::CENTER_CENTER,
                    "MEDIA PREVIEW / RENDER ENGINE NOT CONNECTED",
                    FontId::proportional(15.0),
                    Color32::from_rgb(175, 180, 190),
                );
                let active_count = self.composition().map(|composition| {
                    composition.tracks.iter().flat_map(|track| track.layers.iter())
                        .filter(|layer| layer.enabled
                            && layer.start_frame <= self.playhead_frame
                            && self.playhead_frame < layer.start_frame.saturating_add(layer.duration_frames))
                        .count()
                }).unwrap_or(0);
                ui.label(format!("Frame {}  ·  {active_count} active layer(s)", self.playhead_frame));
                ui.label(egui::RichText::new("Timeline editing and project saving are active. Media decoding, effects preview and final export are not yet implemented.").weak());
            });

        ctx.request_repaint_after(std::time::Duration::from_millis(100));
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Glow,
        viewport: egui::ViewportBuilder::default()
            .with_title("SOWZ AE")
            .with_inner_size([1440.0, 900.0])
            .with_min_inner_size([1050.0, 680.0]),
        ..Default::default()
    };
    eframe::run_native(
        "SOWZ AE",
        options,
        Box::new(|_creation_context| {
            SowzAeApp::new().map(|app| Box::new(app) as Box<dyn eframe::App>)
        }),
    )
}
