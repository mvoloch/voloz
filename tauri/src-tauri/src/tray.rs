//! System tray icon.
//!
//! Closing the main window hides it here instead of quitting, so the
//! backend and the global dictation hotkey keep running and a stray click
//! on the close button can't silently kill dictation. Left-clicking the
//! icon (or "Abrir Voloz" in its menu) brings the window back; "Quit"
//! runs the existing close flow — the frontend stops the server unless
//! "Keep server running" is on — and then exits the app.

use std::sync::atomic::{AtomicBool, Ordering};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Runtime, WebviewWindow, WebviewWindowBuilder, Window};

const MAIN_WINDOW_LABEL: &str = "main";

/// Set by the tray's "Quit" item. The main window's `CloseRequested`
/// handler hides to the tray unless this is set, in which case it runs the
/// real close flow and the app exits.
pub static QUIT_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn quit_requested() -> bool {
    QUIT_REQUESTED.load(Ordering::SeqCst)
}

/// Whether a close request on this window should hide it to the tray.
pub fn should_hide_on_close(window_label: &str) -> bool {
    window_label == MAIN_WINDOW_LABEL && !quit_requested()
}

/// Send the main window to the tray and free its webview.
///
/// The dictation pipeline (record → transcribe → refine → paste) runs in
/// the separate dictate window and the hotkey in Rust, so nothing the user
/// relies on while the window is closed lives in the main webview — and
/// destroying it returns its renderer process (~100-250 MB) to the OS.
/// `show_main_window` rebuilds it from the app config. Destroy runs off the
/// event handler to avoid tearing the window down mid-dispatch.
pub fn release_main_window<R: Runtime>(window: &Window<R>) {
    let window = window.clone();
    tauri::async_runtime::spawn(async move {
        if window.destroy().is_err() {
            let _ = window.hide();
        }
    });
}

/// Show, restore and focus the main window, rebuilding it if it was
/// released to the tray.
pub fn show_main_window<R: Runtime>(app: &AppHandle<R>) {
    let window = match app.get_webview_window(MAIN_WINDOW_LABEL) {
        Some(window) => window,
        None => match rebuild_main_window(app) {
            Some(window) => window,
            None => return,
        },
    };
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
}

fn rebuild_main_window<R: Runtime>(app: &AppHandle<R>) -> Option<WebviewWindow<R>> {
    let config = app
        .config()
        .app
        .windows
        .iter()
        .find(|w| w.label == MAIN_WINDOW_LABEL)?
        .clone();
    match WebviewWindowBuilder::from_config(app, &config).and_then(|builder| builder.build()) {
        Ok(window) => Some(window),
        Err(e) => {
            eprintln!("Failed to rebuild main window: {}", e);
            None
        }
    }
}

/// Quit for real: route through the main window's close flow so the
/// frontend cleanup handshake runs before the process exits.
pub fn request_quit<R: Runtime>(app: &AppHandle<R>) {
    QUIT_REQUESTED.store(true, Ordering::SeqCst);
    match app.get_webview_window(MAIN_WINDOW_LABEL) {
        Some(window) => {
            if window.close().is_err() {
                app.exit(0);
            }
        }
        None => app.exit(0),
    }
}

/// Create the tray icon and its menu.
pub fn build<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Abrir Voloz", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Sair", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;

    let mut builder = TrayIconBuilder::with_id("main")
        .tooltip("Voloz")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => request_quit(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}
