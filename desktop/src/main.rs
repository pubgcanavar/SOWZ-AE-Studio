#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

// SOWZ AE uses the reviewed, pinned EffectCraft engine crates for media decoding,
// compositing, effects, keyframes, rendering and After Effects project interchange.
// The desktop shell and app identity remain SOWZ AE; upstream notices are in
// THIRD_PARTY_NOTICES.md and third_party/effectcraft/.

use std::path::Path;

use eframe::egui;
use effectcraft_ui_egui::EffectcraftApp;

fn install_file_dialogs(app: &mut EffectcraftApp) {
    app.hooks.pick_files = Some(Box::new(|extensions: &[&str]| {
        rfd::FileDialog::new()
            .add_filter("Media and project files", extensions)
            .pick_files()
            .unwrap_or_default()
            .into_iter()
            .map(|path| path.to_string_lossy().into_owned())
            .collect()
    }));

    app.hooks.pick_save = Some(Box::new(|name: &str| {
        rfd::FileDialog::new()
            .add_filter("SOWZ AE Project", &["ecproj"])
            .set_file_name(name)
            .save_file()
            .map(|path| path.to_string_lossy().into_owned())
    }));

    app.hooks.pick_open_project = Some(Box::new(|| {
        rfd::FileDialog::new()
            .add_filter("SOWZ AE Project", &["ecproj", "ecprojx"])
            .pick_file()
            .map(|path| path.to_string_lossy().into_owned())
    }));

    app.hooks.pick_folder = Some(Box::new(|| {
        rfd::FileDialog::new()
            .pick_folder()
            .map(|path| path.to_string_lossy().into_owned())
    }));

    app.hooks.pick_save_file = Some(Box::new(|name: &str, extension: &str| {
        let path = Path::new(name);
        let mut dialog = if extension.is_empty() {
            rfd::FileDialog::new()
        } else {
            rfd::FileDialog::new().add_filter(extension, &[extension])
        };
        if let Some(directory) = path.parent().filter(|directory| directory.is_dir()) {
            dialog = dialog.set_directory(directory);
        }
        let file_name = path
            .file_name()
            .map(|value| value.to_string_lossy().into_owned())
            .unwrap_or_default();
        dialog
            .set_file_name(file_name)
            .save_file()
            .map(|path| path.to_string_lossy().into_owned())
    }));
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("SOWZ AE")
            .with_inner_size([1600.0, 960.0])
            .with_min_inner_size([1080.0, 700.0]),
        ..Default::default()
    };

    eframe::run_native(
        "SOWZ AE",
        options,
        Box::new(|_creation_context| {
            let mut session = effectcraft_host::session();
            session.check_footage_on_open = true;
            session.autosave.background = true;
            if let Some(config_dir) = effectcraft_host::config_dir() {
                let plugin_dir = config_dir.join(effectcraft_engine::commands::PLUGINS_DIR);
                if plugin_dir.is_dir() {
                    let _ = session.execute(
                        "effect.plugins.load",
                        serde_json::json!({"folder": plugin_dir.to_string_lossy()}),
                    );
                    let _ = session.execute("library.loadInstalled", serde_json::json!({}));
                }
                session.config = Some(std::sync::Arc::new(
                    effectcraft_engine::config::DirConfig::new(config_dir),
                ));
            }
            session.load_settings();

            let mut app = EffectcraftApp::new(session);
            app.set_startup_update_check(false);
            app.ui.start_screen = true;
            app.ui.status =
                "SOWZ AE 0.2.0 • After Effects project import, media, effects and render workspace"
                    .to_owned();
            install_file_dialogs(&mut app);

            Ok(Box::new(app))
        }),
    )
}
