//! Windows notifications for mail that arrives while nobody is looking at the
//! window.
//!
//! The webview announces new mail itself, with a toast and the live region a
//! screen reader reads out. Both live inside the window, so neither reaches
//! anyone while the window is hidden in the tray or another application has
//! the focus: a screen reader reads the live regions of the foreground window
//! only. A Windows notification is the surface outside the window — Windows
//! shows it, plays its sound, hands it to the screen reader and keeps it in the
//! notification centre.
//!
//! The work is split by who can answer what. The webview decides what to say,
//! because the i18n bundles live there, and whether the user wants this at all
//! (Settings → Appearance). This module decides whether the window is being
//! looked at, because only Rust can ask the window. A window with the focus
//! gets no Windows notification: its own toast has just been read out, and a
//! screen reader would read the same sentence a second time.
//!
//! The plugin is driven from Rust only. Its JavaScript commands have no
//! permission in `capabilities/default.json`, so the page cannot post a
//! notification of its own; the one way to the notification surface is
//! [`notify_new_mail`], which asks the window first.
//!
//! A dev build (`target/debug`) posts under Windows PowerShell's app id rather
//! than the bundle identifier: a toast's app id needs a Start-menu shortcut
//! that carries it, and only the installer creates one (`SetLnkAppUserModelId`
//! in `windows/nsis-installer.nsi`). The toast then names PowerShell as its
//! sender; in the installed app it names VoxRox Mail.

use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_notification::NotificationExt;

use crate::tray;

/// The sound name `tauri-winrt-notification` reads as "whatever Windows plays
/// for this app" — it then writes no `<audio>` element at all.
const DEFAULT_SOUND: &str = "Default";

/// The main window as far as it decides whether anyone is looking at it.
#[derive(Clone, Copy, Debug)]
struct WindowState {
    visible: bool,
    focused: bool,
    minimized: bool,
}

impl WindowState {
    /// Whether mail announced inside the window would go unheard: hidden in the
    /// tray, minimized, or behind another application.
    fn unattended(self) -> bool {
        !(self.visible && self.focused && !self.minimized)
    }
}

/// Shows a Windows notification for newly arrived mail, unless the main
/// window has the focus.
///
/// Returns whether the notification was shown, so the caller can tell "the
/// window was being looked at" from a failure, which is an `Err`.
#[tauri::command]
pub fn notify_new_mail(app: AppHandle, title: String, body: String) -> Result<bool, String> {
    if !main_window_state(&app).unattended() {
        return Ok(false);
    }
    app.notification()
        .builder()
        .title(title)
        .body(body)
        // Named, because without a sound the plugin marks the toast
        // `<audio silent="true"/>` — measured in the notification centre's
        // copy on 2026-11-09. "Default" leaves the sound to Windows, so the
        // per-app setting in Windows' notification settings governs it.
        .sound(DEFAULT_SOUND)
        .show()
        .map_err(|err| err.to_string())?;
    // The text stays out of the log: it names the account.
    log::info!("New mail announced with a Windows notification");
    Ok(true)
}

/// Reads the main window's state. A query that fails counts as nobody looking:
/// missing mail costs the user more than hearing it twice.
fn main_window_state<R: Runtime>(app: &AppHandle<R>) -> WindowState {
    let Some(window) = app.get_webview_window("main") else {
        return WindowState {
            visible: false,
            focused: false,
            minimized: false,
        };
    };
    WindowState {
        visible: window.is_visible().unwrap_or(false),
        focused: window.is_focused().unwrap_or(false),
        minimized: window.is_minimized().unwrap_or(false),
    }
}

/// Brings the window back when the user clicks a notification.
///
/// Windows reports the click only while the notification is on screen; one
/// clicked later in the notification centre reaches no handler, and the tray
/// icon is the way back from there.
pub fn open_window_on_click<R: Runtime>(app: &AppHandle<R>) {
    let handle = app.clone();
    if let Err(err) = app
        .notification()
        .on_action(move |_| tray::show_main_window(&handle))
    {
        log::warn!("Clicking a new-mail notification will not open the window: {err}");
    }
}

#[cfg(test)]
mod tests {
    use super::WindowState;

    fn state(visible: bool, focused: bool, minimized: bool) -> WindowState {
        WindowState {
            visible,
            focused,
            minimized,
        }
    }

    #[test]
    fn a_window_with_the_focus_is_attended() {
        assert!(!state(true, true, false).unattended());
    }

    #[test]
    fn a_window_hidden_in_the_tray_is_unattended() {
        assert!(state(false, false, false).unattended());
    }

    #[test]
    fn a_window_behind_another_application_is_unattended() {
        assert!(state(true, false, false).unattended());
    }

    #[test]
    fn a_minimized_window_is_unattended_even_if_it_reports_the_focus() {
        assert!(state(true, true, true).unattended());
    }
}
