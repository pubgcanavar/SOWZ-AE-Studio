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
