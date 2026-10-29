// Run with `bun test app/tests`.
import { describe, expect, test } from 'bun:test';
import { audioBufferToWav } from '../src/lib/utils/audio';
import { concatPcm, PcmRing, pcmToWav, samplesToMs, totalSamples } from '../src/lib/utils/pcm';

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
