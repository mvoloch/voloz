import { useCallback, useEffect, useRef, useState } from 'react';
import i18n from '@/i18n';
import { usePlatform } from '@/platform/PlatformContext';
import { type MicTake, startMicTake, takeToWav } from '@/lib/hooks/micCapture';
import {
  RecordingLifecycle,
  type RecordingEndReason,
  type StoppableRecorder,
} from '@/lib/hooks/recorderLifecycle';
import { concatPcm, pcmToWav } from '@/lib/utils/pcm';

export type { RecordingEndReason } from '@/lib/hooks/recorderLifecycle';

/** The user-facing message for a getUserMedia / recorder failure. */
function microphoneErrorMessage(err: unknown): string {
  if (err instanceof DOMException && err.name === 'NotAllowedError') {
    return i18n.t('common.microphone.permissionDenied');
  }
  if (err instanceof DOMException && err.name === 'NotFoundError') {
    return i18n.t('common.microphone.notFound');
  }
  return err instanceof Error ? err.message : i18n.t('common.microphone.accessFailed');
}

// Resampling the recording normally takes well under a second. If it hangs
// (an AudioContext that never decodes), send it at the device's rate.
const WAV_CONVERSION_TIMEOUT_MS = 10_000;
// A stop that arrives while the microphone is still opening is applied once
// it opens. If it never opens, give up on that start after this long so the
// pill doesn't sit on "Recording" waiting for it.
const START_GIVE_UP_MS = 5000;

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error(`timed out after ${ms} ms`)), ms);
    promise.then(
      (value) => {
        window.clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        window.clearTimeout(timer);
        reject(err);
      },
    );
  });
}

/** One recording: its samples and whether it was cancelled. */
interface Take {
  mic: MicTake | null;
  startedAt: number | null;
  duration?: number;
  cancelled: boolean;
}

interface UseAudioRecordingOptions {
  maxDurationSeconds?: number;
  /**
   * Rate of the WAV handed over (mono either way). Unset: the microphone's
   * own rate. Dictation asks for 16 kHz, what speech-to-text works at.
   */
  wavSampleRate?: number;
  /**
   * Keep the microphone open this long after a recording, so the next one
   * starts at once with a little audio from before it (``micCapture.ts``).
   * 0 (default): close it when the recording ends.
   */
  keepMicWarmMs?: number;
  /**
   * The recorded audio. ``reason`` says how the recording ended: ``stopped``
   * by a request, or by itself (``device-ended``: the microphone went away;
   * ``error``: the recorder failed). It is delivered in every case.
   */
  onRecordingComplete?: (blob: Blob, duration?: number, reason?: RecordingEndReason) => void;
  /**
   * The recording ended and ``isRecording`` is now false; its audio follows
   * through ``onRecordingComplete`` once converted.
   */
  onRecordingEnded?: (reason: RecordingEndReason, duration?: number) => void;
  /** Diagnostics (the dictation log). Never audio or text. */
  log?: (message: string) => void;
}

export function useAudioRecording({
  maxDurationSeconds,
  wavSampleRate,
  keepMicWarmMs = 0,
  onRecordingComplete,
  onRecordingEnded,
  log,
}: UseAudioRecordingOptions = {}) {
  const platform = usePlatform();
  const [isRecording, setIsRecording] = useState(false);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  // The microphone's level analyser while recording, for a level meter.
  const [analyser, setAnalyser] = useState<AnalyserNode | null>(null);
  const takeRef = useRef<Take | null>(null);
  const lifecycleRef = useRef<RecordingLifecycle | null>(null);
  const timerRef = useRef<number | null>(null);
  // getUserMedia can take a while, and a hotkey tap is shorter than that.
  // Track the wait so a second start is ignored and a stop that arrives
  // before the recorder exists is applied as soon as it does.
  const startingRef = useRef<boolean>(false);
  const stopRequestedRef = useRef<boolean>(false);
  // Bumped when a pending start is given up, so its late getUserMedia
  // closes the stream instead of recording.
  const startIdRef = useRef(0);
  const giveUpTimerRef = useRef<number | null>(null);

  // The recorder's events outlive the render that created them; read the
  // latest callbacks.
  const onCompleteRef = useRef(onRecordingComplete);
  onCompleteRef.current = onRecordingComplete;
  const onEndedRef = useRef(onRecordingEnded);
  onEndedRef.current = onRecordingEnded;
  const logRef = useRef(log);
  logRef.current = log;
  const writeLog = useCallback((message: string) => logRef.current?.(message), []);

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const clearGiveUpTimer = useCallback(() => {
    if (giveUpTimerRef.current !== null) {
      window.clearTimeout(giveUpTimerRef.current);
      giveUpTimerRef.current = null;
    }
  }, []);

  const startRecording = useCallback(async () => {
    const requestedAt = performance.now();
    if (startingRef.current) return;
    if (lifecycleRef.current && !lifecycleRef.current.ended) return;
    startingRef.current = true;
    stopRequestedRef.current = false;
    const startId = ++startIdRef.current;
    const take: Take = { mic: null, startedAt: null, cancelled: false };
    takeRef.current = take;
    try {
      setError(null);
      setDuration(0);

      // Check if getUserMedia is available
      // In Tauri, navigator.mediaDevices might not be available immediately
      if (typeof navigator === 'undefined') {
        const errorMsg =
          'Navigator API is not available. This might be a Tauri configuration issue.';
        setError(errorMsg);
        throw new Error(errorMsg);
      }

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        // Try waiting a bit for Tauri webview to initialize
        await new Promise((resolve) => setTimeout(resolve, 100));

        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
          console.error('MediaDevices check:', {
            hasNavigator: typeof navigator !== 'undefined',
            hasMediaDevices: !!navigator?.mediaDevices,
            hasGetUserMedia: !!navigator?.mediaDevices?.getUserMedia,
            isTauri: platform.metadata.isTauri,
          });

          const errorMsg = platform.metadata.isTauri
            ? i18n.t('common.microphone.unavailableApp')
            : i18n.t('common.microphone.unavailableBrowser');
          setError(errorMsg);
          throw new Error(errorMsg);
        }
      }

      // The open microphone when it is warm (with audio from before the
      // press), else open it. Samples are kept from the first block the
      // stream delivers; there is no encoder to wait for.
      const mic = await startMicTake(writeLog, requestedAt);

      if (startId !== startIdRef.current) {
        // This start was given up while the microphone was opening.
        mic.release(0);
        writeLog('microphone opened after its start was given up: closed');
        return;
      }
      clearGiveUpTimer();
      take.mic = mic;

      // What the lifecycle stops: the take, whose last block arrives a
      // moment after the stop (the recorder's ``stop`` event).
      const recorder: { state: StoppableRecorder['state']; stop(): void } = {
        state: 'recording',
        stop() {
          if (recorder.state === 'inactive') return;
          recorder.state = 'inactive';
          void mic.stop().then(() => lifecycle.recorderStopped());
        },
      };

      const lifecycle = new RecordingLifecycle(recorder, {
        onLeave: (reason) => {
          clearTimer();
          setIsRecording(false);
          setAnalyser(null);
          take.duration = take.startedAt ? (Date.now() - take.startedAt) / 1000 : undefined;
          writeLog(`recording ended (${reason}) after ${(take.duration ?? 0).toFixed(1)} s`);
          onEndedRef.current?.(reason, take.duration);
        },
        onFinish: async (reason) => {
          // A microphone that ended or failed is closed; otherwise it stays
          // open for the next recording when the caller asked for that.
          mic.release(take.cancelled || reason !== 'stopped' ? 0 : keepMicWarmMs);

          // Don't fire completion callback if the recording was cancelled
          if (take.cancelled) return;

          try {
            const wavBlob = await withTimeout(
              takeToWav(mic, wavSampleRate),
              WAV_CONVERSION_TIMEOUT_MS,
            );
            onCompleteRef.current?.(wavBlob, take.duration, reason);
          } catch (err) {
            console.error('Error converting audio to WAV:', err);
            writeLog(
              `wav resampling failed (${err instanceof Error ? err.message : 'error'}): sending ${mic.sampleRate} Hz`,
            );
            const wavBlob = pcmToWav(concatPcm(mic.blocks), mic.sampleRate);
            onCompleteRef.current?.(wavBlob, take.duration, reason);
          }
        },
        log: writeLog,
      });
      lifecycleRef.current = lifecycle;

      // The track may end (device removed, Bluetooth profile switch): end
      // the recording and deliver what was recorded.
      mic.onTrackEnded = () => lifecycle.end('device-ended');

      setIsRecording(true);
      setAnalyser(mic.analyser);
      take.startedAt = Date.now();
      startingRef.current = false;

      if (stopRequestedRef.current) {
        // Released before the mic opened: stop now. The near-empty
        // recording is reported as too short by the caller.
        stopRequestedRef.current = false;
        writeLog('stop requested while the microphone was opening: applied');
        lifecycle.end('stopped');
        return;
      }

      // Start timer
      timerRef.current = window.setInterval(() => {
        if (take.startedAt) {
          const elapsed = (Date.now() - take.startedAt) / 1000;
          setDuration(elapsed);

          // Auto-stop at max duration when the caller opts in — dictation
          // sessions pass a 10 min cap; voice-clone sample recorders pass
          // 29s to keep reference clips short.
          if (maxDurationSeconds !== undefined && elapsed >= maxDurationSeconds) {
            writeLog('length cap reached');
            lifecycle.end('stopped');
          }
        }
      }, 100);
    } catch (err) {
      take.mic?.release(0);
      take.mic = null;
      if (startId === startIdRef.current) {
        startingRef.current = false;
        stopRequestedRef.current = false;
        clearGiveUpTimer();
        setError(microphoneErrorMessage(err));
        setIsRecording(false);
      }
      writeLog(`microphone failed to open: ${err instanceof Error ? err.name : 'error'}`);
    }
  }, [
    maxDurationSeconds,
    wavSampleRate,
    keepMicWarmMs,
    platform.metadata.isTauri,
    clearTimer,
    clearGiveUpTimer,
    writeLog,
  ]);

  const stopRecording = useCallback(() => {
    if (startingRef.current) {
      stopRequestedRef.current = true;
      writeLog('stop requested while the microphone is opening: held');
      if (giveUpTimerRef.current === null) {
        giveUpTimerRef.current = window.setTimeout(() => {
          giveUpTimerRef.current = null;
          if (!startingRef.current) return;
          startIdRef.current += 1;
          startingRef.current = false;
          stopRequestedRef.current = false;
          writeLog(`microphone still not open ${START_GIVE_UP_MS / 1000} s after the stop: start given up`);
          setError(i18n.t('common.microphone.accessFailed'));
        }, START_GIVE_UP_MS);
      }
      return;
    }
    // Ask the lifecycle, not `isRecording`: a release that lands after the
    // mic opened but before React re-rendered sees a stale `false`. And not
    // the recorder's state either: a recorder that stopped by itself is
    // inactive while the recording still has to be closed out.
    const lifecycle = lifecycleRef.current;
    if (lifecycle && !lifecycle.ended) {
      writeLog('stop requested');
      lifecycle.end('stopped');
      return;
    }
    // Nothing in progress; make sure nothing says otherwise.
    clearTimer();
    setIsRecording(false);
    writeLog('stop requested: no recording in progress');
  }, [clearTimer, writeLog]);

  const cancelRecording = useCallback(() => {
    const take = takeRef.current;
    if (take) {
      take.cancelled = true; // Must be set before the recording finishes
    }
    if (startingRef.current) {
      stopRequestedRef.current = true;
      return;
    }
    const lifecycle = lifecycleRef.current;
    if (lifecycle && !lifecycle.ended) lifecycle.end('stopped');
    setIsRecording(false);
    setAnalyser(null);
    setDuration(0);
    clearTimer();

    // Close the microphone
    take?.mic?.release(0);
  }, [clearTimer]);

  // Cleanup on unmount: this hook's recording only; a warm microphone
  // closes on its own timer.
  useEffect(() => {
    return () => {
      clearTimer();
      clearGiveUpTimer();
      takeRef.current?.mic?.release(0);
    };
  }, [clearTimer, clearGiveUpTimer]);

  return {
    isRecording,
    duration,
    error,
    analyser,
    startRecording,
    stopRecording,
    cancelRecording,
  };
}
