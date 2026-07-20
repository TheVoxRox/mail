use std::path::PathBuf;
use std::sync::Mutex;

use tauri::{LogicalSize, WebviewUrl, WebviewWindowBuilder};
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

            let work_area = app.primary_monitor().ok().flatten().map(|monitor| {
                monitor
                    .work_area()
                    .size
                    .to_logical::<f64>(monitor.scale_factor())
            });
            let (restore_size, min_size) = window_sizes_for(work_area);
            log::info!(
                "Window sizing (logical px): restore {}x{}, min {}x{}",
                restore_size.width,
                restore_size.height,
                min_size.width,
                min_size.height
            );

            // Created with a "loading" title (cs = default locale) so a screen
            // reader reading the window name on focus — before the webview even
            // hydrates — already conveys that the app is starting. The frontend
            // ($lib/windowTitle via the root layout) switches it to the plain
            // app name once boot reaches 'ready'/'failed'.
            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("VoxRox Mail – načítání…")
                .inner_size(restore_size.width, restore_size.height)
                .min_inner_size(min_size.width, min_size.height)
                .resizable(true)
                .maximized(true)
                .data_directory(webview_dir.clone())
                .build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

/// Restored (un-maximized) and minimum inner window size in logical px,
/// clamped to the monitor's work area. The 1280×800 / 1024×768 defaults
/// mirror the layout baselines in `viewport.functional.e2e.ts`, but they are
/// logical pixels: at 150–200 % display scaling (typical low-vision setups)
/// the 1024×768 minimum exceeds the whole screen, so an un-maximized window
/// would overflow past the taskbar with no way to shrink it. `None` (no
/// primary monitor detected) keeps the defaults.
fn window_sizes_for(work_area: Option<LogicalSize<f64>>) -> (LogicalSize<f64>, LogicalSize<f64>) {
    const RESTORE: LogicalSize<f64> = LogicalSize {
        width: 1280.0,
        height: 800.0,
    };
    const MIN: LogicalSize<f64> = LogicalSize {
        width: 1024.0,
        height: 768.0,
    };

    let Some(area) = work_area else {
        return (RESTORE, MIN);
    };

    // The work area bounds the *outer* frame; keep room for the title bar and
    // resize borders (Win11 ≈ 40 logical px vertically, 16 horizontally). The
    // floor guards against nonsensical monitor info.
    let max_inner_width = (area.width - 16.0).max(320.0);
    let max_inner_height = (area.height - 40.0).max(240.0);

    let clamp = |size: LogicalSize<f64>| LogicalSize {
        width: size.width.min(max_inner_width),
        height: size.height.min(max_inner_height),
    };
    (clamp(RESTORE), clamp(MIN))
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
async fn install_pending_update(
    pending: tauri::State<'_, PendingUpdate>,
    expected_version: String,
) -> Result<(), String> {
    let update = pending
        .0
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .clone()
        .ok_or_else(|| "no update is pending installation".to_string())?;

    // The webview names the version its prompt showed. The pending slot is
    // replaced by every check (cleared on a no-update result), so without
    // this pin a stale prompt could install a different build than the one
    // the user approved — failing is better.
    if update.version != expected_version {
        return Err(format!(
            "pending update is {}, but the prompt offered {}",
            update.version, expected_version
        ));
    }

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
    use super::{beta_endpoint_override, data_root_under, window_sizes_for, UpdateMetadata};
    use std::path::{Path, PathBuf};
    use tauri::LogicalSize;

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
    fn window_sizes_keep_defaults_on_a_large_work_area() {
        let (restore, min) = window_sizes_for(Some(LogicalSize {
            width: 1904.0,
            height: 1040.0,
        }));
        assert_eq!(
            restore,
            LogicalSize {
                width: 1280.0,
                height: 800.0
            }
        );
        assert_eq!(
            min,
            LogicalSize {
                width: 1024.0,
                height: 768.0
            }
        );
    }

    #[test]
    fn window_sizes_keep_defaults_without_a_monitor() {
        assert_eq!(
            window_sizes_for(None),
            window_sizes_for(Some(LogicalSize {
                width: 4000.0,
                height: 4000.0,
            }))
        );
    }

    #[test]
    fn window_sizes_clamp_restore_height_on_a_125_percent_1080p_display() {
        // 1920×1080 at 125 % scaling: logical work area ≈ 1536×824 with the
        // taskbar deducted. Only the restored height overflows (by ~16 px).
        let (restore, min) = window_sizes_for(Some(LogicalSize {
            width: 1536.0,
            height: 824.0,
        }));
        assert_eq!(
            restore,
            LogicalSize {
                width: 1280.0,
                height: 784.0
            }
        );
        assert_eq!(
            min,
            LogicalSize {
                width: 1024.0,
                height: 768.0
            }
        );
    }

    #[test]
    fn window_sizes_clamp_everything_on_a_200_percent_1080p_display() {
        // 1920×1080 at 200 % scaling: logical work area ≈ 960×516 — smaller
        // than the 1024×768 minimum in both axes.
        let (restore, min) = window_sizes_for(Some(LogicalSize {
            width: 960.0,
            height: 516.0,
        }));
        assert_eq!(
            restore,
            LogicalSize {
                width: 944.0,
                height: 476.0
            }
        );
        assert_eq!(min, restore);
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
