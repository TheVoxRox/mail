//! System-tray icon, its menu, and the close-to-tray behaviour of the main
//! window.
//!
//! The app can outlive its window: closing the window hides it instead of
//! quitting, so sync and new-mail notifications keep running. That is the
//! whole point of the tray — a mail client that only notifies while its window
//! is open is not doing the job — but it is also a trap if the user reads the
//! close button as "quit", which is why the behaviour is a setting
//! (`mail.closeAction`, Settings → Appearance) rather than a decision made for
//! them. The webview owns that setting and pushes it here via
//! [`set_close_behavior`]; nothing is persisted on the Rust side.
//!
//! Menu labels come from the webview too ([`configure_tray`]) — the i18n
//! bundles live there, and a second copy of the strings in Rust would be a
//! second thing to keep translated. Until the webview has loaded its locale
//! there is a tray icon with no menu, which is the honest state: the window is
//! visible anyway during boot.

use std::sync::Mutex;

use serde::Deserialize;
use tauri::menu::{Menu, MenuEvent, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, Runtime, WindowEvent};

/// Tray icon id, and the event name the webview listens on for menu actions
/// it has to handle itself (navigating to the composer, triggering a sync).
const TRAY_ID: &str = "main";
pub const TRAY_ACTION_EVENT: &str = "tray://action";

const MENU_OPEN: &str = "open";
const MENU_COMPOSE: &str = "compose";
const MENU_SYNC: &str = "sync";
const MENU_QUIT: &str = "quit";

/// Whether closing the main window hides it (true) or quits the app (false).
///
/// Defaults to hiding: the tray exists so the app can keep receiving mail, and
/// a user who never opens Settings gets the behaviour the feature was added
/// for. The webview overwrites this from the stored preference during boot.
pub struct CloseBehavior(Mutex<bool>);

impl Default for CloseBehavior {
    fn default() -> Self {
        Self(Mutex::new(true))
    }
}

impl CloseBehavior {
    fn hides_to_tray(&self) -> bool {
        *self.0.lock().unwrap_or_else(|err| err.into_inner())
    }

    fn set(&self, hide_to_tray: bool) {
        *self.0.lock().unwrap_or_else(|err| err.into_inner()) = hide_to_tray;
    }
}

/// Localized strings for the tray, supplied by the webview.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrayLabels {
    open: String,
    compose: String,
    sync: String,
    quit: String,
    /// Tooltip shown on hover — already formatted (and already carrying the
    /// unread count, if any), because pluralisation is the webview's job.
    tooltip: String,
}

/// Creates the tray icon with no menu yet. Called once during setup so the
/// icon is present from the start; [`configure_tray`] fills in the menu as
/// soon as the webview knows which locale to render it in.
pub fn create<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let icon = app
        .default_window_icon()
        .ok_or_else(|| tauri::Error::AssetNotFound("default window icon".into()))?
        .clone();

    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        // Left click belongs to "show me the app"; the menu is the right-click
        // gesture, matching every other Windows tray icon.
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .on_menu_event(handle_menu_event)
        .build(app)?;

    Ok(())
}

/// Builds (or rebuilds) the tray menu and tooltip from localized strings.
/// Rebuilding is what a language switch does — the menu is native, so its
/// labels cannot be reactive the way the webview's are.
#[tauri::command]
pub fn configure_tray(app: AppHandle, labels: TrayLabels) -> Result<(), String> {
    let tray = app
        .tray_by_id(TRAY_ID)
        .ok_or_else(|| "tray icon is not available".to_string())?;

    let items = [
        MenuItem::with_id(&app, MENU_OPEN, &labels.open, true, None::<&str>),
        MenuItem::with_id(&app, MENU_COMPOSE, &labels.compose, true, None::<&str>),
        MenuItem::with_id(&app, MENU_SYNC, &labels.sync, true, None::<&str>),
        MenuItem::with_id(&app, MENU_QUIT, &labels.quit, true, None::<&str>),
    ];
    let mut built = Vec::with_capacity(items.len());
    for item in items {
        built.push(item.map_err(|err| err.to_string())?);
    }
    let refs: Vec<&dyn tauri::menu::IsMenuItem<_>> = built
        .iter()
        .map(|item| item as &dyn tauri::menu::IsMenuItem<_>)
        .collect();

    let menu = Menu::with_items(&app, &refs).map_err(|err| err.to_string())?;
    tray.set_menu(Some(menu)).map_err(|err| err.to_string())?;
    tray.set_tooltip(Some(&labels.tooltip))
        .map_err(|err| err.to_string())?;
    Ok(())
}

/// Updates only the tooltip — the unread count changes far more often than the
/// locale, and rebuilding the menu for it would recreate native menu items on
/// every sync.
#[tauri::command]
pub fn set_tray_tooltip(app: AppHandle, tooltip: String) -> Result<(), String> {
    let tray = app
        .tray_by_id(TRAY_ID)
        .ok_or_else(|| "tray icon is not available".to_string())?;
    tray.set_tooltip(Some(&tooltip)).map_err(|err| err.to_string())
}

/// Pushes the user's Settings → Appearance choice down to the window-close
/// handler.
#[tauri::command]
pub fn set_close_behavior(app: AppHandle, hide_to_tray: bool) {
    app.state::<CloseBehavior>().set(hide_to_tray);
}

/// Brings the main window back from the tray. Also invoked by the
/// single-instance plugin, so a second launch surfaces the running app instead
/// of failing on an occupied backend port.
pub fn show_main_window<R: Runtime>(app: &AppHandle<R>) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    // A window hidden from a maximized state comes back minimized on Windows
    // unless it is explicitly unminimized first.
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
}

/// Hides the window instead of closing it when the user asked for that.
///
/// The webview keeps running while hidden — that is what keeps the SSE stream,
/// the sync scheduler and the new-mail notifications alive. Nothing here
/// touches the sidecar: it is stopped by the `beforeunload` hook on a real
/// quit, and by the backend's parent-death watchdog if the process goes away
/// without one.
pub fn handle_window_event<R: Runtime>(window: &tauri::Window<R>, event: &WindowEvent) {
    let WindowEvent::CloseRequested { api, .. } = event else {
        return;
    };
    if window.label() != "main" {
        return;
    }
    if !window.app_handle().state::<CloseBehavior>().hides_to_tray() {
        return;
    }
    api.prevent_close();
    let _ = window.hide();
    log::info!("Main window hidden to tray (close-to-tray is on)");
}

/// Open and Quit are handled here; Compose and Sync are webview concerns
/// (routing, the sync API call), so they surface the window and forward the
/// action as an event rather than being reimplemented in Rust.
fn handle_menu_event<R: Runtime>(app: &AppHandle<R>, event: MenuEvent) {
    let id = event.id().as_ref();
    match id {
        MENU_QUIT => {
            log::info!("Quit requested from the tray menu");
            app.exit(0);
        }
        MENU_OPEN => show_main_window(app),
        MENU_COMPOSE | MENU_SYNC => {
            show_main_window(app);
            if let Err(err) = app.emit(TRAY_ACTION_EVENT, id) {
                log::warn!("Failed to forward tray action {id} to the webview: {err}");
            }
        }
        other => log::warn!("Unknown tray menu id: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::CloseBehavior;

    #[test]
    fn close_to_tray_is_the_default_until_the_webview_says_otherwise() {
        assert!(CloseBehavior::default().hides_to_tray());
    }

    #[test]
    fn set_overrides_the_default_in_both_directions() {
        let behavior = CloseBehavior::default();

        behavior.set(false);
        assert!(!behavior.hides_to_tray());

        behavior.set(true);
        assert!(behavior.hides_to_tray());
    }
}
