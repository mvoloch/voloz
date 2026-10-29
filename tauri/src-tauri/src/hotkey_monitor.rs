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
//! Left- and right-hand modifier variants are kept distinct all the way
//! down to the OS event tap (keytap's core promise). Defaults bind to
//! right-hand Cmd + right-hand Option on macOS / right-hand Ctrl +
//! right-hand Shift on Windows so the usual left-hand shortcuts stay
//! with the OS / app.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
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
    pub fn spawn(app: AppHandle, bindings: Bindings) -> Self {
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
    /// no reason.
    pub fn update_bindings(&mut self, bindings: Bindings) {
        self.apply(bindings);
    }

    /// Rebuild the matcher with the current bindings, dropping any chord
    /// state keytap holds. A hands-free (Toggle) chord stays active in
    /// keytap until it is pressed again, and suppresses push-to-talk
    /// meanwhile — so a recording stopped another way (the pill's stop
    /// button, the length cap) would leave the next push-to-talk press
    /// ignored.
    pub fn reset(&mut self) {
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
            .spawn(move || dispatcher_loop(app, matcher, shutdown_for_thread))
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

/// Stop the dictation like a chord release would. `source` tells the pill
/// it was not a chord, so it resets a hands-free toggle left on in keytap.
fn stop_on_escape(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(DICTATE_WINDOW_LABEL) {
        let _ = window.emit("dictate:stop", serde_json::json!({ "source": "escape" }));
        dictation_log::log("[rust] Esc while recording: dictate:stop sent");
    }
}

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
    shutdown: Arc<AtomicBool>,
) {
    while !shutdown.load(Ordering::Relaxed) {
        match matcher.recv_timeout(Duration::from_millis(100)) {
            Ok(event) => process_event(&app, &matcher, event),
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
    event: ChordEvent<ChordAction>,
) {
    match event {
        ChordEvent::Start { id, .. } => {
            apply_effect(app, Effect::StartRecording(id));
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
                    apply_effect(app, Effect::RestartRecording(start_id));
                }
                Ok(other) => {
                    apply_effect(app, Effect::StopRecording(end_id));
                    // The peeked event wasn't a transition partner;
                    // process it in its own right. Recursion depth is
                    // bounded by the number of back-to-back chord
                    // events, in practice 1–2.
                    process_event(app, matcher, other);
                }
                Err(_) => {
                    apply_effect(app, Effect::StopRecording(end_id));
                }
            }
        }
    }
}

// ========================================================================
// Effect → Tauri
// ========================================================================

fn apply_effect(app: &AppHandle, effect: Effect) {
    match effect {
        Effect::StartRecording(action) => {
            // Snapshot focus BEFORE we touch the window — any AppKit
            // reshuffle triggered by set_position / show could in principle
            // steal key focus and poison the reading. In practice those
            // calls leave keyWindow alone, but capturing first is free.
            let focus = focus_capture::capture_focus().ok();
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
                        let y = monitor_pos.y + (monitor_size.height as f64 * 0.04) as i32;
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
                let _ = window.show();
                let payload = serde_json::json!({
                    "focus": focus,
                    "mode": if command { "command" } else { "dictation" },
                    "selection": selection,
                    // Started by the hands-free (toggle) chord: the pill says so.
                    "hands_free": action == ChordAction::ToggleToTalk,
                });
                let _ = window.emit("dictate:start", payload);
                dictation_log::log(&format!("[rust] chord start ({action:?}): dictate:start sent"));
            } else {
                dictation_log::log(&format!("[rust] chord start ({action:?}): no dictate window"));
            }
        }
        Effect::StopRecording(action) => {
            if let Some(window) = app.get_webview_window(DICTATE_WINDOW_LABEL) {
                let _ = window.emit("dictate:stop", ());
                dictation_log::log(&format!("[rust] chord stop ({action:?}): dictate:stop sent"));
            }
        }
        Effect::RestartRecording(action) => {
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
