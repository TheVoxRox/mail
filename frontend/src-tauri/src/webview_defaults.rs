//! Browser behaviour WebView2 keeps by default and a mail client has no use for.
//!
//! Two handlers, attached to the one webview once it exists:
//!
//! - **Browser accelerator keys.** WebView2 leaves them on and Tauri exposes no
//!   setting for them, so every key the frontend did not claim itself reached
//!   the browser: F5 and Ctrl+R reloaded the app, restarting the backend with
//!   it, and Ctrl+P opened printing for the whole window. They are switched off
//!   per key through `IsBrowserAcceleratorKeyEnabled` rather than `Handled`,
//!   because only the former still delivers the key to the page — the app keeps
//!   receiving F5, Ctrl+P and the rest as ordinary keydowns and can give them
//!   Outlook's meaning. Deny by default: Microsoft lists the browser keys as
//!   "including but not limited to". Kept are history navigation, which the
//!   app's URLs are built for, and F12 in debug builds, where devtools exist.
//!   Editing and movement keys (Ctrl+C/V/X/A/Z, Home, End, Page Up/Down) are
//!   not browser accelerators and are never affected.
//! - **The default context menu** loses Back, Forward, Reload, Save as and
//!   Print. Switching the whole menu off would also take Cut, Copy, Paste and
//!   the spelling suggestions out of the compose editor.
//!
//! A handler that fails to attach is logged and leaves that default in place:
//! a mail client that starts with a browser's shortcuts beats one that does not
//! start.

/// Whether a browser accelerator stays with WebView2 instead of being switched
/// off. `alt_down` is the Alt state WebView2 reports with the key.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn keeps_browser_handling(virtual_key: u32, alt_down: bool, devtools: bool) -> bool {
    const VK_LEFT: u32 = 0x25;
    const VK_RIGHT: u32 = 0x27;
    const VK_F12: u32 = 0x7B;
    const VK_BROWSER_BACK: u32 = 0xA6;
    const VK_BROWSER_FORWARD: u32 = 0xA7;
    match virtual_key {
        VK_LEFT | VK_RIGHT => alt_down,
        VK_BROWSER_BACK | VK_BROWSER_FORWARD => true,
        VK_F12 => devtools,
        _ => false,
    }
}

/// Entries removed from WebView2's default context menu, by the names
/// `ICoreWebView2ContextMenuItem::Name` reports.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn is_removed_context_menu_item(name: &str) -> bool {
    matches!(name, "back" | "forward" | "reload" | "saveAs" | "print")
}

#[cfg(windows)]
pub fn install<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) {
    let attached = window.with_webview(|webview| {
        if let Err(err) = unsafe { windows_impl::attach(&webview.controller()) } {
            log::error!("WebView2 browser defaults left in place: {err}");
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

    pub unsafe fn attach(controller: &ICoreWebView2Controller) -> windows_core::Result<()> {
        // Both handlers live as long as the webview, so the tokens are never read.
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
        )?;

        let webview = controller.CoreWebView2()?.cast::<ICoreWebView2_11>()?;
        webview.add_ContextMenuRequested(
            &ContextMenuRequestedEventHandler::create(Box::new(|_, args| {
                let Some(args) = args else { return Ok(()) };
                let items = args.MenuItems()?;
                let mut count = 0u32;
                items.Count(&mut count)?;
                let mut removed = Vec::new();
                let mut kept = Vec::new();
                for index in (0..count).rev() {
                    let mut name = PWSTR::null();
                    items.GetValueAtIndex(index)?.Name(&mut name)?;
                    let name = take_pwstr(name);
                    if is_removed_context_menu_item(&name) {
                        items.RemoveValueAtIndex(index)?;
                        removed.push(name);
                    } else {
                        kept.push(name);
                    }
                }
                // Debug builds only: the names are what the filter matches on,
                // and a WebView2 update renaming one would otherwise go unseen.
                if cfg!(debug_assertions) {
                    removed.reverse();
                    kept.reverse();
                    log::info!("Context menu: removed {removed:?}, kept {kept:?}");
                }
                Ok(())
            })),
            &mut token,
        )?;

        Ok(())
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
    fn reload_print_and_find_are_switched_off() {
        // F5, R (Ctrl+R), P (Ctrl+P), F3, F (Ctrl+F), F7
        for key in [0x74, 0x52, 0x50, 0x72, 0x46, 0x76] {
            assert!(!keeps_browser_handling(key, false, false), "key {key:#x}");
        }
    }

    #[test]
    fn devtools_key_only_where_devtools_exist() {
        assert!(keeps_browser_handling(0x7B, false, true));
        assert!(!keeps_browser_handling(0x7B, false, false));
    }

    #[test]
    fn only_navigation_and_page_entries_leave_the_context_menu() {
        for name in ["back", "forward", "reload", "saveAs", "print"] {
            assert!(is_removed_context_menu_item(name), "{name}");
        }
        for name in [
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
