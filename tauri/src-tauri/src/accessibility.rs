//! Platform permission gate for the auto-paste pipeline.
//!
//! On macOS, posting synthetic keyboard events and reading focused-UI state
//! via the AX API both require the host process to be listed under System
//! Settings → Privacy & Security → Accessibility. Without that trust,
//! `CGEventPost` silently drops events and `AXUIElementCopyAttributeValue`
//! returns an error. We surface a boolean check up front so the paste
//! pipeline can short-circuit with a clear "grant permission" message
//! instead of running through the full save → write → post → restore dance
//! with nothing to show for it.
//!
//! Windows has no equivalent user-facing permission — `SendInput` and
//! UIAutomation work for any non-elevated target out of the box. (UAC /
//! UIPI still blocks sending input *into* an elevated target window from a
//! non-elevated process, but that's per-target, not a global switch, and
//! there's no Settings pane to send users to.) So the Windows branch just
//! returns `true`.
//!
//! Linux types Ctrl+V / Ctrl+C through a uinput virtual keyboard
//! (`synthetic_keys.rs`), so the permission is write access to
//! `/dev/uinput`, which the .deb/.rpm udev rule gives the `input` group.

#[cfg(target_os = "macos")]
mod ffi {
    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        /// Returns true when the current process is listed in Accessibility.
        /// No prompt side-effect.
        pub fn AXIsProcessTrusted() -> bool;
    }
}

#[cfg(target_os = "macos")]
pub fn is_trusted() -> bool {
    unsafe { ffi::AXIsProcessTrusted() }
}

#[cfg(target_os = "windows")]
pub fn is_trusted() -> bool {
    true
}

#[cfg(target_os = "linux")]
pub fn is_trusted() -> bool {
    std::fs::OpenOptions::new().write(true).open("/dev/uinput").is_ok()
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
pub fn is_trusted() -> bool {
    false
}

/// The error shown when [`is_trusted`] is false; `action` reads like
/// "paste into other apps".
#[cfg(target_os = "linux")]
pub fn denied_message(action: &str) -> String {
    format!(
        "Voloz can't {action}: it needs access to /dev/uinput. Add your user to the input group \
         (the .deb and .rpm install the rule for /dev/uinput), then log out and back in."
    )
}

#[cfg(not(target_os = "linux"))]
pub fn denied_message(action: &str) -> String {
    format!(
        "Accessibility permission required to {action}. Open System Settings → Privacy & \
         Security → Accessibility and enable Voloz."
    )
}
