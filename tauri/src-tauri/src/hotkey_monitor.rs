//! Global hotkey → dictation effect bridge.
//!
//! Thin adapter from `keytap::chord::ChordMatcher` to Tauri events. keytap
//! owns the OS event tap + the chord state machine (Momentary vs Toggle,
//! longest-match resolution, sticky-toggle semantics); this module's only
//! job is:
//!
//!   1. Build a `ChordMatcher` from the user's saved PTT + Toggle chords.
//!   2. Translate `ChordEvent` → voicebox's [`Effect`] on a dispatcher
//!      thread.
//!   3. Fan [`Effect`]s out into Tauri events + dictate-window show/hide.
//!
//! The [`Effect::RestartRecording`] signal is emitted when keytap fires
//! `End(PTT)` and `Start(Toggle)` with the *same* [`Instant`] — which
//! happens when the held set upgrades from a shorter chord to a longer
//! superset in a single event (the classic PTT→hands-free transition).
//! We detect the pair with a 5 ms peek on the matcher's receiver and
//! coalesce into one `Restart` so hosts can discard the transition-
//! moment audio rather than treat it as an unrelated Stop+Start pair.
//!
//! Esc stops a recording dictation (see [`EscapeWatcher`]).
//!
//! The chord in progress is tracked here too ([`ChordState`]), not only in
//! keytap: rebuilding the matcher drops keytap's state, and with it the
//! release of a chord still held. A rebuild while a chord is in progress
//! ends that dictation with a stop; on Windows a push-to-talk release
//! that never arrives is caught by polling the keys ([`watch_release`]).
//!
//! Left- and right-hand modifier variants are kept distinct all the way
//! down to the OS event tap (keytap's core promise). Defaults bind to
//! right-hand Cmd + right-hand Option on macOS / right-hand Ctrl +
//! right-hand Shift on Windows so the usual left-hand shortcuts stay
//! with the OS / app.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use keytap::chord::{Chord, ChordEvent, ChordMatcher};
use keytap::{EventKind, Key, RecvTimeoutError, Tap};
use tauri::{AppHandle, Emitter, Manager};

use crate::dictation_log;
use crate::focus_capture;
use crate::DICTATE_WINDOW_LABEL;

// ========================================================================
// Public types
// ========================================================================

/// Semantic action a chord can be bound to. `PushToTalk` = hold chord to
/// record, release to stop. `ToggleToTalk` = press chord to start recording,
/// press again to stop. `Command` = hold to speak an instruction applied to
/// the selected text (or a draft when nothing is selected), release to stop.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ChordAction {
    PushToTalk,
    ToggleToTalk,
    Command,
}

/// Effect produced after the chord matcher resolves an event. Hosts
/// translate these into UI / recorder calls.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Effect {
    StartRecording(ChordAction),
    StopRecording(ChordAction),
    /// Emitted when a push-to-talk chord is "upgraded" into the toggle
    /// chord mid-hold — hosts may want to discard the captured audio and
    /// restart so the transition moment isn't in the recording.
    RestartRecording(ChordAction),
}

/// Chord key sets from capture settings. Both actions use the same
/// `HashSet<Key>` shape so callers don't need to know about keytap's
/// `Chord` type.
pub type Bindings = HashMap<ChordAction, HashSet<Key>>;

// ========================================================================
// Monitor
// ========================================================================

pub struct HotkeyMonitor {
    app: AppHandle,
    active: Option<Active>,
    bindings: Bindings,
    /// Kept across [`Self::reset`]: it holds no chord state.
    escape: Option<EscapeWatcher>,
}

struct Active {
    dispatcher: JoinHandle<()>,
    shutdown: Arc<AtomicBool>,
}

impl HotkeyMonitor {
    /// Build the monitor with initial bindings. Equivalent to constructing
    /// an empty monitor and calling [`Self::update_bindings`] once.
    pub fn spawn(app: AppHandle, bindings: Bindings, caller: &str) -> Self {
        dictation_log::log(&format!(
            "[rust] hotkey: spawn (from {caller}, chord active={})",
            describe(active_chord())
        ));
        let mut m = Self {
            app,
            active: None,
            bindings: Bindings::new(),
            escape: None,
        };
        m.apply(bindings);
        m
    }

    /// Swap in a fresh set of chord bindings. Tears down the existing
    /// `ChordMatcher` (which stops keytap's chord worker thread and
    /// closes the OS tap) and spawns a new one. No-op for the "all
    /// empty" case so "disable hotkey" doesn't keep a tap running for
    /// no reason, and when the bindings are the ones already armed: the
    /// main window sends them again each time it opens.
    ///
    /// A rebuild while a chord is in progress ends that dictation with a
    /// stop. The new matcher never saw the chord pressed, so it would
    /// ignore its release (and a hands-free chord pressed again would start
    /// a second dictation instead of stopping this one); keytap can't be
    /// handed the keys already held.
    pub fn update_bindings(&mut self, bindings: Bindings, caller: &str) {
        let in_progress = active_chord();
        let armed = self.active.is_some() || bindings.values().all(|set| set.is_empty());
        if bindings == self.bindings && armed {
            dictation_log::log(&format!(
                "[rust] hotkey: update_bindings (from {caller}, chord active={}): unchanged, matcher kept",
                describe(in_progress)
            ));
            return;
        }
        dictation_log::log(&format!(
            "[rust] hotkey: update_bindings (from {caller}, chord active={}): rebuilding",
            describe(in_progress)
        ));
        if let Some(action) = in_progress {
            if end_chord(None) {
                send_stop(&self.app, "rebuild");
                dictation_log::log(&format!(
                    "[rust] hotkey: {action:?} in progress during the rebuild: dictate:stop sent"
                ));
            }
        }
        self.apply(bindings);
    }

    /// Rebuild the matcher with the current bindings, dropping any chord
    /// state keytap holds. A hands-free (Toggle) chord stays active in
    /// keytap until it is pressed again, and suppresses push-to-talk
    /// meanwhile — so a recording stopped another way (the pill's stop
    /// button, the length cap) would leave the next push-to-talk press
    /// ignored. The recording has already ended, so the chord in progress
    /// is forgotten without a stop.
    pub fn reset(&mut self, caller: &str) {
        dictation_log::log(&format!(
            "[rust] hotkey: reset (from {caller}, chord active={})",
            describe(active_chord())
        ));
        end_chord(None);
        let bindings = self.bindings.clone();
        self.apply(bindings);
    }

    fn apply(&mut self, bindings: Bindings) {
        self.bindings = bindings.clone();
        // Tear down any existing matcher + dispatcher first. The
        // dispatcher sees the shutdown flag on its next recv_timeout
        // (≤100ms) and returns; joining waits for that. Dropping the
        // ChordMatcher stops keytap's chord-worker thread and the
        // underlying Tap.
        if let Some(active) = self.active.take() {
            active.shutdown.store(true, Ordering::Relaxed);
            let _ = active.dispatcher.join();
        }

        if bindings.values().all(|set| set.is_empty()) {
            self.escape = None;
            return;
        }

        let matcher = match build_matcher(&bindings) {
            Ok(m) => m,
            Err(err) => {
                eprintln!(
                    "HotkeyMonitor: ChordMatcher build failed ({err}). Global chord detection is disabled. On macOS, grant Input Monitoring in System Settings → Privacy & Security → Input Monitoring and relaunch."
                );
                return;
            }
        };

        let shutdown = Arc::new(AtomicBool::new(false));
        let shutdown_for_thread = shutdown.clone();
        let app = self.app.clone();
        let dispatcher = thread::Builder::new()
            .name("voicebox-hotkey-dispatcher".into())
            .spawn(move || dispatcher_loop(app, matcher, bindings, shutdown_for_thread))
            .expect("spawn hotkey dispatcher thread");

        self.active = Some(Active { dispatcher, shutdown });

        if self.escape.is_none() {
            self.escape = EscapeWatcher::spawn(self.app.clone());
        }
    }
}

impl Drop for HotkeyMonitor {
    fn drop(&mut self) {
        if let Some(active) = self.active.take() {
            active.shutdown.store(true, Ordering::Relaxed);
            let _ = active.dispatcher.join();
        }
    }
}

// ========================================================================
// Esc stops a recording dictation
// ========================================================================

/// Whether the dictate pill has a recording in progress, mirrored from its
/// webview (`set_dictation_recording`). Esc stops a dictation only while
/// this is set, so the rest of the time it is just a key.
static DICTATION_RECORDING: AtomicBool = AtomicBool::new(false);

pub fn set_dictation_recording(recording: bool) {
    DICTATION_RECORDING.store(recording, Ordering::Relaxed);
}

/// Esc stops a recording dictation and transcribes it, as releasing the
/// chord does. The pill never takes keyboard focus, so the key comes from a
/// global tap, which only observes: Esc still reaches the focused app.
///
/// It is a second raw `keytap::Tap` rather than an Esc chord in the
/// `ChordMatcher`: while a hands-free (Toggle) chord is active the matcher
/// suppresses every other chord (keytap's
/// `toggle_suppresses_other_chords_while_active` test), which is exactly
/// when Esc is needed, and a held longer chord would win over a one-key one
/// anyway. keytap documents two taps as the way to observe raw keys next to
/// a matcher.
struct EscapeWatcher {
    thread: Option<JoinHandle<()>>,
    shutdown: Arc<AtomicBool>,
}

impl EscapeWatcher {
    fn spawn(app: AppHandle) -> Option<Self> {
        let tap = match Tap::new() {
            Ok(tap) => tap,
            Err(err) => {
                eprintln!("HotkeyMonitor: Esc tap failed ({err}); Esc will not stop a dictation.");
                return None;
            }
        };
        let shutdown = Arc::new(AtomicBool::new(false));
        let shutdown_for_thread = shutdown.clone();
        let thread = thread::Builder::new()
            .name("voloz-escape-watcher".into())
            .spawn(move || {
                // Dropping the tap at the end closes the OS hook.
                while !shutdown_for_thread.load(Ordering::Relaxed) {
                    match tap.recv_timeout(Duration::from_millis(100)) {
                        Ok(event) => {
                            if event.kind == EventKind::KeyDown(Key::Escape)
                                && DICTATION_RECORDING.load(Ordering::Relaxed)
                            {
                                stop_on_escape(&app);
                            }
                        }
                        Err(RecvTimeoutError::Timeout) => continue,
                        Err(RecvTimeoutError::Disconnected) => break,
                    }
                }
            });
        match thread {
            Ok(thread) => Some(Self { thread: Some(thread), shutdown }),
            Err(err) => {
                eprintln!("HotkeyMonitor: Esc watcher thread failed ({err}).");
                None
            }
        }
    }
}

impl Drop for EscapeWatcher {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn stop_on_escape(app: &AppHandle) {
    if send_stop(app, "escape") {
        dictation_log::log("[rust] Esc while recording: dictate:stop sent");
    }
}

/// Stop the dictation like a chord release would. `source` tells the pill
/// it was not a chord, so it resets the chord state left in keytap (a
/// hands-free toggle still on, or keys it still thinks are held).
fn send_stop(app: &AppHandle, source: &str) -> bool {
    match app.get_webview_window(DICTATE_WINDOW_LABEL) {
        Some(window) => {
            let _ = window.emit("dictate:stop", serde_json::json!({ "source": source }));
            true
        }
        None => false,
    }
}

// ========================================================================
// The chord in progress
// ========================================================================

/// The chord whose `dictate:start` went out and whose stop hasn't. Kept
/// across matcher rebuilds, which drop keytap's own state.
struct ChordState {
    action: Option<ChordAction>,
    /// Bumped on every start, end and restart, so a [`watch_release`]
    /// started for one chord press can tell it is out of date.
    generation: u64,
}

static CHORD: Mutex<ChordState> = Mutex::new(ChordState { action: None, generation: 0 });

fn chord_state() -> std::sync::MutexGuard<'static, ChordState> {
    CHORD.lock().unwrap_or_else(|e| e.into_inner())
}

fn active_chord() -> Option<ChordAction> {
    chord_state().action
}

/// Record a chord start (or the action a restart switched to); returns its
/// generation.
fn begin_chord(action: ChordAction) -> u64 {
    let mut state = chord_state();
    state.action = Some(action);
    state.generation += 1;
    state.generation
}

/// Forget the chord in progress — only if it is still the press of
/// `generation`, when given. Returns whether one was in progress.
fn end_chord(generation: Option<u64>) -> bool {
    let mut state = chord_state();
    if generation.is_some_and(|g| g != state.generation) {
        return false;
    }
    let was = state.action.take().is_some();
    state.generation += 1;
    was
}

fn describe(action: Option<ChordAction>) -> String {
    action.map_or_else(|| "none".to_string(), |a| format!("{a:?}"))
}

/// Push-to-talk and voice commands end when the chord is released;
/// hands-free runs until its chord is pressed again.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn ends_on_release(action: ChordAction) -> bool {
    matches!(action, ChordAction::PushToTalk | ChordAction::Command)
}

/// How often [`watch_release`] reads the keys, and how long all of them
/// must be up before it stops the dictation itself. keytap's release
/// normally arrives within milliseconds, well before this.
#[cfg(target_os = "windows")]
const RELEASE_POLL: Duration = Duration::from_millis(100);
#[cfg(target_os = "windows")]
const RELEASE_GRACE: Duration = Duration::from_millis(300);

/// Safety net for a lost release: while a hold-to-talk chord is in
/// progress, read the physical state of its keys every 100 ms. If none of
/// them has been down for 300 ms and keytap still hasn't reported the
/// release, stop the dictation. Ends as soon as the chord ends, turns into
/// hands-free, or the recording stops another way; nothing runs between
/// dictations. Windows only (`GetAsyncKeyState`); elsewhere a no-op.
#[cfg(target_os = "windows")]
fn watch_release(app: &AppHandle, action: ChordAction, keys: Option<&HashSet<Key>>, generation: u64) {
    use std::time::Instant;

    if !ends_on_release(action) {
        return;
    }
    // A key without a virtual-key code can't be read; the others are
    // enough, since releasing any key of the chord ends it.
    let vks: Vec<u16> = keys
        .into_iter()
        .flatten()
        .filter_map(|key| crate::key_codes::windows_vk(*key))
        .collect();
    if vks.is_empty() {
        return;
    }
    let app = app.clone();
    let spawned = thread::Builder::new()
        .name("voloz-chord-release-watch".into())
        .spawn(move || {
            let mut last_down = Instant::now();
            let mut seen_recording = false;
            loop {
                thread::sleep(RELEASE_POLL);
                if chord_state().generation != generation {
                    return;
                }
                // Stopped another way (the pill's button, Esc, the cap):
                // nothing left to stop.
                if DICTATION_RECORDING.load(Ordering::Relaxed) {
                    seen_recording = true;
                } else if seen_recording {
                    return;
                }
                if crate::synthetic_keys::any_key_down(&vks) {
                    last_down = Instant::now();
                } else if last_down.elapsed() >= RELEASE_GRACE {
                    if end_chord(Some(generation)) && send_stop(&app, "safety") {
                        dictation_log::log(&format!(
                            "[rust] safety stop: chord keys released but no release event ({action:?}): dictate:stop sent"
                        ));
                    }
                    return;
                }
            }
        });
    if let Err(err) = spawned {
        eprintln!("HotkeyMonitor: release watch thread failed ({err}).");
    }
}

#[cfg(not(target_os = "windows"))]
fn watch_release(_app: &AppHandle, _action: ChordAction, _keys: Option<&HashSet<Key>>, _generation: u64) {}

// ========================================================================
// Matcher construction + dispatch
// ========================================================================

fn build_matcher(bindings: &Bindings) -> Result<ChordMatcher<ChordAction>, keytap::Error> {
    let mut builder = ChordMatcher::builder();
    if let Some(keys) = bindings.get(&ChordAction::PushToTalk) {
        if !keys.is_empty() {
            builder = builder.add(
                ChordAction::PushToTalk,
                Chord::of(keys.iter().copied()),
            );
        }
    }
    if let Some(keys) = bindings.get(&ChordAction::ToggleToTalk) {
        if !keys.is_empty() {
            builder = builder.add_toggle(
                ChordAction::ToggleToTalk,
                Chord::of(keys.iter().copied()),
            );
        }
    }
    if let Some(keys) = bindings.get(&ChordAction::Command) {
        if !keys.is_empty() {
            builder = builder.add(ChordAction::Command, Chord::of(keys.iter().copied()));
        }
    }
    builder.build()
}

fn dispatcher_loop(
    app: AppHandle,
    matcher: ChordMatcher<ChordAction>,
    bindings: Bindings,
    shutdown: Arc<AtomicBool>,
) {
    while !shutdown.load(Ordering::Relaxed) {
        match matcher.recv_timeout(Duration::from_millis(100)) {
            Ok(event) => process_event(&app, &matcher, &bindings, event),
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
}

/// Turn a single [`ChordEvent`] into zero or one [`Effect`]s, peeking at
/// the matcher once for a same-Instant follow-up so upgrade transitions
/// coalesce into [`Effect::RestartRecording`] instead of a Stop+Start
/// pair.
fn process_event(
    app: &AppHandle,
    matcher: &ChordMatcher<ChordAction>,
    bindings: &Bindings,
    event: ChordEvent<ChordAction>,
) {
    match event {
        ChordEvent::Start { id, .. } => {
            apply_effect(app, bindings, Effect::StartRecording(id));
        }
        ChordEvent::End { id: end_id, time: end_time } => {
            // Peek for an immediately-following Start. keytap emits
            // End+Start atomically (same Instant) when the held set
            // transitions between registered chords — our 5 ms window
            // is well under perceptible latency but far longer than the
            // channel hop between keytap's chord worker and our
            // dispatcher.
            match matcher.recv_timeout(Duration::from_millis(5)) {
                Ok(ChordEvent::Start { id: start_id, time: start_time })
                    if start_time == end_time =>
                {
                    apply_effect(app, bindings, Effect::RestartRecording(start_id));
                }
                Ok(other) => {
                    apply_effect(app, bindings, Effect::StopRecording(end_id));
                    // The peeked event wasn't a transition partner;
                    // process it in its own right. Recursion depth is
                    // bounded by the number of back-to-back chord
                    // events, in practice 1–2.
                    process_event(app, matcher, bindings, other);
                }
                Err(_) => {
                    apply_effect(app, bindings, Effect::StopRecording(end_id));
                }
            }
        }
    }
}

// ========================================================================
// Effect → Tauri
// ========================================================================

fn apply_effect(app: &AppHandle, bindings: &Bindings, effect: Effect) {
    match effect {
        Effect::StartRecording(action) => {
            let generation = begin_chord(action);
            // Snapshot focus BEFORE we touch the window — any AppKit
            // reshuffle triggered by set_position / show could in principle
            // steal key focus and poison the reading. In practice those
            // calls leave keyWindow alone, but capturing first is free.
            let focus = focus_capture::capture_focus().ok();
            // Linux pastes through a virtual keyboard the compositor needs a
            // moment to pick up: create it while the user speaks.
            #[cfg(target_os = "linux")]
            std::thread::spawn(crate::synthetic_keys::prepare_paste);
            // A voice command works on the selection. UIAutomation reads it
            // now; `None` (no TextPattern) makes the pill fall back to a
            // synthetic copy once the chord is released.
            let command = action == ChordAction::Command;
            let selection = if command { focus_capture::read_selection() } else { None };

            if let Some(window) = app.get_webview_window(DICTATE_WINDOW_LABEL) {
                // The previous hide-cycle parked the window off-screen and
                // made it click-through — undo both before showing, so the
                // pill lands at top-center and the user can actually click
                // the error pill / stop button.
                //
                // `current_monitor()` returns None when the window is off
                // any display (our hide handler parks it at -10_000, -10_000
                // precisely so it never intercepts clicks), so fall back to
                // the primary monitor for the reposition.
                let monitor = window
                    .current_monitor()
                    .ok()
                    .flatten()
                    .or_else(|| window.primary_monitor().ok().flatten());
                if let Some(monitor) = monitor {
                    let monitor_pos = monitor.position();
                    let monitor_size = monitor.size();
                    if let Ok(win_size) = window.outer_size() {
                        let x = monitor_pos.x
                            + (monitor_size.width as i32 - win_size.width as i32) / 2;
                        let y = monitor_pos.y + (monitor_size.height as f64 * crate::PILL_TOP_FRACTION) as i32;
                        let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
                    }
                }
                // Skip on Linux: aborts if the window was never realized
                // (see show_dictate_window in main.rs).
                #[cfg(not(target_os = "linux"))]
                let _ = window.set_ignore_cursor_events(false);
                // Deliberately no set_focus() — taking key focus would yank
                // it out of whatever app the user was typing in, which is
                // the opposite of what a dictation overlay should do.
                crate::show_pill(&window);
                let payload = serde_json::json!({
                    "focus": focus,
                    "mode": if command { "command" } else { "dictation" },
                    "selection": selection,
                    // Started by the hands-free (toggle) chord: the pill says so.
                    "hands_free": action == ChordAction::ToggleToTalk,
                });
                let _ = window.emit("dictate:start", payload);
                dictation_log::log(&format!("[rust] chord start ({action:?}): dictate:start sent"));
                watch_release(app, action, bindings.get(&action), generation);
            } else {
                end_chord(Some(generation));
                dictation_log::log(&format!("[rust] chord start ({action:?}): no dictate window"));
            }
        }
        Effect::StopRecording(action) => {
            end_chord(None);
            if let Some(window) = app.get_webview_window(DICTATE_WINDOW_LABEL) {
                let _ = window.emit("dictate:stop", ());
                dictation_log::log(&format!("[rust] chord stop ({action:?}): dictate:stop sent"));
            }
        }
        Effect::RestartRecording(action) => {
            // The release watch of the push-to-talk press ends here; a
            // hands-free chord gets none.
            let generation = begin_chord(action);
            watch_release(app, action, bindings.get(&action), generation);
            if let Some(window) = app.get_webview_window(DICTATE_WINDOW_LABEL) {
                // Holding push-to-talk and adding the rest of the hands-free
                // chord upgrades the recording in progress; the pill shows
                // the mode, since it now runs until the chord is pressed again.
                let payload = serde_json::json!({
                    "hands_free": action == ChordAction::ToggleToTalk,
                });
                let _ = window.emit("dictate:restart", payload);
                dictation_log::log(&format!("[rust] chord restart ({action:?}): dictate:restart sent"));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chord_state_follows_the_press_in_progress() {
        let push = begin_chord(ChordAction::PushToTalk);
        assert_eq!(active_chord(), Some(ChordAction::PushToTalk));
        // Upgraded to hands-free: the push-to-talk press's release watch is
        // out of date and can no longer end it.
        let hands_free = begin_chord(ChordAction::ToggleToTalk);
        assert!(hands_free > push);
        assert!(!end_chord(Some(push)));
        assert_eq!(active_chord(), Some(ChordAction::ToggleToTalk));
        assert!(end_chord(None));
        assert_eq!(active_chord(), None);
        assert!(!end_chord(None));

        assert!(ends_on_release(ChordAction::PushToTalk));
        assert!(ends_on_release(ChordAction::Command));
        assert!(!ends_on_release(ChordAction::ToggleToTalk));
    }
}
