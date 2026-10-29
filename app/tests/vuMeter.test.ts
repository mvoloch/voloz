// Run with `bun test app/tests`.
import { describe, expect, test } from 'bun:test';
import {
  BAR_MAX_PX,
  BAR_MIN_PX,
  BAR_WEIGHTS,
  FULL_SCALE_DB,
  NOISE_FLOOR_DB,
  barHeights,
  levelFromRms,
  rms,
  smoothLevel,
} from '../src/components/CapturePill/vuMeter';

const fromDb = (db: number) => 10 ** (db / 20);

describe('rms', () => {
  test('of silence is 0, of a full-scale square wave is 1', () => {
    expect(rms(new Float32Array(512))).toBe(0);
    expect(rms([1, -1, 1, -1])).toBe(1);
    expect(rms([])).toBe(0);
  });

  test('of a sine is amplitude / sqrt(2)', () => {
    const sine = Array.from({ length: 1000 }, (_, i) => 0.5 * Math.sin((2 * Math.PI * i) / 100));
    expect(rms(sine)).toBeCloseTo(0.5 / Math.SQRT2, 3);
  });
});

describe('levelFromRms', () => {
  test('silence and room hiss below the noise floor stay at 0', () => {
    expect(levelFromRms(0)).toBe(0);
    expect(levelFromRms(Number.NaN)).toBe(0);
    expect(levelFromRms(fromDb(-70))).toBe(0);
    expect(levelFromRms(fromDb(NOISE_FLOOR_DB))).toBe(0);
  });

  test('speech lands in between, loud speech is full', () => {
    const speech = levelFromRms(fromDb(-30));
    expect(speech).toBeGreaterThan(0.4);
    expect(speech).toBeLessThan(0.8);
    expect(levelFromRms(fromDb(FULL_SCALE_DB))).toBeCloseTo(1, 6);
    expect(levelFromRms(1)).toBe(1);
  });

  test('is monotonic', () => {
    let previous = -1;
    for (let db = -80; db <= 0; db += 2) {
      const level = levelFromRms(fromDb(db));
      expect(level).toBeGreaterThanOrEqual(previous);
      previous = level;
    }
  });
});

describe('smoothLevel', () => {
  test('rises faster than it falls', () => {
    const up = smoothLevel(0, 1);
    const down = 1 - smoothLevel(1, 0);
    expect(up).toBeGreaterThan(down);
  });

  test('settles at exactly 0 after the sound stops', () => {
    let level = 1;
    let frames = 0;
    while (level > 0 && frames < 200) {
      level = smoothLevel(level, 0);
      frames += 1;
    }
    expect(level).toBe(0);
    // About half a second at 60 frames per second, not a lingering tail.
    expect(frames).toBeLessThan(40);
  });

  test('a steady level stays put', () => {
    expect(smoothLevel(0.5, 0.5)).toBe(0.5);
    expect(smoothLevel(0, 0)).toBe(0);
  });
});

describe('barHeights', () => {
  test('silence is flat', () => {
    expect(barHeights(0)).toEqual(BAR_WEIGHTS.map(() => BAR_MIN_PX));
  });

  test('a full level reaches the max in the middle and stays within bounds', () => {
    const heights = barHeights(1);
    expect(Math.max(...heights)).toBe(BAR_MAX_PX);
    expect(heights[2]).toBe(BAR_MAX_PX);
    for (const h of barHeights(5)) expect(h).toBeLessThanOrEqual(BAR_MAX_PX);
    for (const h of barHeights(-1)) expect(h).toBe(BAR_MIN_PX);
  });

  test('every bar grows with the level', () => {
    const quiet = barHeights(0.2);
    const loud = barHeights(0.8);
    quiet.forEach((h, i) => {
      expect(loud[i]).toBeGreaterThan(h);
    });
  });
});
