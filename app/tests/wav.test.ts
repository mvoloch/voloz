// Run with `bun test app/tests`.
import { afterEach, describe, expect, test } from 'bun:test';
import { audioBufferToWav, convertToWav, type WavSource } from '../src/lib/utils/audio';

function source(channels: number[][], sampleRate: number): WavSource {
  return {
    numberOfChannels: channels.length,
    sampleRate,
    length: channels[0].length,
    getChannelData: (i: number) => Float32Array.from(channels[i]),
  };
}

async function header(blob: Blob) {
  const view = new DataView(await blob.arrayBuffer());
  return {
    channels: view.getUint16(22, true),
    sampleRate: view.getUint32(24, true),
    bits: view.getUint16(34, true),
    dataBytes: view.getUint32(40, true),
    samples: Array.from({ length: (view.byteLength - 44) / 2 }, (_, i) =>
      view.getInt16(44 + i * 2, true),
    ),
  };
}

describe('audioBufferToWav', () => {
  test('writes 16-bit PCM with the source rate and channel count', async () => {
    const wav = await header(audioBufferToWav(source([[0, 1, -1, 0.5]], 16_000)));
    expect(wav).toEqual({
      channels: 1,
      sampleRate: 16_000,
      bits: 16,
      dataBytes: 8,
      samples: [0, 0x7fff, -0x8000, Math.floor(0.5 * 0x7fff)],
    });
  });
});

describe('convertToWav with monoSampleRate', () => {
  const original = (globalThis as { OfflineAudioContext?: unknown }).OfflineAudioContext;
  afterEach(() => {
    (globalThis as { OfflineAudioContext?: unknown }).OfflineAudioContext = original;
  });

  test('decodes at the target rate and mixes stereo down to one channel', async () => {
    const contexts: number[][] = [];
    class FakeOfflineContext {
      destination = {};
      constructor(channels: number, length: number, rate: number) {
        contexts.push([channels, length, rate]);
      }
      async decodeAudioData() {
        return source(
          [
            [0.5, 0.5, 0.5],
            [0, 0, 0],
          ],
          16_000,
        );
      }
      createBufferSource() {
        return { buffer: null, connect() {}, start() {} };
      }
      async startRendering() {
        return source([[0.25, 0.25, 0.25]], 16_000);
      }
    }
    (globalThis as { OfflineAudioContext?: unknown }).OfflineAudioContext = FakeOfflineContext;

    const wav = await header(
      await convertToWav(new Blob([new Uint8Array(8)]), { monoSampleRate: 16_000 }),
    );
    expect(contexts).toEqual([
      [1, 1, 16_000],
      [1, 3, 16_000],
    ]);
    expect(wav.channels).toBe(1);
    expect(wav.sampleRate).toBe(16_000);
    expect(wav.samples.length).toBe(3);
  });

  test('rejects when the webview cannot decode the format', async () => {
    class FailingContext {
      async decodeAudioData(): Promise<never> {
        throw new Error('Unable to decode audio data');
      }
    }
    (globalThis as { OfflineAudioContext?: unknown }).OfflineAudioContext = FailingContext;
    await expect(
      convertToWav(new Blob([new Uint8Array(8)]), { monoSampleRate: 16_000 }),
    ).rejects.toThrow('Unable to decode');
  });
});
