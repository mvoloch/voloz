// Run with `bun test app/tests`.
import { describe, expect, test } from 'bun:test';
import { audioBufferToWav } from '../src/lib/utils/audio';
import {
  concatPcm,
  describeLevel,
  describeReceived,
  mixToMono,
  PcmRing,
  pcmLevel,
  pcmToWav,
  samplesToMs,
  totalSamples,
} from '../src/lib/utils/pcm';

/** ``count`` samples whose values are their index, in blocks of ``block``. */
function blocks(count: number, block: number, start = 0): Float32Array[] {
  const out: Float32Array[] = [];
  for (let i = 0; i < count; i += block) {
    const n = Math.min(block, count - i);
    out.push(Float32Array.from({ length: n }, (_, j) => start + i + j));
  }
  return out;
}

async function bytes(blob: Blob): Promise<number[]> {
  return Array.from(new Uint8Array(await blob.arrayBuffer()));
}

describe('PcmRing (pre-roll)', () => {
  test('keeps exactly the last capacity samples, oldest first', () => {
    // 300 ms at 48 kHz, fed 1 s of 2048-sample blocks as the tap posts them.
    const capacity = 14_400;
    const ring = new PcmRing(capacity);
    for (const b of blocks(48_000, 2048)) ring.push(b);
    expect(ring.length).toBeLessThan(capacity + 2048);
    const kept = concatPcm(ring.drain());
    expect(kept.length).toBe(capacity);
    expect(kept[0]).toBe(48_000 - capacity);
    expect(kept[capacity - 1]).toBe(47_999);
    for (let i = 1; i < kept.length; i++) expect(kept[i] - kept[i - 1]).toBe(1);
    expect(ring.length).toBe(0);
    expect(ring.drain()).toEqual([]);
  });

  test('hands over everything when it holds less than its capacity', () => {
    const ring = new PcmRing(14_400);
    for (const b of blocks(5000, 2048)) ring.push(b);
    expect(concatPcm(ring.drain())).toEqual(concatPcm(blocks(5000, 5000)));
  });

  test('a single block larger than the capacity is trimmed to its tail', () => {
    const ring = new PcmRing(100);
    ring.push(Float32Array.from({ length: 1000 }, (_, i) => i));
    const kept = concatPcm(ring.drain());
    expect(kept.length).toBe(100);
    expect(kept[0]).toBe(900);
  });

  test('ignores empty blocks and clears', () => {
    const ring = new PcmRing(10);
    ring.push(new Float32Array(0));
    expect(ring.length).toBe(0);
    ring.push(new Float32Array(5));
    ring.clear();
    expect(ring.drain()).toEqual([]);
  });

  test('pre-roll then live blocks form one gapless recording', () => {
    const ring = new PcmRing(14_400);
    const stream = blocks(96_000, 2048);
    // Idle warm microphone: the first 40 blocks go to the ring...
    for (const b of stream.slice(0, 40)) ring.push(b);
    // ...the press: the take starts with the ring, then gets the rest.
    const preroll = ring.drain();
    expect(samplesToMs(totalSamples(preroll), 48_000)).toBe(300);
    const take = [...preroll, ...stream.slice(40)];
    const samples = concatPcm(take);
    const first = 40 * 2048 - 14_400;
    expect(samples[0]).toBe(first);
    expect(samples.length).toBe(96_000 - first);
    for (let i = 1; i < samples.length; i++) expect(samples[i] - samples[i - 1]).toBe(1);
  });
});

describe('pcmToWav', () => {
  test('same bytes as the decode-to-WAV writer for the same samples, however they were split', async () => {
    const samples = Float32Array.from({ length: 4801 }, (_, i) => Math.sin(i / 7) * 0.8);
    const old = audioBufferToWav({
      numberOfChannels: 1,
      sampleRate: 48_000,
      length: samples.length,
      getChannelData: () => samples,
    });
    const split = [samples.subarray(0, 2048), samples.subarray(2048, 4096), samples.subarray(4096)];
    expect(await bytes(pcmToWav(concatPcm(split), 48_000))).toEqual(await bytes(old));
  });

  test('a mono 16-bit header at the given rate; an empty take is a header only', async () => {
    const view = new DataView(await pcmToWav(new Float32Array(3), 16_000).arrayBuffer());
    expect(view.getUint16(22, true)).toBe(1);
    expect(view.getUint32(24, true)).toBe(16_000);
    expect(view.getUint16(34, true)).toBe(16);
    expect(view.getUint32(40, true)).toBe(6);
    expect((await pcmToWav(new Float32Array(0), 48_000).arrayBuffer()).byteLength).toBe(44);
  });
});

describe('pcmLevel (dictation log)', () => {
  const rate = 16_000;
  const tone = (n: number, amp: number) =>
    Float32Array.from({ length: n }, (_, i) => amp * Math.sin((2 * Math.PI * 440 * i) / rate));

  test('a muted microphone: all silent, muted 100%', () => {
    const level = pcmLevel([new Float32Array(rate)], rate);
    expect(level.peakDb).toBe(Number.NEGATIVE_INFINITY);
    expect(level.mutedShare).toBe(1);
    expect(describeLevel(level)).toBe(
      'audio level: peak silent, rms silent, muted 100% (the microphone delivered no sound)',
    );
  });

  test('speech-like level: peak and rms in dBFS, nothing muted', () => {
    const level = pcmLevel([tone(rate / 2, 0.5), tone(rate / 2, 0.5)], rate);
    expect(Math.round(level.peakDb)).toBe(-6);
    expect(Math.round(level.rmsDb)).toBe(-9);
    expect(level.mutedShare).toBe(0);
    expect(describeLevel(level)).toBe('audio level: peak -6 dBFS, rms -9 dBFS, muted 0%');
  });

  test('voice cut out after the first 300 ms counts as muted', () => {
    const level = pcmLevel([tone(4800, 0.1), new Float32Array(rate)], rate);
    // 13 windows of 100 ms: 3 with sound, 10 of zeros (rounded share 77%).
    expect(Math.round(level.mutedShare * 100)).toBe(77);
    expect(describeLevel(level)).toContain('(the microphone delivered no sound)');
  });

  test('a quiet room is not muted', () => {
    const level = pcmLevel([tone(rate, 0.001)], rate);
    expect(level.mutedShare).toBe(0);
  });
});

describe('describeReceived (audio lost before the tap)', () => {
  test('a complete warm take: everything held arrived, pre-roll included', () => {
    // 10 s held on a warm mic: 10.3 s of samples at 48 kHz.
    expect(describeReceived(494_400, 48_000, 10, 300)).toBe(
      'audio received: 10.3 s of 10.3 s (complete)',
    );
  });

  test('blocks dropped on a busy computer are reported as a share lost', () => {
    // The 23/11 20:16 dictation: held 53.1 s, the WAV had 37.5 s.
    expect(describeReceived(37.5 * 48_000, 48_000, 53.1, 0)).toBe(
      'audio received: 37.5 s of 53.1 s (29% lost: the audio graph fell behind)',
    );
  });

  test('under 2% is timing noise, not loss', () => {
    expect(describeReceived(47_200, 48_000, 1, 0)).toBe('audio received: 1.0 s of 1.0 s (complete)');
  });

  test('a cold take missing under 0.3 s is the graph starting, not loss', () => {
    // Before 11-16 cold takes came out up to 0.11 s short (p90) of the time held.
    expect(describeReceived(2.8 * 48_000, 48_000, 3, 0)).toBe('audio received: 2.8 s of 3.0 s (complete)');
  });

  test('more samples than time held (clock skew) is complete, never negative', () => {
    expect(describeReceived(50_000, 48_000, 1, 0)).toBe('audio received: 1.0 s of 1.0 s (complete)');
  });

  test('an unknown hold time says only what arrived', () => {
    expect(describeReceived(24_000, 48_000, undefined, 0)).toBe('audio received: 0.5 s');
  });
});

describe('mixToMono (direct capture frames to one channel)', () => {
  test('a single channel is passed on as it is', () => {
    const plane = Float32Array.from([0.1, -0.2, 0.3]);
    expect(mixToMono([plane])).toBe(plane);
  });

  test('two channels are averaged', () => {
    const left = Float32Array.from([1, 0.5, -1, 0]);
    const right = Float32Array.from([0, 0.5, 1, 0]);
    expect(Array.from(mixToMono([left, right]))).toEqual([0.5, 0.5, 0, 0]);
  });

  test('three channels are averaged and the inputs are left alone', () => {
    const a = Float32Array.from([0.3, 0.6]);
    const b = Float32Array.from([0.3, 0.0]);
    const c = Float32Array.from([0.3, -0.3]);
    const mono = mixToMono([a, b, c]);
    expect(mono[0]).toBeCloseTo(0.3, 6);
    expect(mono[1]).toBeCloseTo(0.1, 6);
    expect(Array.from(a)).toEqual([Math.fround(0.3), Math.fround(0.6)]);
  });

  test('no channels give no samples', () => {
    expect(mixToMono([]).length).toBe(0);
  });
});
