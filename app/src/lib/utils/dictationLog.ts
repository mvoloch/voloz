import { invoke } from '@tauri-apps/api/core';

/**
 * Append a line to the desktop app's ``dictation.log`` (next to
 * ``watchdog.log``), to diagnose a dictation that went wrong. Only what
 * happened — never transcript text, keys or window titles. Never awaited;
 * a no-op outside Tauri.
 */
export function dictationLog(message: string): void {
  invoke('dictation_log', { message }).catch(() => {
    /* not running inside Tauri; nothing to log to */
  });
}
