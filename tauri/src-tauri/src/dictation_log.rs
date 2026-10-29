//! `dictation.log`: a short trail of what each dictation did, to diagnose
//! one that went wrong on a user's machine (a pill stuck on "Recording", a
//! stop that did nothing). It sits next to the server's `watchdog.log` in
//! `<app data>/logs` (`%APPDATA%\io.github.mvoloch.voloz\logs` on Windows).
//!
//! Lines say what happened — chord events, the events sent to the pill, the
//! recorder's state, upload and paste results — never transcript text, keys
//! or window titles. The webview writes through the `dictation_log` command.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

/// Past this size the log moves to `dictation.log.1` (replacing the
/// previous one) and starts over.
const MAX_BYTES: u64 = 1024 * 1024;
/// Longest line kept; the rest is cut.
const MAX_LINE_CHARS: usize = 300;

static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();
static WRITE_LOCK: Mutex<()> = Mutex::new(());

/// Point the log at `<app data>/logs/dictation.log`. Until this runs,
/// [`log`] does nothing.
pub fn init(app: &tauri::AppHandle) {
    use tauri::Manager;
    if let Ok(dir) = app.path().app_data_dir() {
        let dir = dir.join("logs");
        let _ = std::fs::create_dir_all(&dir);
        let _ = LOG_PATH.set(dir.join("dictation.log"));
    }
}

/// Append one line, prefixed with the UTC time. Never fails: a log that
/// can't be written is skipped.
pub fn log(message: &str) {
    let Some(path) = LOG_PATH.get() else { return };
    let line: String = message
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(MAX_LINE_CHARS)
        .collect();
    let _guard = WRITE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if std::fs::metadata(path).map(|m| m.len() >= MAX_BYTES).unwrap_or(false) {
        let _ = std::fs::rename(path, path.with_extension("log.1"));
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{} {}", timestamp(), line);
    }
}

/// `2026-10-29 14:03:12.345Z`.
fn timestamp() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs();
    let (y, m, d) = civil_from_days((secs / 86_400) as i64);
    let rem = secs % 86_400;
    format!(
        "{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        now.subsec_millis()
    )
}

/// Days since 1970-01-01 to (year, month, day), proleptic Gregorian
/// (Howard Hinnant's `civil_from_days`). Avoids a date crate for one line.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

#[cfg(test)]
mod tests {
    use super::{civil_from_days, log, LOG_PATH, MAX_BYTES};

    #[test]
    fn appends_one_line_and_rotates_past_the_cap() {
        let dir = std::env::temp_dir().join(format!("voloz-dictation-log-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("dictation.log");
        LOG_PATH.set(path.clone()).unwrap();

        log("chord start\nwith a newline");
        let text = std::fs::read_to_string(&path).unwrap();
        assert_eq!(text.lines().count(), 1);
        assert!(text.trim_end().ends_with("chord start with a newline"));

        std::fs::write(&path, vec![b'x'; MAX_BYTES as usize]).unwrap();
        log("after rotation");
        assert_eq!(std::fs::metadata(dir.join("dictation.log.1")).unwrap().len(), MAX_BYTES);
        assert!(std::fs::read_to_string(&path).unwrap().ends_with("after rotation\n"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn civil_from_days_matches_known_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(11_016), (2000, 2, 29));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
        assert_eq!(civil_from_days(20_755), (2026, 10, 29));
    }
}
