import { useCallback, useEffect, useRef, useState } from 'react';
import i18n from '@/i18n';
import { usePlatform } from '@/platform/PlatformContext';
import { convertToWav } from '@/lib/utils/audio';
import { RecordingLifecycle, type RecordingEndReason } from '@/lib/hooks/recorderLifecycle';

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

// Decoding the WebM to WAV normally takes well under a second. If it hangs
// (an AudioContext that never decodes), send the WebM rather than nothing.
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

/** One recording: its data, its stream and whether it was cancelled. */
interface Take {
  chunks: Blob[];
  stream: MediaStream | null;
  startedAt: number | null;
  duration?: number;
  cancelled: boolean;
}

interface UseAudioRecordingOptions {
  maxDurationSeconds?: number;
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
  onRecordingComplete,
  onRecordingEnded,
  log,
}: UseAudioRecordingOptions = {}) {
  const platform = usePlatform();
  const [isRecording, setIsRecording] = useState(false);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
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
    if (startingRef.current) return;
    if (lifecycleRef.current && !lifecycleRef.current.ended) return;
    startingRef.current = true;
    stopRequestedRef.current = false;
    const startId = ++startIdRef.current;
    const take: Take = { chunks: [], stream: null, startedAt: null, cancelled: false };
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

      // Request microphone access
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      if (startId !== startIdRef.current) {
        // This start was given up while the microphone was opening.
        stream.getTracks().forEach((track) => {
          track.stop();
        });
        writeLog('microphone opened after its start was given up: closed');
        return;
      }
      clearGiveUpTimer();
      take.stream = stream;

      // Create MediaRecorder with preferred MIME type
      const options: MediaRecorderOptions = {
        mimeType: 'audio/webm;codecs=opus',
      };

      // Fallback to default if webm not supported
      if (!MediaRecorder.isTypeSupported(options.mimeType!)) {
        delete options.mimeType;
      }

      const mediaRecorder = new MediaRecorder(stream, options);

      const releaseStream = () => {
        stream.getTracks().forEach((track) => {
          track.stop();
        });
        take.stream = null;
      };

      const lifecycle = new RecordingLifecycle(mediaRecorder, {
        onLeave: (reason) => {
          clearTimer();
          setIsRecording(false);
          take.duration = take.startedAt ? (Date.now() - take.startedAt) / 1000 : undefined;
          writeLog(`recording ended (${reason}) after ${(take.duration ?? 0).toFixed(1)} s`);
          onEndedRef.current?.(reason, take.duration);
        },
        onFinish: async (reason) => {
          const webmBlob = new Blob(take.chunks, { type: 'audio/webm' });
          // Stop all tracks now that we have the data
          releaseStream();

          // Don't fire completion callback if the recording was cancelled
          if (take.cancelled) return;

          // Convert to WAV format to avoid needing ffmpeg on backend
          try {
            const wavBlob = await withTimeout(convertToWav(webmBlob), WAV_CONVERSION_TIMEOUT_MS);
            onCompleteRef.current?.(wavBlob, take.duration, reason);
          } catch (err) {
            console.error('Error converting audio to WAV:', err);
            writeLog(`wav conversion failed (${err instanceof Error ? err.message : 'error'}): sending webm`);
            // Fallback to original blob if conversion fails
            onCompleteRef.current?.(webmBlob, take.duration, reason);
          }
        },
        log: writeLog,
      });
      lifecycleRef.current = lifecycle;

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          take.chunks.push(event.data);
        }
      };
      mediaRecorder.onstart = () => writeLog('recorder state: recording');
      mediaRecorder.onpause = () => writeLog('recorder state: paused');
      mediaRecorder.onresume = () => writeLog('recorder state: recording (resumed)');
      mediaRecorder.onstop = () => {
        writeLog('recorder state: inactive (stop event)');
        lifecycle.recorderStopped();
      };
      mediaRecorder.onerror = (event) => {
        setError(i18n.t('common.microphone.recordingError'));
        console.error('MediaRecorder error:', event);
        const name = (event as Event & { error?: DOMException }).error?.name ?? 'unknown';
        writeLog(`recorder error: ${name}`);
        lifecycle.end('error');
      };

      // The recorder may or may not stop by itself when its input goes away
      // (device removed, Bluetooth profile switch); end the recording either
      // way so nothing waits on a stop that already happened.
      for (const track of stream.getAudioTracks()) {
        track.addEventListener('ended', () => {
          writeLog('microphone track ended');
          lifecycle.end('device-ended');
        });
        track.addEventListener('mute', () => writeLog('microphone track muted'));
        track.addEventListener('unmute', () => writeLog('microphone track unmuted'));
      }
      writeLog(`microphone open: ${stream.getAudioTracks()[0]?.label || 'unnamed device'}`);

      // WebKit's MediaRecorder drops the WebM EBML header from chunks when
      // started with a timeslice, so concatenated blobs fail to parse in
      // both AudioContext and ffmpeg. Starting with no timeslice produces
      // exactly one dataavailable on stop() with a valid container.
      mediaRecorder.start();
      setIsRecording(true);
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
      take.stream?.getTracks().forEach((track) => {
        track.stop();
      });
      take.stream = null;
      if (startId === startIdRef.current) {
        startingRef.current = false;
        stopRequestedRef.current = false;
        clearGiveUpTimer();
        setError(microphoneErrorMessage(err));
        setIsRecording(false);
      }
      writeLog(`microphone failed to open: ${err instanceof Error ? err.name : 'error'}`);
    }
  }, [maxDurationSeconds, platform.metadata.isTauri, clearTimer, clearGiveUpTimer, writeLog]);

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
      take.chunks = [];
    }
    if (startingRef.current) {
      stopRequestedRef.current = true;
      return;
    }
    const lifecycle = lifecycleRef.current;
    if (lifecycle && !lifecycle.ended) lifecycle.end('stopped');
    setIsRecording(false);
    setDuration(0);
    clearTimer();

    // Stop all tracks
    take?.stream?.getTracks().forEach((track) => {
      track.stop();
    });
    if (take) take.stream = null;
  }, [clearTimer]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearTimer();
      clearGiveUpTimer();
      takeRef.current?.stream?.getTracks().forEach((track) => {
        track.stop();
      });
    };
  }, [clearTimer, clearGiveUpTimer]);

  return {
    isRecording,
    duration,
    error,
    startRecording,
    stopRecording,
    cancelRecording,
  };
}
