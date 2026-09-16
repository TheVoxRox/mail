//! Browser behaviour WebView2 keeps by default and a mail client has no use for.
//!
//! Two handlers, attached to the one webview once it exists:
//!
//! - **Browser accelerator keys.** WebView2 leaves them on and Tauri exposes no
//!   setting for them, so every key the frontend did not claim itself reached
//!   the browser: F5 and Ctrl+R reloaded the app, restarting the backend with
//!   it. They are switched off per key through `IsBrowserAcceleratorKeyEnabled`
//!   rather than `Handled`, because only the former still delivers the key to
//!   the page — the app keeps receiving F5 and the rest as ordinary keydowns
//!   and can give them Outlook's meaning. Deny by default: Microsoft lists the
//!   browser keys as "including but not limited to". Kept are history
//!   navigation, which the app's URLs are built for, F12 in debug builds, where
//!   devtools exist, and two groups the app has nothing of its own to put in
//!   the place of — see `keeps_browser_handling`. Editing and movement keys
//!   (Ctrl+C/V/X/A/Z, Home, End, Page Up/Down) are not browser accelerators and
//!   are never affected.
//! - **The default context menu** loses Back, Forward, Reload and Save as.
//!   Switching the whole menu off would also take Cut, Copy, Paste and the
//!   spelling suggestions out of the compose editor.
//!
//! Each handler is attached and reported on its own: an older runtime can carry
//! one and not the other, and a handler that fails to attach leaves that default
//! in place. A mail client that starts with a browser's shortcuts beats one that
//! does not start.

/// Whether a browser accelerator stays with WebView2 instead of being switched
/// off. `alt_down` is the Alt state WebView2 reports with the key.
///
/// One group is kept although it is browser behaviour, because switching it off
/// would remove the only way to do the thing and put nothing back:
///
/// - **Zoom** (Ctrl+0, Ctrl+Plus, Ctrl+Minus and their numpad twins). The app's
///   own text size is three steps ending at an 18px root font, a little over
///   110%, so page zoom is what carries a reader from there to the 200% that
///   WCAG 1.4.4 asks for.
/// Print is no longer among them. It was kept while nothing in the app printed,
/// because denying it took printing a message away altogether; the app now binds
/// Ctrl+P itself (`globalShortcuts.ts`) and prints the open message rather than
/// the window, so the key is claimed like Ctrl+R and Ctrl+F before it. The
/// context menu keeps its Print entry, which is not an accelerator and now
/// reaches the same sheet through the print rules in app.css.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn keeps_browser_handling(virtual_key: u32, alt_down: bool, devtools: bool) -> bool {
    const VK_0: u32 = 0x30;
    const VK_LEFT: u32 = 0x25;
    const VK_RIGHT: u32 = 0x27;
    const VK_NUMPAD0: u32 = 0x60;
    const VK_ADD: u32 = 0x6B;
    const VK_SUBTRACT: u32 = 0x6D;
    const VK_F12: u32 = 0x7B;
    const VK_BROWSER_BACK: u32 = 0xA6;
    const VK_BROWSER_FORWARD: u32 = 0xA7;
    const VK_OEM_PLUS: u32 = 0xBB;
    const VK_OEM_MINUS: u32 = 0xBD;
    match virtual_key {
        VK_LEFT | VK_RIGHT => alt_down,
        VK_BROWSER_BACK | VK_BROWSER_FORWARD => true,
        VK_F12 => devtools,
        // Kept as the Ctrl chords they are. Alt with the same key is nothing
        // the browser does, and stays denied along with everything else.
        VK_0 | VK_NUMPAD0 | VK_ADD | VK_SUBTRACT | VK_OEM_PLUS | VK_OEM_MINUS => !alt_down,
        _ => false,
    }
}

/// Entries removed from WebView2's default context menu, by the names
/// `ICoreWebView2ContextMenuItem::Name` reports.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn is_removed_context_menu_item(name: &str) -> bool {
    matches!(name, "back" | "forward" | "reload" | "saveAs")
}

#[cfg(windows)]
pub fn install<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) {
    let attached = window.with_webview(|webview| {
        let controller = webview.controller();
        if let Err(err) = unsafe { windows_impl::attach_accelerator_keys(&controller) } {
            log::error!("WebView2 browser accelerator keys left on: {err}");
        }
        if let Err(err) = unsafe { windows_impl::attach_context_menu(&controller) } {
            log::error!("WebView2 default context menu left as it is: {err}");
        }
    });
    if let Err(err) = attached {
        log::error!("WebView2 browser defaults left in place: {err}");
    }
}

#[cfg(windows)]
mod windows_impl {
    use super::{is_removed_context_menu_item, keeps_browser_handling};
    use webview2_com::Microsoft::Web::WebView2::Win32::{
        ICoreWebView2AcceleratorKeyPressedEventArgs2, ICoreWebView2Controller, ICoreWebView2_11,
        COREWEBVIEW2_KEY_EVENT_KIND, COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN,
        COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN, COREWEBVIEW2_PHYSICAL_KEY_STATUS,
    };
    use webview2_com::{
        take_pwstr, AcceleratorKeyPressedEventHandler, ContextMenuRequestedEventHandler,
    };
    use windows_core::{Interface, PWSTR};

    pub unsafe fn attach_accelerator_keys(
        controller: &ICoreWebView2Controller,
    ) -> windows_core::Result<()> {
        // The handler lives as long as the webview, so the token is never read.
        let mut token = 0i64;
        controller.add_AcceleratorKeyPressed(
            &AcceleratorKeyPressedEventHandler::create(Box::new(|_, args| {
                let Some(args) = args else { return Ok(()) };
                let mut kind = COREWEBVIEW2_KEY_EVENT_KIND::default();
                args.KeyEventKind(&mut kind)?;
                if kind != COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN
                    && kind != COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN
                {
                    return Ok(());
                }
                let mut key = 0u32;
                args.VirtualKey(&mut key)?;
                let mut status = COREWEBVIEW2_PHYSICAL_KEY_STATUS::default();
                args.PhysicalKeyStatus(&mut status)?;
                if keeps_browser_handling(
                    key,
                    status.IsMenuKeyDown.as_bool(),
                    cfg!(debug_assertions),
                ) {
                    return Ok(());
                }
                // A runtime older than 1.0.2210.55 has no such interface and keeps
                // the browser keys, as every runtime did before this handler.
                if let Ok(args) = args.cast::<ICoreWebView2AcceleratorKeyPressedEventArgs2>() {
                    args.SetIsBrowserAcceleratorKeyEnabled(false)?;
                }
                Ok(())
            })),
            &mut token,
        )
    }

    pub unsafe fn attach_context_menu(
        controller: &ICoreWebView2Controller,
    ) -> windows_core::Result<()> {
        // A runtime older than 1.0.1108.44 has no ICoreWebView2_11 and so no
        // context menu event. Reported on its own, which is the reason this is a
        // handler of its own: the accelerator keys are switched off either way.
        let webview = controller.CoreWebView2()?.cast::<ICoreWebView2_11>()?;
        // The handler lives as long as the webview, so the token is never read.
        let mut token = 0i64;
        webview.add_ContextMenuRequested(
            &ContextMenuRequestedEventHandler::create(Box::new(|_, args| {
                let Some(args) = args else { return Ok(()) };
                let items = args.MenuItems()?;
                let mut count = 0u32;
                items.Count(&mut count)?;
                // Debug builds only: the names are what the filter matches on,
                // and a WebView2 update renaming one would otherwise go unseen.
                // Collected under the same cfg, so a release build does not
                // build two vectors per right click for nobody to read.
                #[cfg(debug_assertions)]
                let mut removed = Vec::new();
                #[cfg(debug_assertions)]
                let mut kept = Vec::new();
                for index in (0..count).rev() {
                    let mut name = PWSTR::null();
                    items.GetValueAtIndex(index)?.Name(&mut name)?;
                    let name = take_pwstr(name);
                    if is_removed_context_menu_item(&name) {
                        items.RemoveValueAtIndex(index)?;
                        #[cfg(debug_assertions)]
                        removed.push(name);
                    } else {
                        #[cfg(debug_assertions)]
                        kept.push(name);
                    }
                }
                #[cfg(debug_assertions)]
                {
                    removed.reverse();
                    kept.reverse();
                    log::info!("Context menu: removed {removed:?}, kept {kept:?}");
                }
                Ok(())
            })),
            &mut token,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::{is_removed_context_menu_item, keeps_browser_handling};

    #[test]
    fn history_navigation_stays_with_the_browser() {
        assert!(keeps_browser_handling(0x25, true, false));
        assert!(keeps_browser_handling(0x27, true, false));
        assert!(keeps_browser_handling(0xA6, false, false));
        assert!(keeps_browser_handling(0xA7, false, false));
    }

    #[test]
    fn arrows_without_alt_are_not_history_navigation() {
        assert!(!keeps_browser_handling(0x25, false, false));
        assert!(!keeps_browser_handling(0x27, false, false));
    }

    #[test]
    fn reload_find_and_print_are_switched_off() {
        // F5, R (Ctrl+R), P (Ctrl+P), F3, F (Ctrl+F), F7
        for key in [0x74, 0x52, 0x50, 0x72, 0x46, 0x76] {
            assert!(!keeps_browser_handling(key, false, false), "key {key:#x}");
        }
    }

    #[test]
    fn page_zoom_stays_with_the_browser() {
        // Ctrl+0, Ctrl+Numpad0, Ctrl+Add, Ctrl+Subtract, Ctrl+Plus, Ctrl+Minus.
        // The app's own text size ends a little over 110%, so this is what a
        // reader has to reach 200% with.
        for key in [0x30, 0x60, 0x6B, 0x6D, 0xBB, 0xBD] {
            assert!(keeps_browser_handling(key, false, false), "key {key:#x}");
            assert!(!keeps_browser_handling(key, true, false), "alt {key:#x}");
        }
    }

    #[test]
    fn print_is_the_app_s_key_now_and_not_the_browser_s() {
        // globalShortcuts.ts binds Ctrl+P to printing the open message, so the
        // key reaches the page as an ordinary keydown like Ctrl+R and Ctrl+F.
        assert!(!keeps_browser_handling(0x50, false, false));
    }

    #[test]
    fn devtools_key_only_where_devtools_exist() {
        assert!(keeps_browser_handling(0x7B, false, true));
        assert!(!keeps_browser_handling(0x7B, false, false));
    }

    #[test]
    fn only_navigation_and_page_entries_leave_the_context_menu() {
        for name in ["back", "forward", "reload", "saveAs"] {
            assert!(is_removed_context_menu_item(name), "{name}");
        }
        for name in [
            "print",
            "cut",
            "copy",
            "paste",
            "selectAll",
            "spellcheck",
            "inspectElement",
        ] {
            assert!(!is_removed_context_menu_item(name), "{name}");
        }
    }
}
