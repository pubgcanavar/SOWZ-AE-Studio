use std::collections::BTreeMap;

use crate::{
    Command, EditorEngine, Interpolation, LayerKind, LoadedPackage, Project,
    PropertyValue, TrackKind, load_package, save_package,
};

fn project() -> Project {
    Project::new("SOWZ Test", 1920, 1080, 30, 1, 300)
}

#[test]
fn empty_project_is_valid() {
    assert!(project().validate().is_ok());
}

#[test]
fn timeline_edits_are_undoable_and_redoable() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 0,
        duration_frames: 90,
    }).expect("layer should be added");

    assert_eq!(engine.project().compositions[0].tracks[0].layers.len(), 1);
    assert!(engine.undo());
    assert!(engine.project().compositions[0].tracks[0].layers.is_empty());
    assert!(engine.redo());
    assert_eq!(engine.project().compositions[0].tracks[0].layers.len(), 1);
}

#[test]
fn invalid_edit_does_not_change_live_project() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 0,
        duration_frames: 90,
    }).expect("layer should be added");

    let before = engine.project().clone();
    let layer_id = before.compositions[0].tracks[0].layers[0].id;
    assert!(engine.execute(Command::TrimLayer {
        layer_id,
        new_duration_frames: 0,
    }).is_err());
    assert_eq!(*engine.project(), before);
}

#[test]
fn keyframes_are_stored_in_frame_order() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Animated title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 0,
        duration_frames: 90,
    }).expect("layer should be added");
    let layer_id = engine.project().compositions[0].tracks[0].layers[0].id;

    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 45,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(0.5),
        interpolation: Interpolation::Bezier,
    }).expect("keyframe should be added");
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 10,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(1.0),
        interpolation: Interpolation::Linear,
    }).expect("earlier keyframe should be added");

    let keyframes = &engine.project().compositions[0].tracks[0].layers[0].keyframes;
    assert_eq!(keyframes[0].frame, 10);
    assert_eq!(keyframes[1].frame, 45);
}

#[test]
fn project_package_round_trips_and_keeps_extra_files() {
    let directory = tempfile::tempdir().expect("temporary directory");
    let path = directory.path().join("test.sowzae");
    let mut files = BTreeMap::new();
    files.insert("assets/images/card.bin".to_owned(), vec![1, 2, 3, 4, 5]);
    files.insert("import_report.json".to_owned(), br#"{"warnings":[]}"#.to_vec());
    let expected = project();

    save_package(&path, &LoadedPackage {
        project: expected.clone(),
        files,
    }).expect("package should save");

    let loaded = load_package(&path).expect("package should load");
    assert_eq!(loaded.project, expected);
    assert_eq!(loaded.files.get("assets/images/card.bin"), Some(&vec![1, 2, 3, 4, 5]));
    assert!(loaded.files.contains_key("import_report.json"));
}

#[test]
fn unsafe_embedded_paths_are_rejected() {
    let mut value = project();
    value.assets.push(crate::Asset {
        id: 4,
        name: "bad".to_owned(),
        kind: crate::AssetKind::Image,
        source: crate::AssetSource::Embedded { path: "../outside.png".to_owned() },
    });
    value.next_id = 5;
    assert!(value.validate().is_err());
}

#[test]
fn track_command_adds_a_real_track() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddTrack {
        composition_id: 2,
        name: "Audio 1".to_owned(),
        kind: TrackKind::Audio,
    }).expect("track should be added");
    assert_eq!(engine.project().compositions[0].tracks.len(), 2);
}


#[test]
fn split_layer_keeps_timing_and_shifts_right_side_keyframes() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Motion clip".to_owned(),
        kind: LayerKind::Footage,
        asset_id: None,
        start_frame: 10,
        duration_frames: 100,
    }).expect("layer should be added");
    let layer_id = engine.project().compositions[0].tracks[0].layers[0].id;
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 10,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(0.2),
        interpolation: Interpolation::Linear,
    }).expect("first keyframe should be added");
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 50,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(0.7),
        interpolation: Interpolation::Bezier,
    }).expect("split boundary keyframe should be added");
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 80,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(1.0),
        interpolation: Interpolation::EaseOut,
    }).expect("last keyframe should be added");

    engine.execute(Command::SplitLayer { layer_id, at_frame: 60 })
        .expect("split should succeed");

    let layers = &engine.project().compositions[0].tracks[0].layers;
    assert_eq!(layers.len(), 2);
    assert_eq!(layers[0].start_frame, 10);
    assert_eq!(layers[0].duration_frames, 50);
    assert_eq!(layers[1].start_frame, 60);
    assert_eq!(layers[1].duration_frames, 50);
    assert_eq!(layers[0].keyframes.iter().map(|item| item.frame).collect::<Vec<_>>(), vec![10, 50]);
    assert_eq!(layers[1].keyframes.iter().map(|item| item.frame).collect::<Vec<_>>(), vec![0, 30]);
    assert_ne!(layers[0].keyframes[1].id, layers[1].keyframes[0].id);
}

#[test]
fn duplicate_layer_assigns_new_layer_and_keyframe_ids() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 0,
        duration_frames: 90,
    }).expect("layer should be added");
    let layer_id = engine.project().compositions[0].tracks[0].layers[0].id;
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 20,
        property: "position_x".to_owned(),
        value: PropertyValue::Number(250.0),
        interpolation: Interpolation::Bezier,
    }).expect("keyframe should be added");
    engine.execute(Command::DuplicateLayer { layer_id, new_start_frame: 100 })
        .expect("duplicate should succeed");

    let layers = &engine.project().compositions[0].tracks[0].layers;
    assert_eq!(layers.len(), 2);
    assert_eq!(layers[1].start_frame, 100);
    assert_ne!(layers[0].id, layers[1].id);
    assert_ne!(layers[0].keyframes[0].id, layers[1].keyframes[0].id);
    assert_eq!(layers[0].keyframes[0].value, layers[1].keyframes[0].value);
}

#[test]
fn reorder_layer_changes_track_order_and_is_undoable() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    for name in ["One", "Two", "Three"] {
        engine.execute(Command::AddLayer {
            composition_id: 2,
            track_id: 3,
            name: name.to_owned(),
            kind: LayerKind::Text,
            asset_id: None,
            start_frame: 0,
            duration_frames: 90,
        }).expect("layer should be added");
    }
    let original_ids = engine.project().compositions[0].tracks[0].layers
        .iter().map(|layer| layer.id).collect::<Vec<_>>();
    engine.execute(Command::ReorderLayer { layer_id: original_ids[2], new_index: 0 })
        .expect("reorder should succeed");

    let reordered = engine.project().compositions[0].tracks[0].layers
        .iter().map(|layer| layer.id).collect::<Vec<_>>();
    assert_eq!(reordered, vec![original_ids[2], original_ids[0], original_ids[1]]);
    assert!(engine.undo());
    let restored = engine.project().compositions[0].tracks[0].layers
        .iter().map(|layer| layer.id).collect::<Vec<_>>();
    assert_eq!(restored, original_ids);
}


#[test]
fn layer_clipboard_round_trips_and_pastes_with_fresh_identifiers() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Clipboard title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 5,
        duration_frames: 80,
    }).expect("layer should be added");
    let layer_id = engine.project().compositions[0].tracks[0].layers[0].id;
    engine.execute(Command::AddEffect {
        layer_id,
        effect_type: "SOWZ Glow".to_owned(),
    }).expect("effect should be added");
    engine.execute(Command::PutKeyframe {
        layer_id,
        frame: 30,
        property: "opacity".to_owned(),
        value: PropertyValue::Number(0.65),
        interpolation: Interpolation::Bezier,
    }).expect("keyframe should be added");

    let clipboard = engine.copy_layer(layer_id).expect("layer should copy");
    let encoded = clipboard.encode().expect("clipboard should encode");
    let decoded = crate::LayerClipboard::decode(&encoded).expect("clipboard should decode");
    engine.paste_layer(2, 3, decoded, 150).expect("layer should paste");

    let layers = &engine.project().compositions[0].tracks[0].layers;
    assert_eq!(layers.len(), 2);
    assert_eq!(layers[1].start_frame, 150);
    assert_ne!(layers[0].id, layers[1].id);
    assert_ne!(layers[0].effects[0].id, layers[1].effects[0].id);
    assert_ne!(layers[0].keyframes[0].id, layers[1].keyframes[0].id);
    assert_eq!(layers[0].keyframes[0].value, layers[1].keyframes[0].value);
}

#[test]
fn clipboard_rejects_unknown_schema_and_oversized_payloads() {
    let layer = crate::Layer {
        id: 1,
        name: "Clipboard".to_owned(),
        kind: LayerKind::Text,
        start_frame: 0,
        duration_frames: 10,
        asset_id: None,
        enabled: true,
        transform: crate::Transform::default(),
        effects: Vec::new(),
        keyframes: Vec::new(),
    };
    let mut clipboard = crate::LayerClipboard::new(&layer);
    clipboard.schema_version = 999;
    let bytes = serde_json::to_vec(&clipboard).expect("test fixture should encode");
    assert!(crate::LayerClipboard::decode(&bytes).is_err());
    assert!(crate::LayerClipboard::decode(&vec![0; crate::LayerClipboard::MAX_BYTES + 1]).is_err());
}


#[test]
fn inspector_property_update_is_atomic_and_undoable() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "Title".to_owned(),
        kind: LayerKind::Text,
        asset_id: None,
        start_frame: 0,
        duration_frames: 90,
    }).expect("layer should be added");
    let layer_id = engine.project().compositions[0].tracks[0].layers[0].id;
    let before = engine.project().clone();

    assert!(engine.execute(Command::SetLayerProperties {
        layer_id,
        name: "Invalid timing".to_owned(),
        start_frame: 20,
        duration_frames: 0,
        transform: crate::Transform::default(),
    }).is_err());
    assert_eq!(*engine.project(), before);

    engine.execute(Command::SetLayerProperties {
        layer_id,
        name: "Updated title".to_owned(),
        start_frame: 12,
        duration_frames: 60,
        transform: crate::Transform {
            position_x: 220.0,
            opacity: 0.75,
            ..crate::Transform::default()
        },
    }).expect("valid property update should succeed");

    let layer = &engine.project().compositions[0].tracks[0].layers[0];
    assert_eq!(layer.name, "Updated title");
    assert_eq!(layer.start_frame, 12);
    assert_eq!(layer.duration_frames, 60);
    assert_eq!(layer.transform.position_x, 220.0);
    assert_eq!(layer.transform.opacity, 0.75);

    assert!(engine.undo());
    assert_eq!(*engine.project(), before);
}


#[test]
fn linked_media_asset_can_be_added_and_referenced_by_a_layer() {
    let mut engine = EditorEngine::new(project()).expect("valid test project");
    let asset_id = engine.project().next_id;
    engine.execute(Command::AddAsset {
        name: "sample-video.mp4".to_owned(),
        kind: crate::AssetKind::Video,
        source: crate::AssetSource::Linked { uri: "sample-video.mp4".to_owned() },
    }).expect("asset should be added");
    engine.execute(Command::AddLayer {
        composition_id: 2,
        track_id: 3,
        name: "sample-video.mp4".to_owned(),
        kind: LayerKind::Footage,
        asset_id: Some(asset_id),
        start_frame: 0,
        duration_frames: 120,
    }).expect("footage layer should be added");

    let value = engine.project();
    assert_eq!(value.assets.len(), 1);
    assert_eq!(value.assets[0].id, asset_id);
    assert_eq!(value.compositions[0].tracks[0].layers[0].asset_id, Some(asset_id));
    assert!(value.validate().is_ok());
}
