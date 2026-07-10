use std::path::PathBuf;
use std::sync::Mutex;

use tauri::{WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_log::{Target, TargetKind};
use tauri_plugin_updater::UpdaterExt;

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
        .manage(PendingUpdate(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            check_for_update,
            install_pending_update
        ])
        .setup(move |app| {
            log::info!("Mail Tauri application started");
            log::info!("Data root: {}", data_root.display());
            log::info!("Log directory: {}", log_dir.display());
            log::info!("WebView2 user data directory: {}", webview_dir.display());

            // Created with a "loading" title (cs = default locale) so a screen
            // reader reading the window name on focus — before the webview even
            // hydrates — already conveys that the app is starting. The frontend
            // ($lib/windowTitle via the root layout) switches it to the plain
            // app name once boot reaches 'ready'/'failed'.
            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("VoxRox Mail – načítání…")
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

/// The `Update` found by the last `check_for_update`, held so that
/// `install_pending_update` installs exactly what the prompt showed. Cloned
/// out (not taken) on install so a failed download can be retried; replaced
/// (or cleared) by every subsequent check, including after a channel switch.
struct PendingUpdate(Mutex<Option<tauri_plugin_updater::Update>>);

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateMetadata {
    version: String,
    current_version: String,
    date: Option<String>,
    body: Option<String>,
}

/// latest.json of the beta update channel (a moving GitHub release refreshed
/// by .github/workflows/beta-channel.yml). Compile-time overridable so CI can
/// move the URL without a source change; an empty value (an unset Actions var
/// still materializes as an empty env var) falls back to the default.
fn beta_updater_endpoint() -> &'static str {
    match option_env!("TAURI_UPDATER_BETA_ENDPOINT") {
        Some(url) if !url.is_empty() => url,
        _ => "https://github.com/TheVoxRox/mail/releases/download/beta/latest.json",
    }
}

/// Maps an update-channel name from the webview onto an optional endpoint
/// override. `stable` maps to `None` on purpose: the stable channel keeps the
/// endpoints baked into the updater plugin config, so the release config
/// remains their single source of truth. Only known channel names are
/// accepted — the webview can never supply a URL, keeping the endpoint set
/// pinned even if the renderer is compromised (signature pinning stays the
/// trust anchor either way).
fn beta_endpoint_override(channel: &str) -> Result<Option<&'static str>, String> {
    match channel {
        "stable" => Ok(None),
        "beta" => Ok(Some(beta_updater_endpoint())),
        other => Err(format!("unknown update channel: {other}")),
    }
}

#[tauri::command]
async fn check_for_update(
    app: tauri::AppHandle,
    pending: tauri::State<'_, PendingUpdate>,
    channel: String,
) -> Result<Option<UpdateMetadata>, String> {
    let mut builder = app.updater_builder();
    if let Some(endpoint) = beta_endpoint_override(&channel)? {
        let url = tauri::Url::parse(endpoint).map_err(|err| err.to_string())?;
        builder = builder.endpoints(vec![url]).map_err(|err| err.to_string())?;
    }

    let update = builder
        .build()
        .map_err(|err| err.to_string())?
        .check()
        .await
        .map_err(|err| err.to_string())?;

    let metadata = update.as_ref().map(|update| UpdateMetadata {
        version: update.version.clone(),
        current_version: update.current_version.clone(),
        date: update.date.map(|date| date.to_string()),
        body: update.body.clone(),
    });

    *pending.0.lock().unwrap_or_else(|err| err.into_inner()) = update;
    Ok(metadata)
}

#[tauri::command]
async fn install_pending_update(pending: tauri::State<'_, PendingUpdate>) -> Result<(), String> {
    let update = pending
        .0
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .clone()
        .ok_or_else(|| "no update is pending installation".to_string())?;

    update
        .download_and_install(|_, _| {}, || {})
        .await
        .map_err(|err| err.to_string())
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
    use super::{beta_endpoint_override, data_root_under, UpdateMetadata};
    use std::path::{Path, PathBuf};

    #[test]
    fn stable_channel_keeps_the_baked_endpoints() {
        assert_eq!(beta_endpoint_override("stable"), Ok(None));
    }

    #[test]
    fn beta_channel_overrides_with_a_valid_https_endpoint() {
        let endpoint = beta_endpoint_override("beta")
            .expect("beta must be a known channel")
            .expect("beta must override the endpoint");
        let url = tauri::Url::parse(endpoint).expect("beta endpoint must be a valid URL");
        assert_eq!(url.scheme(), "https");
        assert!(endpoint.ends_with("/latest.json"));
    }

    #[test]
    fn unknown_channels_are_rejected() {
        let err = beta_endpoint_override("nightly").expect_err("unknown channel must fail");
        assert!(err.contains("nightly"));
    }

    #[test]
    fn update_metadata_serializes_camel_case_for_the_webview() {
        let metadata = UpdateMetadata {
            version: "0.2.0-beta.1".into(),
            current_version: "0.1.0".into(),
            date: None,
            body: None,
        };
        let value = serde_json::to_value(metadata).expect("metadata must serialize");
        assert_eq!(value["version"], "0.2.0-beta.1");
        assert_eq!(value["currentVersion"], "0.1.0");
        assert!(value["date"].is_null());
    }

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
