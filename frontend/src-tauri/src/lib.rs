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
    let base =
        dirs::data_local_dir().expect("local data directory is unavailable on this platform");
    data_root_under(base, &suffix)
}

/// Builds the `<base>/VoxRox/Mail[suffix]` data root. Split out from
/// `resolve_data_root` so the vendor/suffix layout can be unit-tested without
/// touching the process environment or the OS local-data directory.
fn data_root_under(base: PathBuf, suffix: &str) -> PathBuf {
    base.join("VoxRox").join(format!("Mail{suffix}"))
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

#[cfg(test)]
mod tests {
    use super::data_root_under;
    use std::path::{Path, PathBuf};

    #[test]
    fn data_root_without_suffix_is_the_production_folder() {
        let root = data_root_under(PathBuf::from("/base"), "");
        assert_eq!(root, Path::new("/base").join("VoxRox").join("Mail"));
    }

    #[test]
    fn dev_suffix_yields_an_isolated_sibling_of_production() {
        let base = PathBuf::from("/base");
        let prod = data_root_under(base.clone(), "");
        let dev = data_root_under(base, ".dev");

        assert_eq!(dev, Path::new("/base").join("VoxRox").join("Mail.dev"));
        // Same parent (one vendor folder) but never the same directory, so a dev
        // run can never read or clobber the production install's data.
        assert_eq!(dev.parent(), prod.parent());
        assert_ne!(dev, prod);
    }
}
