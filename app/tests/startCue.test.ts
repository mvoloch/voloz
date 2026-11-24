// Run with `bun test app/tests` (Bun's built-in runner; the app has no JS
// test runner of its own).
import { describe, expect, test } from 'bun:test';
import {
  type CueTimers,
  SPEAK_CUE_MS,
  START_CUE_DELAY_MS,
  StartCue,
  type StartCueState,
} from '../src/lib/hooks/startCue';

/** A clock the test moves by hand; timers fire as it passes their time. */
class FakeClock implements CueTimers {
  private time = 0;
  private nextId = 1;
  private pending = new Map<number, { at: number; callback: () => void }>();

  now(): number {
    return this.time;
  }

  setTimeout(callback: () => void, ms: number): unknown {
    const id = this.nextId++;
    this.pending.set(id, { at: this.time + ms, callback });
    return id;
  }

  clearTimeout(handle: unknown): void {
    this.pending.delete(handle as number);
  }

  get pendingCount(): number {
    return this.pending.size;
  }

  advance(ms: number): void {
    const target = this.time + ms;
    for (;;) {
      let nextId: number | null = null;
      let nextAt = Number.POSITIVE_INFINITY;
      for (const [id, timer] of this.pending) {
        if (timer.at <= target && timer.at < nextAt) {
          nextId = id;
          nextAt = timer.at;
        }
      }
      if (nextId === null) break;
      const timer = this.pending.get(nextId);
      this.pending.delete(nextId);
      this.time = nextAt;
      timer?.callback();
    }
    this.time = target;
  }
}

function setup() {
  const clock = new FakeClock();
  const changes: Array<[StartCueState, number]> = [];
  const cue = new StartCue((state, since) => changes.push([state, since]), clock);
  return { clock, changes, cue };
}

describe('StartCue', () => {
  test('a warm microphone (sound at once) shows no cue', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    cue.flowing();
    clock.advance(5000);
    expect(changes).toEqual([]);
    expect(clock.pendingCount).toBe(0);
  });

  test('a cold start that is quicker than the delay shows no cue', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS - 1);
    cue.flowing();
    clock.advance(5000);
    expect(changes).toEqual([]);
  });

  test('a slow cold start says "starting", then "speak", then back to nothing', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS);
    expect(changes).toEqual([['starting', START_CUE_DELAY_MS]]);
    clock.advance(600);
    cue.flowing();
    expect(changes[1]).toEqual(['speak', START_CUE_DELAY_MS + 600]);
    clock.advance(SPEAK_CUE_MS);
    expect(changes[2]?.[0]).toBe(null);
    expect(changes.length).toBe(3);
  });

  test('a second flowing call changes nothing', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS + 100);
    cue.flowing();
    cue.flowing();
    expect(changes.map(([state]) => state)).toEqual(['starting', 'speak']);
  });

  test('reset while starting clears the cue and its timers', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS);
    cue.reset();
    expect(changes.map(([state]) => state)).toEqual(['starting', null]);
    expect(clock.pendingCount).toBe(0);
  });

  test('reset before the delay cancels it: "starting" never shows', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS - 1);
    cue.reset();
    clock.advance(5000);
    expect(changes).toEqual([]);
  });

  test('a new begin during "speak" drops the old cue and starts over', () => {
    const { clock, changes, cue } = setup();
    cue.begin();
    clock.advance(START_CUE_DELAY_MS + 50);
    cue.flowing();
    cue.begin();
    expect(changes.map(([state]) => state)).toEqual(['starting', 'speak', null]);
    clock.advance(START_CUE_DELAY_MS);
    expect(changes[3]?.[0]).toBe('starting');
    expect(changes[3]?.[1]).toBe(START_CUE_DELAY_MS);
  });

  test('flowing without a begin does nothing', () => {
    const { clock, changes, cue } = setup();
    cue.flowing();
    clock.advance(5000);
    expect(changes).toEqual([]);
  });
});
