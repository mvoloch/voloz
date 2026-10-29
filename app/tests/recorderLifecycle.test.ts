// Run with `bun test app/tests` (Bun's built-in runner; the app has no JS
// test runner of its own).
import { describe, expect, test } from 'bun:test';
import {
  FINISH_FALLBACK_MS,
  RecordingLifecycle,
  type RecordingEndReason,
  type StoppableRecorder,
} from '../src/lib/hooks/recorderLifecycle';

/** A MediaRecorder stand-in: stop() goes inactive; the stop event is fired by the test. */
class FakeRecorder implements StoppableRecorder {
  state: 'inactive' | 'recording' | 'paused' = 'recording';
  stopCalls = 0;
  stop() {
    if (this.state === 'inactive') throw new DOMException('inactive', 'InvalidStateError');
    this.stopCalls += 1;
    this.state = 'inactive';
  }
}

function setup() {
  const recorder = new FakeRecorder();
  const left: RecordingEndReason[] = [];
  const finished: RecordingEndReason[] = [];
  const timers: { fn: () => void; ms: number }[] = [];
  const lifecycle = new RecordingLifecycle(
    recorder,
    {
      onLeave: (reason) => left.push(reason),
      onFinish: (reason) => finished.push(reason),
    },
    (fn, ms) => timers.push({ fn, ms }),
  );
  const runTimers = () => {
    for (const t of timers.splice(0)) t.fn();
  };
  return { recorder, lifecycle, left, finished, timers, runTimers };
}

describe('RecordingLifecycle', () => {
  test('a stop request stops the recorder and finishes on its stop event', () => {
    const { recorder, lifecycle, left, finished } = setup();
    lifecycle.end('stopped');
    expect(recorder.stopCalls).toBe(1);
    expect(left).toEqual(['stopped']);
    expect(finished).toEqual([]);
    lifecycle.recorderStopped();
    expect(finished).toEqual(['stopped']);
  });

  test('a recorder that stops by itself leaves recording and still delivers', () => {
    const { recorder, lifecycle, left, finished } = setup();
    recorder.state = 'inactive';
    lifecycle.recorderStopped();
    expect(left).toEqual(['device-ended']);
    expect(finished).toEqual(['device-ended']);
    expect(lifecycle.ended).toBe(true);
  });

  test('a stop request after the recorder stopped by itself is not a no-op', () => {
    const { recorder, lifecycle, left, finished, timers, runTimers } = setup();
    // The recorder went inactive and its stop event has not come (or never will).
    recorder.state = 'inactive';
    lifecycle.end('stopped');
    expect(left).toEqual(['stopped']);
    expect(recorder.stopCalls).toBe(0);
    expect(timers.map((t) => t.ms)).toEqual([FINISH_FALLBACK_MS]);
    runTimers();
    expect(finished).toEqual(['stopped']);
  });

  test('a track that ends stops the recorder with reason device-ended', () => {
    const { recorder, lifecycle, left, finished } = setup();
    lifecycle.end('device-ended');
    expect(recorder.stopCalls).toBe(1);
    lifecycle.recorderStopped();
    expect(left).toEqual(['device-ended']);
    expect(finished).toEqual(['device-ended']);
  });

  test('a recorder error ends the recording with reason error', () => {
    const { recorder, lifecycle, left, finished } = setup();
    // Per spec the recorder goes inactive and fires error, then stop.
    recorder.state = 'inactive';
    lifecycle.end('error');
    lifecycle.recorderStopped();
    expect(left).toEqual(['error']);
    expect(finished).toEqual(['error']);
  });

  test('leaves and finishes once however many ends arrive', () => {
    const { recorder, lifecycle, left, finished, runTimers } = setup();
    lifecycle.end('stopped');
    lifecycle.end('stopped');
    lifecycle.end('device-ended');
    lifecycle.recorderStopped();
    lifecycle.recorderStopped();
    runTimers();
    expect(recorder.stopCalls).toBe(1);
    expect(left).toEqual(['stopped']);
    expect(finished).toEqual(['stopped']);
  });

  test('the fallback does not finish twice after the stop event', () => {
    const { lifecycle, finished, runTimers } = setup();
    lifecycle.end('stopped');
    lifecycle.recorderStopped();
    runTimers();
    expect(finished).toEqual(['stopped']);
  });

  test('a recorder whose stop() throws still ends and finishes', () => {
    const { recorder, lifecycle, left, finished, runTimers } = setup();
    recorder.stop = () => {
      throw new Error('boom');
    };
    lifecycle.end('stopped');
    expect(left).toEqual(['stopped']);
    runTimers();
    expect(finished).toEqual(['stopped']);
  });
});
