import { useMutation, useQueryClient } from '@tanstack/react-query';
import { emit as tauriEmit } from '@tauri-apps/api/event';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { PillState } from '@/components/CapturePill/CapturePill';
import i18n from '@/i18n';
import { apiClient } from '@/lib/api/client';
import type {
  CaptureCreateResponse,
  CaptureListResponse,
  CaptureResponse,
  CaptureSource,
} from '@/lib/api/types';
import { useAudioRecording } from '@/lib/hooks/useAudioRecording';

/**
 * Broadcast to sibling Tauri webviews that the captures list has changed.
 * The main CapturesTab listens, seeds its React Query cache, and focuses the
 * new row, so uploads from the floating dictate window show up live.
 *
 * ``capture:created`` carries the full response so the sibling can seed its
 * cache before the refetch lands — otherwise the selection-guard effect
 * would snap back to ``captures[0]`` in the race window between
 * ``setSelectedId(new)`` and the list actually containing the new row.
 *
 * No-op in web mode — there are no siblings to notify.
 */
function broadcastCreated(capture: CaptureResponse) {
  tauriEmit('capture:created', { capture }).catch(() => {
    /* not running inside Tauri; nothing to sync to */
  });
}

function broadcastUpdated(id: string) {
  tauriEmit('capture:updated', { id }).catch(() => {
    /* not running inside Tauri; nothing to sync to */
  });
}

const REST_FADE_MS = 900;
// How long the green "Done" pill stays visible after refine (or transcribe,
// when auto-refine is off) completes, before the fade-out begins.
const COMPLETED_DWELL_MS = 2000;
// Long enough to read a full backend stack message and click-to-copy.
const ERROR_PILL_VISIBLE_MS = 6000;
// Short self-explanatory notices (e.g. "Recording too short, canceled") —
// there's nothing to read or copy, so clear out quickly.
const BRIEF_NOTICE_MS = 2000;
// "Refinement skipped: rate limit" — one line, a little longer to read.
const RATE_LIMIT_NOTICE_MS = 3500;
// MediaRecorder.start(100) emits its first chunk ~100ms in, but the webm
// container header isn't guaranteed to be finalised that quickly — anything
// under half a second tends to produce a blob neither AudioContext.decode
// nor ffmpeg will accept. Caught client-side and surfaced as a friendly
// "Recording too short, canceled" pill instead of bubbling up a 400.
const MIN_RECORDING_DURATION_S = 0.5;
// A dictation whose stop never arrives (a lost hotkey release, a hands-free
// toggle the user didn't notice) would otherwise record until the next press.
// 10 min stays under the 25 MB upload limit common to hosted Whisper APIs
// (~13 min of 16 kHz mono WAV).
const MAX_RECORDING_DURATION_S = 600;
const shortRecordingMessage = () => i18n.t('captures.pill.tooShort');

export type CapturePillState = PillState | 'hidden';

/** A refine chained automatically after a new capture. */
interface AutoRefine {
  capture: CaptureCreateResponse;
  context: unknown;
}

/** How a recording is uploaded; defaults to a plain dictation. */
export interface RecordingOptions {
  /**
   * ``command``: the transcript is an instruction applied to the selected
   * text (``getCommandSelection``) instead of being refined.
   */
  source?: CaptureSource;
  /** App the text is for (exe name / bundle id) and its window title. */
  targetApp?: string | null;
  targetTitle?: string | null;
}

export interface UseCaptureRecordingSessionOptions {
  /**
   * Fired after a capture row is created on the server. Callers can use this
   * to select the new capture or emit a Tauri event to a sibling window.
   */
  onCaptureCreated?: (capture: CaptureResponse) => void;
  /**
   * Fired with the final delivered text — refined if ``auto_refine`` was on
   * for this capture, raw transcript otherwise (also when the automatic
   * refine fails, so a dictation is never lost to a refinement error). Used
   * by the floating dictate window to hand the text off to the Rust
   * auto-paste pipeline.
   *
   * ``allowAutoPaste`` is the setting the capture was created under, so a
   * refine that lands after the user flips the toggle still uses it.
   * ``context`` is whatever was passed to ``startRecording`` for this
   * recording; dictations can overlap (a new chord while the previous one
   * is still refining), so each result carries its own.
   */
  onFinalText?: (
    text: string,
    capture: CaptureResponse,
    allowAutoPaste: boolean,
    context: unknown,
  ) => void;
  /**
   * The text a voice command works on, for a ``command`` recording's
   * ``context``. Empty drafts new text from the instruction.
   */
  getCommandSelection?: (context: unknown) => Promise<string>;
}

export interface UseCaptureRecordingSessionResult {
  pillState: CapturePillState;
  pillElapsedMs: number;
  errorMessage: string | null;
  isRecording: boolean;
  isUploading: boolean;
  isRefining: boolean;
  startRecording: (context?: unknown, options?: RecordingOptions) => void;
  stopRecording: () => void;
  toggleRecording: () => void;
  dismissError: () => void;
  uploadFile: (file: File, source: CaptureSource) => void;
  refine: (captureId: string) => void;
}

/**
 * Owns the full record → transcribe → refine → rest lifecycle behind the
 * capture pill. The pill component and the Dictate/Stop button are the only
 * consumers; everything else (cache seeding, error toasts, settings reads) is
 * internal so the hook can be reused from a floating Tauri window without the
 * containing tab.
 */
export function useCaptureRecordingSession(
  options: UseCaptureRecordingSessionOptions = {},
): UseCaptureRecordingSessionResult {
  const queryClient = useQueryClient();
  // Every capture setting is resolved server-side. ``stt_model``,
  // ``llm_model`` and refine flags are read from the capture_settings table
  // inside POST /captures and /captures/*/refine, and ``auto_refine`` comes
  // back on the create response so the client decides whether to chain a
  // refine call using a value that can't go stale across sibling webviews.

  const [pillState, setPillState] = useState<CapturePillState>('hidden');
  const [frozenElapsedMs, setFrozenElapsedMs] = useState(0);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const restTimerRef = useRef<number | null>(null);
  const errorTimerRef = useRef<number | null>(null);

  // Mutation callbacks close over stale pillState otherwise.
  const pillStateRef = useRef<CapturePillState>('hidden');
  pillStateRef.current = pillState;

  const onCaptureCreatedRef = useRef(options.onCaptureCreated);
  onCaptureCreatedRef.current = options.onCaptureCreated;

  const onFinalTextRef = useRef(options.onFinalText);
  onFinalTextRef.current = options.onFinalText;

  const getCommandSelectionRef = useRef(options.getCommandSelection);
  getCommandSelectionRef.current = options.getCommandSelection;

  // Context and options of the recording in progress; moved into the
  // upload's variables when it stops, so they follow that capture through
  // transcribe and refine.
  const contextRef = useRef<unknown>(undefined);
  const recordingOptionsRef = useRef<RecordingOptions>({});

  const clearRestTimer = useCallback(() => {
    if (restTimerRef.current !== null) {
      window.clearTimeout(restTimerRef.current);
      restTimerRef.current = null;
    }
  }, []);

  const clearErrorTimer = useCallback(() => {
    if (errorTimerRef.current !== null) {
      window.clearTimeout(errorTimerRef.current);
      errorTimerRef.current = null;
    }
  }, []);

  const scheduleHidePill = useCallback(() => {
    clearRestTimer();
    setPillState('completed');
    // Two-hop timer: show the green "Done" pill for COMPLETED_DWELL_MS,
    // then hand off to the existing rest-fade before unmounting.
    restTimerRef.current = window.setTimeout(() => {
      setPillState('rest');
      restTimerRef.current = window.setTimeout(() => {
        setPillState('hidden');
        restTimerRef.current = null;
      }, REST_FADE_MS);
    }, COMPLETED_DWELL_MS);
  }, [clearRestTimer]);

  const showError = useCallback(
    (message: string, durationMs: number = ERROR_PILL_VISIBLE_MS) => {
      clearRestTimer();
      clearErrorTimer();
      setErrorMessage(message || 'Something went wrong');
      setPillState('error');
      errorTimerRef.current = window.setTimeout(() => {
        setPillState('hidden');
        setErrorMessage(null);
        errorTimerRef.current = null;
      }, durationMs);
    },
    [clearRestTimer, clearErrorTimer],
  );

  const dismissError = useCallback(() => {
    clearErrorTimer();
    setPillState('hidden');
    setErrorMessage(null);
  }, [clearErrorTimer]);

  useEffect(
    () => () => {
      clearRestTimer();
      clearErrorTimer();
    },
    [clearRestTimer, clearErrorTimer],
  );

  const refineMutation = useMutation({
    // Empty body — backend resolves flags and model from capture_settings.
    // ``auto`` is set when the refine follows a new capture automatically;
    // a manual refine from the captures list leaves it out.
    mutationFn: async ({ captureId }: { captureId: string; auto?: AutoRefine }) =>
      apiClient.refineCapture(captureId, {}),
    onSuccess: (data, { captureId, auto }) => {
      queryClient.invalidateQueries({ queryKey: ['captures'] });
      broadcastUpdated(captureId);
      if (pillStateRef.current === 'refining') scheduleHidePill();
      const finalText = data.transcript_refined ?? data.transcript_raw;
      if (finalText) {
        // A manual refine has no capture-time setting and never pastes.
        onFinalTextRef.current?.(
          finalText,
          data,
          auto?.capture.allow_auto_paste ?? false,
          auto?.context,
        );
      }
    },
    onError: (err: Error, { auto }) => {
      // Deliver the raw transcript rather than lose the dictation.
      if (auto?.capture.transcript_raw?.trim()) {
        onFinalTextRef.current?.(
          auto.capture.transcript_raw,
          auto.capture,
          auto.capture.allow_auto_paste,
          auto.context,
        );
      }
      // A newer recording owns the pill; don't paint an old error over it.
      if (pillStateRef.current === 'recording') return;
      // A provider rate limit (Groq's free tier) is expected and passes: say
      // so briefly instead of showing the provider's error body.
      if ((err as { status?: number }).status === 429) {
        showError(i18n.t('captures.pill.refineRateLimited'), RATE_LIMIT_NOTICE_MS);
        return;
      }
      showError(err.message || i18n.t('captures.pill.refineFailed'));
    },
  });

  // A voice command: read the selection (the pill may fall back to a
  // synthetic copy here), then let the LLM apply the spoken instruction.
  // Unlike a refine, a failure delivers nothing — pasting the instruction
  // over the user's selection would destroy it.
  const commandMutation = useMutation({
    mutationFn: async ({ capture, context }: AutoRefine) => {
      const selection = (await getCommandSelectionRef.current?.(context)) ?? '';
      return apiClient.runCaptureCommand(capture.id, { selection });
    },
    onSuccess: (data, { capture, context }) => {
      queryClient.invalidateQueries({ queryKey: ['captures'] });
      broadcastUpdated(data.id);
      if (pillStateRef.current === 'refining') scheduleHidePill();
      if (data.transcript_refined) {
        onFinalTextRef.current?.(data.transcript_refined, data, capture.allow_auto_paste, context);
      }
    },
    onError: (err: Error & { status?: number }) => {
      if (pillStateRef.current === 'recording') return;
      showError(
        err.status === 409
          ? i18n.t('captures.pill.commandNoLlm')
          : err.message || i18n.t('captures.pill.commandFailed'),
      );
    },
  });

  const uploadMutation = useMutation({
    mutationFn: async ({
      file,
      source,
      targetApp,
      targetTitle,
    }: {
      file: File;
      source: CaptureSource;
      targetApp?: string | null;
      targetTitle?: string | null;
      context?: unknown;
    }) => apiClient.createCapture(file, { source, targetApp, targetTitle }),
    onSuccess: (capture, { context }) => {
      queryClient.setQueryData<CaptureListResponse>(['captures'], (prev) => {
        if (!prev) return prev;
        if (prev.items.some((c) => c.id === capture.id)) return prev;
        return { ...prev, items: [capture, ...prev.items], total: prev.total + 1 };
      });
      queryClient.invalidateQueries({ queryKey: ['captures'] });
      broadcastCreated(capture);
      onCaptureCreatedRef.current?.(capture);
      if (capture.source === 'command') {
        if (!capture.transcript_raw?.trim()) {
          if (pillStateRef.current !== 'recording') {
            showError(i18n.t('captures.pill.commandEmpty'), BRIEF_NOTICE_MS);
          }
          return;
        }
        if (pillStateRef.current !== 'recording') setPillState('refining');
        commandMutation.mutate({ capture, context });
        return;
      }
      // Nothing heard: skip the refine — there is nothing to polish or paste.
      if (capture.auto_refine && capture.transcript_raw?.trim()) {
        if (pillStateRef.current !== 'recording') setPillState('refining');
        refineMutation.mutate({ captureId: capture.id, auto: { capture, context } });
      } else {
        if (pillStateRef.current === 'transcribing') scheduleHidePill();
        // Without a refine the server may still have produced the final
        // text: a dictation that was only a shortcut trigger comes back
        // with the shortcut's text as ``transcript_refined``.
        const finalText = capture.transcript_refined ?? capture.transcript_raw;
        if (finalText) {
          onFinalTextRef.current?.(finalText, capture, capture.allow_auto_paste, context);
        }
      }
    },
    onError: (err: Error) => {
      // A newer recording owns the pill; don't paint an old error over it.
      if (pillStateRef.current === 'recording') return;
      // Backend's librosa-audioread fallback returns a 400 with this shape
      // for tiny/corrupt webm blobs that slip past the client guard —
      // translate it to the same friendly message so the user sees one
      // consistent cause, not an opaque decode error.
      const msg = err.message || '';
      if (/could not decode/i.test(msg) || /empty or corrupt/i.test(msg)) {
        showError(shortRecordingMessage(), BRIEF_NOTICE_MS);
      } else {
        showError(msg || i18n.t('captures.pill.uploadFailed'));
      }
    },
  });

  const {
    isRecording,
    duration,
    startRecording: beginAudioRecording,
    stopRecording,
    error: recordError,
  } = useAudioRecording({
    maxDurationSeconds: MAX_RECORDING_DURATION_S,
    onRecordingComplete: (blob, recordedDuration) => {
      const context = contextRef.current;
      const recordingOptions = recordingOptionsRef.current;
      contextRef.current = undefined;
      recordingOptionsRef.current = {};
      // Trigger-happy tap — MediaRecorder hasn't emitted a usable chunk yet
      // so the blob is empty or unparseable. Surface it as a transient pill
      // so the user sees their recording was recognised and canceled.
      if (!blob.size || (recordedDuration ?? 0) < MIN_RECORDING_DURATION_S) {
        showError(shortRecordingMessage(), BRIEF_NOTICE_MS);
        return;
      }
      setFrozenElapsedMs(Math.round((recordedDuration ?? 0) * 1000));
      setPillState('transcribing');
      const extension = blob.type.includes('wav')
        ? 'wav'
        : blob.type.includes('webm')
          ? 'webm'
          : 'bin';
      const file = new File([blob], `dictation-${Date.now()}.${extension}`, {
        type: blob.type,
      });
      uploadMutation.mutate({
        file,
        source: recordingOptions.source ?? 'dictation',
        targetApp: recordingOptions.targetApp,
        targetTitle: recordingOptions.targetTitle,
        context,
      });
    },
  });

  useEffect(() => {
    if (recordError) {
      showError(recordError);
    }
  }, [recordError, showError]);

  const startRecording = useCallback(
    (context?: unknown, options: RecordingOptions = {}) => {
      if (isRecording) return;
      contextRef.current = context;
      recordingOptionsRef.current = options;
      clearRestTimer();
      // A notice still on screen (e.g. "too short" from a quick tap) would
      // otherwise fire its hide timer mid-recording and tuck the pill away
      // while the mic keeps running.
      clearErrorTimer();
      setErrorMessage(null);
      setFrozenElapsedMs(0);
      setPillState('recording');
      beginAudioRecording();
      // Open the remote STT/LLM connections while the user is still speaking,
      // so the upload after release doesn't pay the TLS handshake. Never awaited.
      apiClient.warmupCapture().catch(() => {});
    },
    [isRecording, beginAudioRecording, clearRestTimer, clearErrorTimer],
  );

  const toggleRecording = useCallback(() => {
    if (isRecording) {
      stopRecording();
      return;
    }
    startRecording();
  }, [isRecording, startRecording, stopRecording]);

  const uploadFile = useCallback(
    (file: File, source: CaptureSource) => {
      uploadMutation.mutate({ file, source });
    },
    [uploadMutation],
  );

  const refine = useCallback(
    (captureId: string) => {
      refineMutation.mutate({ captureId });
    },
    [refineMutation],
  );

  const pillElapsedMs =
    pillState === 'recording' ? Math.round(duration * 1000) : frozenElapsedMs;

  return {
    pillState,
    pillElapsedMs,
    errorMessage,
    isRecording,
    isUploading: uploadMutation.isPending,
    isRefining: refineMutation.isPending || commandMutation.isPending,
    startRecording,
    stopRecording,
    toggleRecording,
    dismissError,
    uploadFile,
    refine,
  };
}
