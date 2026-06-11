use std::path::PathBuf;

use tauri::{WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_log::{Target, TargetKind};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let data_root = resolve_data_root();
    let log_dir = data_root.join("logs");
    let webview_dir = data_root.join("webview");

    tauri::Builder::default()
        .plugin(configure_log_plugin(log_dir.clone()))
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(move |app| {
            log::info!("Mail Tauri application started");
            log::info!("Data root: {}", data_root.display());
            log::info!("Log directory: {}", log_dir.display());
            log::info!("WebView2 user data directory: {}", webview_dir.display());

            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("VoxRox Mail")
                .inner_size(1280.0, 800.0)
                .min_inner_size(1024.0, 768.0)
                .resizable(true)
                .data_directory(webview_dir.clone())
                .build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

/// Resolves the unified `<vendor>/Mail[suffix]` data root under the OS-specific
/// local data directory. The `MAIL_DATA_SUFFIX` env var (typically `.dev` set by
/// `scripts/tauri-dev-with-env.mjs`) selects the dev sibling so that dev runs
/// stay isolated from a production install without forking the bundle identifier.
fn resolve_data_root() -> PathBuf {
    let suffix = std::env::var("MAIL_DATA_SUFFIX").unwrap_or_default();
    let folder = format!("Mail{suffix}");
    let base = dirs::data_local_dir()
        .expect("local data directory is unavailable on this platform");
    base.join("VoxRox").join(folder)
}

fn configure_log_plugin<R: tauri::Runtime>(log_dir: PathBuf) -> tauri::plugin::TauriPlugin<R> {
    let file_log = Target::new(TargetKind::Folder {
        path: log_dir,
        file_name: Some("mail-frontend".into()),
    });

    let targets = if cfg!(debug_assertions) {
        vec![Target::new(TargetKind::Stdout)]
    } else {
        vec![file_log]
    };

    tauri_plugin_log::Builder::default()
        .level(log::LevelFilter::Info)
        .targets(targets)
        .build()
}
