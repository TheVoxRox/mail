//! "Check for updates when the application starts", kept where the installer
//! can reach it.
//!
//! The NSIS privacy page (`frontend/src-tauri/windows/nsis-installer.nsi`)
//! records the answer, the checkbox in Settings → About writes it back through
//! [`set_update_startup_check`], and the webview reads it before the startup
//! check (`frontend/src/lib/stores/updateStartupCheck.ts`). It lives in the
//! registry rather than only in `localStorage` because the installer cannot
//! write into the WebView2 profile, and that profile survives a reinstall: an
//! answer given while reinstalling would otherwise lose to the one stored
//! before it.
//!
//! Absent means nobody answered — a silent install, or an installation older
//! than the page — and the webview then keeps its own value, which defaults to
//! on.

// Only the Windows registry code and the tests name it; CI lints on Linux.
#[cfg(any(windows, test))]
const VALUE_NAME: &str = "UpdateStartupCheck";

/// `Software\VoxRox\Mail[suffix]` under HKCU, the registry twin of the data
/// root: a dev run (`MAIL_DATA_SUFFIX=.dev`) must neither read nor overwrite
/// the answer an installed release was given.
fn key_path(suffix: &str) -> String {
    format!("Software\\VoxRox\\Mail{suffix}")
}

fn current_key_path() -> String {
    key_path(&std::env::var("MAIL_DATA_SUFFIX").unwrap_or_default())
}

/// The recorded answer, or `None` when there is none to adopt.
#[tauri::command]
pub fn get_update_startup_check() -> Option<bool> {
    read(&current_key_path())
}

/// Records the user's choice from Settings → About, so the installer's page
/// offers it again on the next reinstall and the next start reads it back.
#[tauri::command]
pub fn set_update_startup_check(enabled: bool) -> Result<(), String> {
    write(&current_key_path(), enabled)
}

#[cfg(windows)]
fn read(path: &str) -> Option<bool> {
    let key = windows_registry::CURRENT_USER.open(path).ok()?;
    // No value is the ordinary case, not a failure: nothing was answered.
    key.get_type(VALUE_NAME).ok()?;
    match key.get_u32(VALUE_NAME) {
        Ok(value) => Some(value != 0),
        Err(err) => {
            log::warn!("Ignoring {VALUE_NAME} under HKCU\\{path}, which is not a DWORD: {err}");
            None
        }
    }
}

#[cfg(windows)]
fn write(path: &str, enabled: bool) -> Result<(), String> {
    windows_registry::CURRENT_USER
        .create(path)
        .and_then(|key| key.set_u32(VALUE_NAME, u32::from(enabled)))
        .map_err(|err| err.to_string())
}

#[cfg(not(windows))]
fn read(_path: &str) -> Option<bool> {
    None
}

#[cfg(not(windows))]
fn write(_path: &str, _enabled: bool) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{key_path, VALUE_NAME};

    #[test]
    fn the_dev_suffix_selects_a_sibling_key() {
        assert_eq!(key_path(""), "Software\\VoxRox\\Mail");
        assert_eq!(key_path(".dev"), "Software\\VoxRox\\Mail.dev");
    }

    /// The installer writes the answer and the application reads it, and the
    /// two sides name the key in different languages. A rename on one side
    /// alone would leave the page asking a question nothing reads.
    #[test]
    fn the_installer_writes_where_the_application_reads() {
        let template = include_str!("../windows/nsis-installer.nsi");
        let key = key_path("");
        assert!(
            template.contains(&format!("!define UPDATECHECKKEY \"{key}\"")),
            "nsis-installer.nsi does not define UPDATECHECKKEY as {key}"
        );
        assert!(
            template.contains(&format!("!define UPDATECHECKVALUE \"{VALUE_NAME}\"")),
            "nsis-installer.nsi does not define UPDATECHECKVALUE as {VALUE_NAME}"
        );
    }

    #[cfg(windows)]
    mod registry {
        use super::super::{read, write, VALUE_NAME};

        /// A key of its own per process, removed afterwards, so the test never
        /// touches the answer a real installation holds.
        struct ScratchKey(String);

        impl ScratchKey {
            fn new(name: &str) -> Self {
                Self(format!(
                    "Software\\VoxRox\\Mail.test-{}-{name}",
                    std::process::id()
                ))
            }
        }

        impl Drop for ScratchKey {
            fn drop(&mut self) {
                let _ = windows_registry::CURRENT_USER.remove_tree(&self.0);
            }
        }

        #[test]
        fn nothing_recorded_reads_as_no_answer() {
            let scratch = ScratchKey::new("absent");
            assert_eq!(read(&scratch.0), None);

            windows_registry::CURRENT_USER
                .create(&scratch.0)
                .expect("create scratch key");
            assert_eq!(read(&scratch.0), None);
        }

        #[test]
        fn a_written_answer_reads_back_in_both_directions() {
            let scratch = ScratchKey::new("roundtrip");

            write(&scratch.0, false).expect("write off");
            assert_eq!(read(&scratch.0), Some(false));

            write(&scratch.0, true).expect("write on");
            assert_eq!(read(&scratch.0), Some(true));
        }

        #[test]
        fn a_value_of_the_wrong_type_is_not_adopted() {
            let scratch = ScratchKey::new("wrong-type");
            windows_registry::CURRENT_USER
                .create(&scratch.0)
                .and_then(|key| key.set_string(VALUE_NAME, "0"))
                .expect("write string value");

            assert_eq!(read(&scratch.0), None);
        }
    }
}
