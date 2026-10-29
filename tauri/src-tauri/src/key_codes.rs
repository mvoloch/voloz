//! Stable string ↔ `keytap::Key` mapping for chord persistence.
//!
//! The frontend captures keypresses through the browser keyboard API (which
//! exposes `event.code` like `"MetaRight"`, `"AltRight"`, `"Space"`, `"KeyA"`)
//! and stores chords in capture_settings as JSON arrays of canonical names.
//! On the way back the same names need to round-trip into `keytap::Key`
//! variants the chord engine actually matches against.
//!
//! Input strings follow the W3C `KeyboardEvent.code` identifiers exactly —
//! `"MetaRight"`, `"AltRight"`, `"KeyA"`, `"Digit0"`, `"ArrowUp"`, … —
//! which is also what the browser emits natively, so on-disk chords
//! round-trip without translation on the frontend side. Legacy aliases
//! (`"Alt"` / `"AltGr"` / `"Num0"` / `"UpArrow"` / …) are accepted too so
//! older capture_settings rows written before the keytap swap keep working.

use keytap::Key;

/// Resolve a canonical key name to its `keytap::Key`. Returns `None` for
/// names that don't have a corresponding variant — the command surface
/// rejects those so we never silently drop keys from a chord.
pub fn key_from_str(name: &str) -> Option<Key> {
    Some(match name {
        // Modifiers — left/right distinction matters for chord defaults.
        "AltLeft" | "Alt" => Key::AltLeft,
        "AltRight" | "AltGr" => Key::AltRight,
        "ControlLeft" => Key::ControlLeft,
        "ControlRight" => Key::ControlRight,
        "MetaLeft" => Key::MetaLeft,
        "MetaRight" => Key::MetaRight,
        "ShiftLeft" => Key::ShiftLeft,
        "ShiftRight" => Key::ShiftRight,
        "CapsLock" => Key::CapsLock,
        "Function" => Key::Function,

        // Whitespace / navigation
        "Space" => Key::Space,
        "Tab" => Key::Tab,
        "Enter" | "Return" => Key::Enter,
        "Backspace" => Key::Backspace,
        "Delete" => Key::Delete,
        "Escape" => Key::Escape,
        "Insert" => Key::Insert,
        "Home" => Key::Home,
        "End" => Key::End,
        "PageUp" => Key::PageUp,
        "PageDown" => Key::PageDown,
        "ArrowUp" | "UpArrow" => Key::ArrowUp,
        "ArrowDown" | "DownArrow" => Key::ArrowDown,
        "ArrowLeft" | "LeftArrow" => Key::ArrowLeft,
        "ArrowRight" | "RightArrow" => Key::ArrowRight,

        // Function row
        "F1" => Key::F1, "F2" => Key::F2, "F3" => Key::F3, "F4" => Key::F4,
        "F5" => Key::F5, "F6" => Key::F6, "F7" => Key::F7, "F8" => Key::F8,
        "F9" => Key::F9, "F10" => Key::F10, "F11" => Key::F11, "F12" => Key::F12,

        // Digits
        "Digit0" | "Num0" => Key::Digit0,
        "Digit1" | "Num1" => Key::Digit1,
        "Digit2" | "Num2" => Key::Digit2,
        "Digit3" | "Num3" => Key::Digit3,
        "Digit4" | "Num4" => Key::Digit4,
        "Digit5" | "Num5" => Key::Digit5,
        "Digit6" | "Num6" => Key::Digit6,
        "Digit7" | "Num7" => Key::Digit7,
        "Digit8" | "Num8" => Key::Digit8,
        "Digit9" | "Num9" => Key::Digit9,

        // Letters — browser emits "KeyA"; keytap uses the bare letter.
        "KeyA" => Key::A, "KeyB" => Key::B, "KeyC" => Key::C,
        "KeyD" => Key::D, "KeyE" => Key::E, "KeyF" => Key::F,
        "KeyG" => Key::G, "KeyH" => Key::H, "KeyI" => Key::I,
        "KeyJ" => Key::J, "KeyK" => Key::K, "KeyL" => Key::L,
        "KeyM" => Key::M, "KeyN" => Key::N, "KeyO" => Key::O,
        "KeyP" => Key::P, "KeyQ" => Key::Q, "KeyR" => Key::R,
        "KeyS" => Key::S, "KeyT" => Key::T, "KeyU" => Key::U,
        "KeyV" => Key::V, "KeyW" => Key::W, "KeyX" => Key::X,
        "KeyY" => Key::Y, "KeyZ" => Key::Z,

        // Punctuation / symbols
        "Backquote" | "BackQuote" => Key::Backtick,
        "Minus" => Key::Minus,
        "Equal" => Key::Equal,
        "BracketLeft" | "LeftBracket" => Key::BracketLeft,
        "BracketRight" | "RightBracket" => Key::BracketRight,
        "Semicolon" | "SemiColon" => Key::Semicolon,
        "Quote" => Key::Quote,
        "Backslash" | "BackSlash" => Key::Backslash,
        "Comma" => Key::Comma,
        "Period" | "Dot" => Key::Period,
        "Slash" => Key::Slash,

        _ => return None,
    })
}

/// The Windows virtual-key code of a chord key, to read its physical state
/// with `GetAsyncKeyState` (the safety net for a lost chord release in
/// `hotkey_monitor`). Covers every key `key_from_str` accepts except `Fn`,
/// which Windows doesn't expose; codes as in keytap's own Windows table
/// (left/right modifiers distinct, punctuation as on a US layout).
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
pub fn windows_vk(key: Key) -> Option<u16> {
    Some(match key {
        Key::ShiftLeft => 0xA0,
        Key::ShiftRight => 0xA1,
        Key::ControlLeft => 0xA2,
        Key::ControlRight => 0xA3,
        Key::AltLeft => 0xA4,
        Key::AltRight => 0xA5,
        Key::MetaLeft => 0x5B,
        Key::MetaRight => 0x5C,
        Key::CapsLock => 0x14,

        Key::Backspace => 0x08,
        Key::Tab => 0x09,
        Key::Enter => 0x0D,
        Key::Escape => 0x1B,
        Key::Space => 0x20,
        Key::PageUp => 0x21,
        Key::PageDown => 0x22,
        Key::End => 0x23,
        Key::Home => 0x24,
        Key::ArrowLeft => 0x25,
        Key::ArrowUp => 0x26,
        Key::ArrowRight => 0x27,
        Key::ArrowDown => 0x28,
        Key::Insert => 0x2D,
        Key::Delete => 0x2E,

        Key::Digit0 => 0x30, Key::Digit1 => 0x31, Key::Digit2 => 0x32,
        Key::Digit3 => 0x33, Key::Digit4 => 0x34, Key::Digit5 => 0x35,
        Key::Digit6 => 0x36, Key::Digit7 => 0x37, Key::Digit8 => 0x38,
        Key::Digit9 => 0x39,

        Key::A => 0x41, Key::B => 0x42, Key::C => 0x43, Key::D => 0x44,
        Key::E => 0x45, Key::F => 0x46, Key::G => 0x47, Key::H => 0x48,
        Key::I => 0x49, Key::J => 0x4A, Key::K => 0x4B, Key::L => 0x4C,
        Key::M => 0x4D, Key::N => 0x4E, Key::O => 0x4F, Key::P => 0x50,
        Key::Q => 0x51, Key::R => 0x52, Key::S => 0x53, Key::T => 0x54,
        Key::U => 0x55, Key::V => 0x56, Key::W => 0x57, Key::X => 0x58,
        Key::Y => 0x59, Key::Z => 0x5A,

        Key::F1 => 0x70, Key::F2 => 0x71, Key::F3 => 0x72, Key::F4 => 0x73,
        Key::F5 => 0x74, Key::F6 => 0x75, Key::F7 => 0x76, Key::F8 => 0x77,
        Key::F9 => 0x78, Key::F10 => 0x79, Key::F11 => 0x7A, Key::F12 => 0x7B,

        Key::Semicolon => 0xBA,
        Key::Equal => 0xBB,
        Key::Comma => 0xBC,
        Key::Minus => 0xBD,
        Key::Period => 0xBE,
        Key::Slash => 0xBF,
        Key::Backtick => 0xC0,
        Key::BracketLeft => 0xDB,
        Key::Backslash => 0xDC,
        Key::BracketRight => 0xDD,
        Key::Quote => 0xDE,

        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every name the chord picker can store.
    const NAMES: &[&str] = &[
        "AltLeft", "AltRight", "ControlLeft", "ControlRight", "MetaLeft", "MetaRight",
        "ShiftLeft", "ShiftRight", "CapsLock", "Space", "Tab", "Enter", "Backspace",
        "Delete", "Escape", "Insert", "Home", "End", "PageUp", "PageDown", "ArrowUp",
        "ArrowDown", "ArrowLeft", "ArrowRight", "F1", "F2", "F3", "F4", "F5", "F6", "F7",
        "F8", "F9", "F10", "F11", "F12", "Digit0", "Digit1", "Digit2", "Digit3", "Digit4",
        "Digit5", "Digit6", "Digit7", "Digit8", "Digit9", "KeyA", "KeyB", "KeyC", "KeyD",
        "KeyE", "KeyF", "KeyG", "KeyH", "KeyI", "KeyJ", "KeyK", "KeyL", "KeyM", "KeyN",
        "KeyO", "KeyP", "KeyQ", "KeyR", "KeyS", "KeyT", "KeyU", "KeyV", "KeyW", "KeyX",
        "KeyY", "KeyZ", "Backquote", "Minus", "Equal", "BracketLeft", "BracketRight",
        "Semicolon", "Quote", "Backslash", "Comma", "Period", "Slash",
    ];

    #[test]
    fn every_chord_key_but_fn_has_a_distinct_windows_vk() {
        let mut seen = std::collections::HashSet::new();
        for name in NAMES {
            let key = key_from_str(name).unwrap_or_else(|| panic!("{name} should parse"));
            let vk = windows_vk(key).unwrap_or_else(|| panic!("{name} should have a VK"));
            assert!(seen.insert(vk), "{name}: VK 0x{vk:X} used twice");
        }
        assert_eq!(windows_vk(Key::Function), None);
    }

    #[test]
    fn default_chord_keys_match_the_windows_constants() {
        // VK_RCONTROL, VK_RSHIFT, VK_LWIN, VK_RMENU (winuser.h).
        assert_eq!(windows_vk(Key::ControlRight), Some(0xA3));
        assert_eq!(windows_vk(Key::ShiftRight), Some(0xA1));
        assert_eq!(windows_vk(Key::MetaLeft), Some(0x5B));
        assert_eq!(windows_vk(Key::AltRight), Some(0xA5));
    }
}
