/**
 * What the pill says while a recording starts and the microphone is not yet
 * delivering sound.
 *
 * A key press shows "Recording" at once, but a microphone that was closed can
 * take a second or more to deliver its first sound (``micCapture.ts`` logs
 * it as ``first sample at +N ms``), and what is said in that time is lost.
 * So when the first sound has not arrived after ``START_CUE_DELAY_MS`` the
 * pill says "Starting…", and when it does arrive it says "Speak now" for a
 * moment. A start that is quick (a warm microphone, or a cold one that opens
 * fast) shows neither: the pill just says "Recording", as before.
 */

/** ``starting``: waiting for the first sound. ``speak``: it has arrived. */
export type StartCueState = 'starting' | 'speak' | null;

/** How long to wait for the first sound before saying "Starting…". */
export const START_CUE_DELAY_MS = 400;
/** How long "Speak now" stays before the pill goes back to "Recording". */
export const SPEAK_CUE_MS = 900;

export interface CueTimers {
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  now(): number;
}

const browserTimers: CueTimers = {
  setTimeout: (callback, ms) => window.setTimeout(callback, ms),
  clearTimeout: (handle) => window.clearTimeout(handle as number),
  now: () => performance.now(),
};

export class StartCue {
  private state: StartCueState = null;
  private delayTimer: unknown = null;
  private speakTimer: unknown = null;
  private requestedAt = 0;

  /**
   * ``onChange`` gets every change of the cue, with the time since the
   * recording was requested (for the dictation log).
   */
  constructor(
    private readonly onChange: (state: StartCueState, sinceRequestMs: number) => void,
    private readonly timers: CueTimers = browserTimers,
    private readonly delayMs: number = START_CUE_DELAY_MS,
    private readonly speakMs: number = SPEAK_CUE_MS,
  ) {}

  /** A recording was requested: start waiting for its first sound. */
  begin(): void {
    this.reset();
    this.requestedAt = this.timers.now();
    this.delayTimer = this.timers.setTimeout(() => {
      this.delayTimer = null;
      this.set('starting');
    }, this.delayMs);
  }

  /** The first sound arrived (at once for a warm microphone). */
  flowing(): void {
    if (this.delayTimer !== null) {
      // Quick start: the pill never said "Starting…", so it has nothing to retract.
      this.timers.clearTimeout(this.delayTimer);
      this.delayTimer = null;
      return;
    }
    if (this.state !== 'starting') return;
    this.set('speak');
    this.speakTimer = this.timers.setTimeout(() => {
      this.speakTimer = null;
      this.set(null);
    }, this.speakMs);
  }

  /** The recording ended or was given up: clear the cue and its timers. */
  reset(): void {
    if (this.delayTimer !== null) this.timers.clearTimeout(this.delayTimer);
    if (this.speakTimer !== null) this.timers.clearTimeout(this.speakTimer);
    this.delayTimer = null;
    this.speakTimer = null;
    this.set(null);
  }

  private set(state: StartCueState): void {
    if (state === this.state) return;
    this.state = state;
    this.onChange(state, Math.round(this.timers.now() - this.requestedAt));
  }
}
