//! Synthetic keyboard event posting for the auto-paste pipeline.
//!
//! `send_paste` fires the four-event paste sequence onto the OS input
//! pipeline so the focused app performs its native paste action against
//! whatever the clipboard module has just staged.
//!
//! - **macOS** — Cmd down with Cmd flag, V down with Cmd flag, V up with
//!   Cmd flag, Cmd up via `CGEventPost` at `kCGHIDEventTap`. The Cmd-down
//!   event carries the Command flag so its `flagsChanged` representation
//!   matches hardware — Electron/Chromium tracks modifier state from that
//!   flag and drops the paste otherwise (see the note on the event table).
//!   Accessibility permission is load-bearing: without it the system
//!   swallows the events silently, so callers must gate on
//!   [`crate::accessibility::is_trusted`].
//! - **Windows** — Ctrl down, V down, V up, Ctrl up via `SendInput`. No
//!   permission gate, but UAC/UIPI blocks delivery into elevated target
//!   windows when we run non-elevated — nothing we can do short of also
//!   running elevated.
//!
//! On macOS the V keycode is resolved per-layout by
//! [`crate::keyboard_layout`] — Cmd+V is matched against the layout-
//! translated character via NSMenu key equivalents, so hardcoding
//! `kVK_ANSI_V` (the QWERTY V position) would fire Cmd+. on Dvorak. The
//! resolved keycode is read once per paste from an atomic; the cache is
//! primed at startup and refreshed on layout change.
//!
//! Windows hardcodes `VK_V`. `SendInput` with `wVk = VK_V` makes the
//! target receive `WM_KEYDOWN` with `wParam = VK_V` regardless of the
//! active layout, and most Windows apps treat that as Ctrl+V (the same
//! reason `Send "^v"` works in AutoHotkey on Dvorak Windows).

#[cfg(target_os = "macos")]
use std::ffi::c_void;

#[cfg(target_os = "macos")]
mod ffi {
    use std::ffi::c_void;

    #[repr(C)]
    pub struct CGEvent {
        _opaque: [u8; 0],
    }
    pub type CGEventRef = *mut CGEvent;

    #[repr(C)]
    pub struct CGEventSource {
        _opaque: [u8; 0],
    }
    pub type CGEventSourceRef = *mut CGEventSource;

    pub type CGEventTapLocation = u32;
    pub type CGKeyCode = u16;
    pub type CGEventFlags = u64;
    pub type CGEventSourceStateID = i32;

    /// `kCGHIDEventTap` — posted events enter at the HID level so every
    /// downstream tap (including the target app) sees them exactly as if the
    /// hardware had produced them.
    pub const K_CG_HID_EVENT_TAP: CGEventTapLocation = 0;

    /// `kCGEventSourceStateHIDSystemState` — mimics hardware, which is what
    /// we want: modifier bookkeeping inside target apps stays consistent.
    pub const K_CG_EVENT_SOURCE_STATE_HID_SYSTEM_STATE: CGEventSourceStateID = 1;

    /// `kCGEventFlagMaskCommand` — the Cmd modifier bit inside `CGEventFlags`.
    pub const K_CG_EVENT_FLAG_MASK_COMMAND: CGEventFlags = 0x00100000;

    /// `kVK_Command` (left Cmd).
    pub const KEYCODE_LEFT_CMD: CGKeyCode = 0x37;

    #[link(name = "CoreGraphics", kind = "framework")]
    extern "C" {
        pub fn CGEventSourceCreate(state_id: CGEventSourceStateID) -> CGEventSourceRef;
        pub fn CGEventCreateKeyboardEvent(
            source: CGEventSourceRef,
            virtual_key: CGKeyCode,
            key_down: bool,
        ) -> CGEventRef;
        pub fn CGEventSetFlags(event: CGEventRef, flags: CGEventFlags);
        pub fn CGEventPost(tap: CGEventTapLocation, event: CGEventRef);
    }

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        pub fn CFRelease(cf: *const c_void);
    }
}

/// Post the four-event Cmd+V sequence to the HID event tap.
///
/// Returns after the events are queued — there's no completion callback,
/// so callers should sleep briefly afterwards to let the target app
/// process the paste before any follow-up (e.g. clipboard restore).
#[cfg(target_os = "macos")]
pub fn send_paste() -> Result<(), String> {
    use ffi::*;

    let v_keycode = crate::keyboard_layout::paste_keycode_v();

    unsafe {
        let source = CGEventSourceCreate(K_CG_EVENT_SOURCE_STATE_HID_SYSTEM_STATE);
        if source.is_null() {
            return Err("CGEventSourceCreate returned null".into());
        }
        let _source_guard = scopeguard::guard(source, |s| CFRelease(s as *const c_void));

        let events = [
            // The Cmd-down event must carry the Command flag itself. On real
            // hardware the Cmd keyDown is a flagsChanged event whose flags
            // already include Command; Chromium/Electron builds its tracked
            // modifier state from that flag. Posting Cmd-down with flags = 0
            // leaves that tracker showing "Command up", so the following V —
            // even though its own flags carry Command — matches neither the
            // Cmd+V accelerator (tracker says no modifier) nor plain-text
            // insertion (event flags say Command), and Electron drops it
            // silently. AppKit reads the V event's own flags and pastes
            // regardless, which is why native apps worked but Electron
            // targets (Slack, VS Code) silently no-op'd.
            (KEYCODE_LEFT_CMD, true, K_CG_EVENT_FLAG_MASK_COMMAND),
            (v_keycode, true, K_CG_EVENT_FLAG_MASK_COMMAND),
            (v_keycode, false, K_CG_EVENT_FLAG_MASK_COMMAND),
            (KEYCODE_LEFT_CMD, false, 0),
        ];

        // Build the four events up front so CFRelease happens after all posts.
        // Posting in a loop that interleaved create → post → release would
        // work, but keeping the events alive for the full sequence matches
        // the pattern CGEventPost's docs show and is easier to reason about.
        let mut guards = Vec::with_capacity(events.len());
        let mut created = Vec::with_capacity(events.len());

        for (key, down, flags) in events {
            let event = CGEventCreateKeyboardEvent(source, key, down);
            if event.is_null() {
                return Err(format!(
                    "CGEventCreateKeyboardEvent(key={}, down={}) returned null",
                    key, down
                ));
            }
            let guard = scopeguard::guard(event, |e| CFRelease(e as *const c_void));
            if flags != 0 {
                CGEventSetFlags(event, flags);
            }
            created.push(event);
            guards.push(guard);
        }

        for event in created {
            CGEventPost(K_CG_HID_EVENT_TAP, event);
        }

        drop(guards);
        Ok(())
    }
}

#[cfg(target_os = "windows")]
mod win {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP,
        VIRTUAL_KEY,
    };

    pub fn make_key(vk: VIRTUAL_KEY, up: bool) -> INPUT {
        let flags = if up {
            KEYEVENTF_KEYUP
        } else {
            KEYBD_EVENT_FLAGS(0)
        };
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: vk,
                    wScan: 0,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }
}

#[cfg(target_os = "windows")]
pub fn send_paste() -> Result<(), String> {
    send_ctrl_accelerator(windows::Win32::UI::Input::KeyboardAndMouse::VK_V)
}

/// Ctrl+C, so the focused app copies its selection (voice commands read
/// the selection this way when UIAutomation can't).
#[cfg(target_os = "windows")]
pub fn send_copy() -> Result<(), String> {
    send_ctrl_accelerator(windows::Win32::UI::Input::KeyboardAndMouse::VK_C)
}

#[cfg(target_os = "windows")]
fn send_ctrl_accelerator(
    key: windows::Win32::UI::Input::KeyboardAndMouse::VIRTUAL_KEY,
) -> Result<(), String> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{SendInput, INPUT, VK_CONTROL};

    // Four-event Ctrl+<key> sequence. Matches the macOS CGEvent pattern: the
    // modifier brackets the letter so the target app sees a fully formed
    // accelerator rather than a lone letter. `dwExtraInfo` is zero — we're
    // not tagging these as "ours" because no consumer in the paste path
    // needs to distinguish synthetic events from hardware ones.
    let events = [
        win::make_key(VK_CONTROL, false),
        win::make_key(key, false),
        win::make_key(key, true),
        win::make_key(VK_CONTROL, true),
    ];

    unsafe {
        let sent = SendInput(&events, std::mem::size_of::<INPUT>() as i32);
        if sent as usize != events.len() {
            return Err(format!(
                "SendInput delivered {} of {} events — the input desktop may be locked (secure attention sequence) or a higher-integrity window is intercepting.",
                sent,
                events.len()
            ));
        }
    }

    Ok(())
}

/// Whether the user is physically holding Win, Ctrl, Shift or Alt right
/// now. A synthetic Ctrl+V sent meanwhile combines with them (Win+Ctrl+V
/// opens the sound-output flyout instead of pasting).
#[cfg(target_os = "windows")]
pub fn modifiers_held() -> bool {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        GetAsyncKeyState, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
    };

    [VK_LWIN, VK_RWIN, VK_CONTROL, VK_SHIFT, VK_MENU]
        .iter()
        // High bit set = key is down.
        .any(|vk| unsafe { GetAsyncKeyState(vk.0 as i32) } < 0)
}

#[cfg(not(target_os = "windows"))]
pub fn modifiers_held() -> bool {
    false
}

/// Whether any of these virtual keys is physically down right now
/// (`GetAsyncKeyState` reads the hardware state, whatever window has focus).
#[cfg(target_os = "windows")]
pub fn any_key_down(vks: &[u16]) -> bool {
    use windows::Win32::UI::Input::KeyboardAndMouse::GetAsyncKeyState;

    vks.iter()
        // High bit set = key is down.
        .any(|&vk| unsafe { GetAsyncKeyState(vk as i32) } < 0)
}

/// Linux: Ctrl+V from a uinput virtual keyboard. Wayland gives apps no way
/// to send keys to each other, and uinput sits below the compositor, so this
/// works on Wayland and X11 alike. It needs write access to /dev/uinput: the
/// .deb installs a udev rule for the `input` group (VOLOZ.md, "Linux paste").
/// The key is the physical V position, like a real keyboard on a QWERTY or
/// ABNT2 layout.
#[cfg(target_os = "linux")]
pub fn send_paste() -> Result<(), String> {
    linux_keyboard::send_ctrl(evdev::KeyCode::KEY_V)
}

/// Linux: create the virtual keyboard ahead of the first paste (called when
/// a recording starts), so the compositor has picked it up by then.
#[cfg(target_os = "linux")]
pub fn prepare_paste() {
    if let Err(e) = linux_keyboard::ensure() {
        crate::dictation_log::log(&format!("[rust] paste: virtual keyboard unavailable: {e}"));
    }
}

#[cfg(target_os = "linux")]
mod linux_keyboard {
    use evdev::{uinput::VirtualDevice, AttributeSet, KeyCode, KeyEvent};
    use std::sync::Mutex;
    use std::time::Duration;

    /// Built once and kept: a new input device takes the compositor a moment
    /// to pick up (on KDE Plasma 6.6, 300 ms was too short, 1.2 s enough).
    static DEVICE: Mutex<Option<VirtualDevice>> = Mutex::new(None);
    const PICKUP_DELAY: Duration = Duration::from_millis(1200);
    /// Between key events, as a person's fingers would be.
    const KEY_GAP: Duration = Duration::from_millis(25);

    fn build() -> std::io::Result<VirtualDevice> {
        let mut keys = AttributeSet::<KeyCode>::new();
        keys.insert(KeyCode::KEY_LEFTCTRL);
        keys.insert(KeyCode::KEY_V);
        keys.insert(KeyCode::KEY_C);
        let device = VirtualDevice::builder()?
            .name("Voloz virtual keyboard")
            .with_keys(&keys)?
            .build()?;
        std::thread::sleep(PICKUP_DELAY);
        Ok(device)
    }

    fn open(device: &mut Option<VirtualDevice>) -> Result<(), String> {
        if device.is_none() {
            *device = Some(build().map_err(|e| {
                if e.kind() == std::io::ErrorKind::PermissionDenied {
                    "no permission to write /dev/uinput (the udev rule for the input group is missing)".to_string()
                } else {
                    format!("could not create the virtual keyboard: {e}")
                }
            })?);
        }
        Ok(())
    }

    pub fn ensure() -> Result<(), String> {
        let mut device = DEVICE.lock().map_err(|_| "virtual keyboard lock poisoned".to_string())?;
        open(&mut device)
    }

    pub fn send_ctrl(key: KeyCode) -> Result<(), String> {
        let mut device = DEVICE.lock().map_err(|_| "virtual keyboard lock poisoned".to_string())?;
        open(&mut device)?;
        let keyboard = device.as_mut().expect("opened above");
        // One event per report: Ctrl down and V down in the same report were
        // not taken as a paste on KDE Plasma 6.6.
        for (code, value) in [(KeyCode::KEY_LEFTCTRL, 1), (key, 1), (key, 0), (KeyCode::KEY_LEFTCTRL, 0)] {
            keyboard
                .emit(&[*KeyEvent::new(code, value)])
                .map_err(|e| format!("virtual keyboard write failed: {e}"))?;
            std::thread::sleep(KEY_GAP);
        }
        Ok(())
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
pub fn send_paste() -> Result<(), String> {
    Err("synthetic paste is not yet implemented on this platform".into())
}

/// Linux: Ctrl+C from the virtual keyboard, so a voice command can read
/// the selection (no UIAutomation on Linux).
#[cfg(target_os = "linux")]
pub fn send_copy() -> Result<(), String> {
    linux_keyboard::send_ctrl(evdev::KeyCode::KEY_C)
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
pub fn send_copy() -> Result<(), String> {
    Err("synthetic copy is not yet implemented on this platform".into())
}
