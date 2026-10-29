//! Start Voloz when the user signs in.
//!
//! tauri-plugin-autostart owns the OS entry (on Windows the
//! `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` value) and adds
//! `--autostart` to its command line, so a login launch can tell itself
//! apart from one the user started: it keeps the main window hidden and
//! shows only the tray icon. The main webview still loads behind it, since
//! it starts the server and arms the dictation hotkey. Settings → General
//! reads and writes the same entry through the plugin's commands.

use tauri::plugin::TauriPlugin;
use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_autostart::ManagerExt;

/// Argument the login entry passes to the app.
pub const LAUNCH_ARG: &str = "--autostart";

/// Written to the app data dir once autostart has been turned on by
/// default, so a user who turns it off is never overridden.
const DEFAULT_APPLIED_MARKER: &str = ".autostart-default-applied";

pub fn plugin<R: Runtime>() -> TauriPlugin<R> {
    tauri_plugin_autostart::Builder::new()
        .arg(LAUNCH_ARG)
        .build()
}

/// Whether this process was started by the login entry.
pub fn launched_at_login() -> bool {
    std::env::args().skip(1).any(|arg| arg == LAUNCH_ARG)
}

/// Turn autostart on the first time the app runs, and on every later
/// launch point an enabled entry at this executable: the MSI installs to
/// `Program Files` and the NSIS installer to `%LOCALAPPDATA%`, so the path
/// in the entry goes stale when the user switches between them.
///
/// Skipped in debug builds, which would register `target/debug`'s binary
/// as the login item of the development machine.
pub fn apply_default<R: Runtime>(app: &AppHandle<R>) {
    if cfg!(debug_assertions) {
        return;
    }
    let autolaunch = app.autolaunch();

    let marker = match app.path().app_data_dir() {
        Ok(dir) => dir.join(DEFAULT_APPLIED_MARKER),
        Err(e) => {
            eprintln!(
                "autostart: no app data dir, leaving the login entry alone: {}",
                e
            );
            return;
        }
    };
    if !marker.exists() {
        if let Err(e) = autolaunch.enable() {
            eprintln!("autostart: failed to enable on first run: {}", e);
        }
        // Recorded even when enabling failed: one attempt only, so a later
        // launch can't turn on what the user has since turned off.
        let written = marker
            .parent()
            .map_or(Ok(()), std::fs::create_dir_all)
            .and_then(|()| std::fs::write(&marker, b"1"));
        if let Err(e) = written {
            eprintln!("autostart: failed to write {:?}: {}", marker, e);
        }
        return;
    }

    match autolaunch.is_enabled() {
        // `enable` rewrites the entry with the current executable's path.
        Ok(true) => {
            if let Err(e) = autolaunch.enable() {
                eprintln!("autostart: failed to refresh the login entry: {}", e);
            }
        }
        Ok(false) => {}
        Err(e) => eprintln!("autostart: failed to read the login entry: {}", e),
    }
}
