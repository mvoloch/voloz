import { invoke } from '@tauri-apps/api/core';
import { useEffect, useRef, useState } from 'react';
import { hotkeyCommand } from '@/lib/hooks/useChordSync';
import { useDictationReadiness } from '@/lib/hooks/useDictationReadiness';
import { useCaptureSettings } from '@/lib/hooks/useSettings';
import { dictationLog } from '@/lib/utils/dictationLog';
import { usePlatform } from '@/platform/PlatformContext';
import { useServerStore } from '@/stores/serverStore';

const mainWindowOpen = () => invoke<boolean>('main_window_open').catch(() => true);

/**
 * What the main window's shell does at launch, for a launch without one.
 *
 * Lite doesn't build the main window when it starts at login (it waits in
 * the tray; see `"create": false` in tauri.lite.conf.json), but dictation
 * still needs the server running, the keep-running choice in Rust and the
 * hotkey armed with the saved chords. The dictate window, always present,
 * does that when it loads and finds no main window. Everything it sets up
 * lives in Rust, so it lasts until the app quits; a main window opened
 * later runs its own boot (`start_server` waits for this one and returns
 * the running server) and keeps the chords in sync from then on.
 *
 * Mounted by `DictateWindow`; renders nothing.
 */
export function BootWithoutMainWindow() {
  const platform = usePlatform();
  const [phase, setPhase] = useState<'checking' | 'arming' | 'done'>('checking');

  useEffect(() => {
    if (!platform.metadata.isTauri) {
      setPhase('done');
      return;
    }
    let cancelled = false;
    (async () => {
      if (await mainWindowOpen()) {
        if (!cancelled) setPhase('done');
        return;
      }
      dictationLog('no main window: starting the server from the dictate window');
      const { keepServerRunningOnClose, mode, customModelsDir } = useServerStore.getState();
      platform.lifecycle.setKeepServerRunning(keepServerRunningOnClose).catch(() => {});
      // Same rule as the main window: a dev build uses a server run by hand.
      if (import.meta.env?.PROD) {
        try {
          const url = await platform.lifecycle.startServer(mode === 'remote', customModelsDir);
          useServerStore.getState().setServerUrl(url);
          window.__voiceboxServerStartedByApp = true;
          dictationLog('no main window: server ready');
        } catch (err) {
          // Arming still waits for the server to answer (an external one,
          // or none: then the hotkey stays off, as in the main window).
          dictationLog(
            `no main window: server start failed (${err instanceof Error ? err.message : String(err)})`,
          );
        }
      }
      if (!cancelled) setPhase('arming');
    })();
    return () => {
      cancelled = true;
    };
  }, [platform]);

  return phase === 'arming' ? <ArmHotkeyOnce onDone={() => setPhase('done')} /> : null;
}

/**
 * `useChordSync`, once: the first command that matches loaded settings and
 * readiness. Mounted only after the server is up, so these queries start
 * against a live server (their cache may hold an error from the pill's
 * earlier attempts; mounting refetches it).
 */
function ArmHotkeyOnce({ onDone }: { onDone: () => void }) {
  const { settings } = useCaptureSettings();
  const readiness = useDictationReadiness();
  const loaded = readiness.stt !== undefined;
  const sync = loaded ? hotkeyCommand(settings, readiness.canRecord) : null;
  const sent = useRef(false);

  useEffect(() => {
    if (!sync || sent.current) return;
    sent.current = true;
    void (async () => {
      // Opened meanwhile: the main window's useChordSync owns the chords.
      if (!(await mainWindowOpen())) {
        try {
          await invoke(sync.command, sync.args);
          dictationLog(`no main window: ${sync.command}`);
        } catch (err) {
          dictationLog(
            `no main window: ${sync.command} failed (${err instanceof Error ? err.message : String(err)})`,
          );
        }
      }
      onDone();
    })();
  }, [sync, onDone]);

  return null;
}
