/**
 * How one MediaRecorder recording ends. Kept free of React and the DOM so
 * the rules can be unit-tested (`app/tests/recorderLifecycle.test.ts`).
 *
 * Whatever ends a recording — a stop request (hotkey, the pill's button,
 * Esc, the length cap), the recorder stopping by itself (its microphone
 * removed, a Bluetooth headset switching profile, a Windows audio change),
 * or a recorder error — leaves the recording state once and hands over what
 * was recorded once. A stop request is never a no-op: a recorder that is
 * already inactive only means its data is on the way, and if that data
 * never comes the recording is finished with what was collected.
 */

export type RecordingEndReason = 'stopped' | 'device-ended' | 'error';

/** The part of a MediaRecorder this drives. */
export interface StoppableRecorder {
  readonly state: 'inactive' | 'recording' | 'paused';
  stop(): void;
}

export interface RecordingLifecycleCallbacks {
  /** Leave the recording state (timer, ``isRecording``). Runs once, at the first end. */
  onLeave: (reason: RecordingEndReason) => void;
  /**
   * Hand over the recording. Runs once: on the recorder's ``stop`` event,
   * which follows its final ``dataavailable``, or after
   * ``FINISH_FALLBACK_MS`` if that event never comes.
   */
  onFinish: (reason: RecordingEndReason) => void;
  /** Diagnostics: what an end did to the recorder. */
  log?: (message: string) => void;
}

/**
 * How long to wait for the recorder's ``stop`` event once the recording
 * ended. It normally lands within milliseconds; this only keeps a recorder
 * that never reports back from holding the dictation forever.
 */
export const FINISH_FALLBACK_MS = 3000;

type Schedule = (fn: () => void, ms: number) => void;

const defaultSchedule: Schedule = (fn, ms) => {
  setTimeout(fn, ms);
};

export class RecordingLifecycle {
  private reason: RecordingEndReason | null = null;
  private finished = false;
  private stopEventSeen = false;
  private fallbackArmed = false;

  constructor(
    private readonly recorder: StoppableRecorder,
    private readonly callbacks: RecordingLifecycleCallbacks,
    private readonly schedule: Schedule = defaultSchedule,
  ) {}

  /** Whether the recording has ended (its data may still be on the way). */
  get ended(): boolean {
    return this.reason !== null;
  }

  /**
   * End the recording: a stop request (``stopped``), a track that ended
   * (``device-ended``) or the recorder's ``error`` event. The first reason
   * is the one reported.
   */
  end(reason: RecordingEndReason): void {
    const first = this.reason === null;
    if (first) {
      this.reason = reason;
      this.callbacks.onLeave(reason);
    }
    if (this.stopEventSeen) return;
    const state = this.recorder.state;
    if (state !== 'inactive') {
      try {
        this.recorder.stop();
        this.callbacks.log?.(`${reason}: recorder stopped (was ${state})`);
      } catch (err) {
        this.callbacks.log?.(`${reason}: recorder.stop() threw ${errorName(err)}`);
      }
    } else if (first) {
      this.callbacks.log?.(`${reason}: recorder already inactive, waiting for its data`);
    }
    if (!this.fallbackArmed) {
      this.fallbackArmed = true;
      this.schedule(() => {
        if (this.finished) return;
        this.callbacks.log?.('no stop event from the recorder: finishing with what was recorded');
        this.finish();
      }, FINISH_FALLBACK_MS);
    }
  }

  /** The recorder's ``stop`` event: its data is final. */
  recorderStopped(): void {
    this.stopEventSeen = true;
    // Unless something ended it first, the recorder stopped by itself.
    this.end('device-ended');
    this.finish();
  }

  private finish(): void {
    if (this.finished) return;
    this.finished = true;
    this.callbacks.onFinish(this.reason ?? 'stopped');
  }
}

function errorName(err: unknown): string {
  return err instanceof Error ? err.name : typeof err;
}
