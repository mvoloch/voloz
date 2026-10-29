/**
 * Microphone samples as plain Float32 PCM: the pre-roll ring and the WAV a
 * recording becomes. Free of the DOM so it can be unit-tested
 * (`app/tests/pcm.test.ts`); the capture graph lives in
 * `lib/hooks/micCapture.ts`.
 */
// Relative, so `bun test` resolves it without the app's path aliases.
import { audioBufferToWav } from './audio';

/**
 * The most recent samples of an open microphone, so a recording started
 * while it is open can begin a little before the press. Holds whole blocks
 * as they arrive and trims to ``capacity`` samples when drained.
 */
export class PcmRing {
  private blocks: Float32Array[] = [];
  private size = 0;

  constructor(readonly capacity: number) {}

  /** Samples held (may exceed ``capacity`` by less than one block). */
  get length(): number {
    return this.size;
  }

  push(block: Float32Array): void {
    if (block.length === 0) return;
    this.blocks.push(block);
    this.size += block.length;
    // Drop the oldest blocks no longer needed to cover ``capacity``.
    while (this.blocks.length > 1 && this.size - this.blocks[0].length >= this.capacity) {
      this.size -= this.blocks[0].length;
      this.blocks.shift();
    }
  }

  /** The last ``capacity`` samples (all of them when fewer), oldest first; empties the ring. */
  drain(): Float32Array[] {
    const blocks = this.blocks;
    // Less than the first block's length, by the invariant ``push`` keeps.
    const excess = this.size - this.capacity;
    this.blocks = [];
    this.size = 0;
    if (excess > 0) blocks[0] = blocks[0].subarray(excess);
    return blocks;
  }

  clear(): void {
    this.blocks = [];
    this.size = 0;
  }
}

export function totalSamples(blocks: readonly Float32Array[]): number {
  let n = 0;
  for (const block of blocks) n += block.length;
  return n;
}

/** One contiguous buffer from consecutive blocks. */
export function concatPcm(blocks: readonly Float32Array[]): Float32Array<ArrayBuffer> {
  const out = new Float32Array(totalSamples(blocks));
  let offset = 0;
  for (const block of blocks) {
    out.set(block, offset);
    offset += block.length;
  }
  return out;
}

/** Duration of ``samples`` at ``sampleRate``, in whole milliseconds. */
export function samplesToMs(samples: number, sampleRate: number): number {
  return sampleRate > 0 ? Math.round((samples / sampleRate) * 1000) : 0;
}

/**
 * Mono 16-bit PCM WAV of the samples, written by the same writer the
 * decode-to-WAV conversion uses, so the same samples give the same bytes.
 */
export function pcmToWav(samples: Float32Array<ArrayBuffer>, sampleRate: number): Blob {
  return audioBufferToWav({
    numberOfChannels: 1,
    sampleRate,
    length: samples.length,
    getChannelData: () => samples,
  });
}
