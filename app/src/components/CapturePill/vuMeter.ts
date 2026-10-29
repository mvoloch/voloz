/**
 * The recording pill's level meter: microphone samples → bar heights.
 * Pure so it can be unit-tested (`app/tests/vuMeter.test.ts`); the Web
 * Audio wiring lives in `PillLevelBars`.
 */

/** Relative height of each bar, left to right: tallest in the middle. */
export const BAR_WEIGHTS = [0.55, 0.8, 1, 0.8, 0.55] as const;
/** A flat bar (silence). */
export const BAR_MIN_PX = 4;
/** A full bar; the bars' row is 20 px tall. */
export const BAR_MAX_PX = 18;
/**
 * Below this the bars stay flat: room hiss and fans, after the browser's
 * noise suppression, sit well under it.
 */
export const NOISE_FLOOR_DB = -55;
/** At or above this the bars are full: loud speech close to the mic. */
export const FULL_SCALE_DB = -15;
/** Per-frame smoothing: rise fast with a syllable, fall back gently. */
const ATTACK = 0.6;
const RELEASE = 0.15;

/** Root mean square of time-domain samples in [-1, 1]. */
export function rms(samples: ArrayLike<number>): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  return Math.sqrt(sum / samples.length);
}

/** An RMS amplitude as a 0–1 level, linear in decibels between the floor and full scale. */
export function levelFromRms(value: number): number {
  if (!(value > 0)) return 0;
  const db = 20 * Math.log10(value);
  const level = (db - NOISE_FLOOR_DB) / (FULL_SCALE_DB - NOISE_FLOOR_DB);
  return Math.min(1, Math.max(0, level));
}

/** One frame of smoothing from ``previous`` toward ``target``; settles at exactly 0. */
export function smoothLevel(previous: number, target: number): number {
  const rate = target > previous ? ATTACK : RELEASE;
  const next = previous + (target - previous) * rate;
  return next < 0.01 ? 0 : next;
}

/** Bar heights in px for a 0–1 level: all at ``BAR_MIN_PX`` when silent. */
export function barHeights(level: number): number[] {
  const clamped = Math.min(1, Math.max(0, level));
  return BAR_WEIGHTS.map((weight) => BAR_MIN_PX + (BAR_MAX_PX - BAR_MIN_PX) * clamped * weight);
}
