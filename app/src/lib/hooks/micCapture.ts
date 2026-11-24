/**
 * The recording microphone as raw PCM: one MediaStream feeding one
 * AudioContext graph — source → level analyser — and the samples read
 * straight from the track (``MediaStreamTrackProcessor``, mono, at the
 * device's rate), or, where the webview has none, from a PCM tap in the
 * graph (AudioWorklet) that posts mono blocks. A recording
 * ("take") keeps every block from the first one the stream delivers;
 * nothing waits for an encoder to start, so the first words are not lost.
 *
 * A dictation may keep the microphone open ("warm") for ``MIC_WARM_MS``
 * after it ends. Meanwhile the tap fills a ring of the last ``PREROLL_MS``,
 * so the next take starts at once and begins a little before the press.
 * The stream closes after that idle time (the OS microphone indicator goes
 * off), when its track ends, and when the audio devices change — a warm
 * stream never records from a device that is no longer the default.
 *
 * One per webview, shared by the hooks in it. The pure parts (ring, WAV)
 * are in `lib/utils/pcm.ts`.
 */
import { convertToWav } from '@/lib/utils/audio';
import {
  concatPcm,
  mixToMono,
  PcmRing,
  pcmToWav,
  samplesToMs,
  totalSamples,
} from '@/lib/utils/pcm';

/** How long the microphone stays open after a dictation, for the next one. */
export const MIC_WARM_MS = 3 * 60_000;
/** Audio from before the press that a warm microphone adds to a take. */
export const PREROLL_MS = 300;
/** Samples per block from the tap (~43 ms at 48 kHz). */
const BLOCK_FRAMES = 2048;
/** Longest wait for the tap's last partial block when a take stops. */
const FLUSH_TIMEOUT_MS = 1000;

const TAP_NAME = 'voloz-pcm-tap';

/**
 * The AudioWorklet processor. Its input is mixed down to one channel by
 * the node (``channelCount: 1``, ``speakers``); it posts full blocks, and
 * on ``{ flush: id }`` the partial one with that id.
 */
const TAP_SOURCE = `
class PcmTap extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.size = options.processorOptions.blockFrames;
    this.block = new Float32Array(this.size);
    this.filled = 0;
    this.port.onmessage = (event) => {
      const samples = this.block.slice(0, this.filled);
      this.filled = 0;
      this.port.postMessage({ samples, flush: event.data.flush }, [samples.buffer]);
    };
  }
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;
    for (let i = 0; i < channel.length; i++) {
      this.block[this.filled++] = channel[i];
      if (this.filled === this.size) {
        const full = this.block;
        this.block = new Float32Array(this.size);
        this.filled = 0;
        this.port.postMessage({ samples: full }, [full.buffer]);
      }
    }
    return true;
  }
}
registerProcessor('${TAP_NAME}', PcmTap);
`;

type Log = (message: string) => void;

interface Mic {
  stream: MediaStream;
  track: MediaStreamTrack;
  context: AudioContext;
  analyser: AnalyserNode;
  sampleRate: number;
  ring: PcmRing;
  /** The take receiving blocks; the ring gets them otherwise. */
  take: MicTake | null;
  /** Ask the tap for its partial block; resolves when it arrived (false: it never did). */
  flush: () => Promise<boolean>;
  /** The previous take's flush, which a new take waits for. */
  flushing: Promise<unknown>;
  /** The devices changed or the graph stalled: close when the take ends. */
  stale: boolean;
  idleTimer: number | null;
  closed: boolean;
  /** Shared by the webview's recordings (``current``); a private one closes after its take. */
  shared: boolean;
  log: Log;
  teardown: () => void;
}

let current: Mic | null = null;

/** One recording's samples from the microphone. */
export class MicTake {
  /** Blocks so far, pre-roll first. Final once ``stop`` resolved. */
  readonly blocks: Float32Array[];
  readonly prerollMs: number;
  /** Took over an open microphone instead of opening one. */
  readonly warm: boolean;
  private stopped: Promise<void> | null = null;
  private released = false;
  private firstBlockSeen: boolean;
  /** Called once if the microphone's track ends during the take. */
  onTrackEnded: (() => void) | null = null;
  private firstBlockCallback: (() => void) | null = null;

  constructor(
    private readonly mic: Mic,
    preroll: Float32Array[],
    warm: boolean,
    private readonly requestedAt: number,
  ) {
    this.blocks = preroll;
    this.warm = warm;
    this.prerollMs = samplesToMs(totalSamples(preroll), mic.sampleRate);
    this.firstBlockSeen = warm;
    if (warm) {
      mic.log(`mic warm: reused (pre-roll ${this.prerollMs} ms)`);
      mic.log(`first sample at -${this.prerollMs} ms from the start (warm mic)`);
    }
  }

  get sampleRate(): number {
    return this.mic.sampleRate;
  }

  get analyser(): AnalyserNode {
    return this.mic.analyser;
  }

  get deviceLabel(): string {
    return this.mic.track.label || 'unnamed device';
  }

  /** Whether ``stop`` was called: the microphone is no longer this take's. */
  get stopping(): boolean {
    return this.stopped !== null;
  }

  /**
   * Run ``callback`` once, when sound is flowing into this take: at once for
   * a warm microphone (it already has pre-roll), else when the first block
   * arrives. The pill uses it to tell the user the microphone is ready.
   */
  onFirstBlock(callback: () => void): void {
    if (this.firstBlockSeen) callback();
    else this.firstBlockCallback = callback;
  }

  /** A block from the tap, while this take is the receiver. */
  push(block: Float32Array): void {
    if (block.length === 0) return;
    this.blocks.push(block);
    if (!this.firstBlockSeen) {
      this.firstBlockSeen = true;
      // The block's first sample was captured about one block earlier.
      const ms = performance.now() - this.requestedAt - (block.length / this.mic.sampleRate) * 1000;
      this.mic.log(`first sample at +${Math.max(0, Math.round(ms))} ms from the start (cold mic)`);
      const callback = this.firstBlockCallback;
      this.firstBlockCallback = null;
      callback?.();
    }
  }

  /** Stop taking samples; resolves once the tap's last partial block is in. */
  stop(): Promise<void> {
    if (!this.stopped) {
      const mic = this.mic;
      const done = mic.flush().then((ok) => {
        if (!ok && !mic.closed) {
          mic.log('microphone: no final block from the audio graph');
          mic.stale = true;
        }
        if (mic.take === this) mic.take = null;
      });
      mic.flushing = done;
      this.stopped = done;
    }
    return this.stopped;
  }

  /**
   * Hand the microphone back: kept open for ``keepWarmMs`` (and filling the
   * pre-roll ring), or closed now when 0, when the devices changed or the
   * track ended. Stops the take first if it wasn't.
   */
  release(keepWarmMs: number): void {
    if (this.released) return;
    this.released = true;
    void this.stop();
    const mic = this.mic;
    if (mic.closed) return;
    if (!mic.shared || keepWarmMs <= 0) {
      closeMic(mic, 'microphone closed');
    } else if (mic.stale || mic.track.readyState !== 'live') {
      closeMic(mic, 'mic warm: closed (device changed or ended)');
    } else {
      mic.ring.clear();
      clearIdleTimer(mic);
      mic.idleTimer = window.setTimeout(() => {
        const idle =
          keepWarmMs >= 60_000
            ? `${Math.round(keepWarmMs / 60_000)} min`
            : `${Math.round(keepWarmMs / 1000)} s`;
        closeMic(mic, `mic warm: closed after ${idle} idle`);
      }, keepWarmMs);
      mic.log('mic warm: kept open');
    }
  }
}

/**
 * Start a take: on the open microphone when there is one (no wait, with
 * pre-roll), else open it. Rejects like getUserMedia when it can't.
 * ``requestedAt`` (``performance.now()``) is when the recording was asked
 * for, to log how long the first sample took.
 */
export async function startMicTake(log: Log, requestedAt: number): Promise<MicTake> {
  let mic = current;
  // The previous take's last block is still on its way (a press right
  // after a release): let it land in that take first.
  if (mic && !mic.closed && mic.take?.stopping) await mic.flushing;
  if (mic && !mic.closed && (mic.track.readyState !== 'live' || mic.context.state === 'closed')) {
    closeMic(mic, 'mic warm: closed (no longer live)');
  }
  if (mic?.closed) mic = null;
  if (mic && !mic.take) {
    clearIdleTimer(mic);
    mic.log = log;
    const take = new MicTake(mic, mic.ring.drain(), true, requestedAt);
    mic.take = take;
    return take;
  }
  // Another take holds the shared microphone (two recorders in one
  // window): this one gets its own, closed when it ends.
  const shared = !mic;
  const opened = await openMic(log, shared, requestedAt);
  if (shared) current = opened;
  const take = new MicTake(opened, [], false, requestedAt);
  opened.take = take;
  return take;
}

/**
 * The take's samples as mono 16-bit WAV, resampled to ``sampleRate`` when
 * given by the same conversion imported files go through
 * (``convertToWav`` with ``monoSampleRate``: the browser's decoder
 * resampler). Rejects if that conversion fails; the device-rate WAV is
 * ``pcmToWav``.
 */
export async function takeToWav(take: MicTake, sampleRate?: number): Promise<Blob> {
  const wav = pcmToWav(concatPcm(take.blocks), take.sampleRate);
  if (!sampleRate || sampleRate === take.sampleRate) return wav;
  return convertToWav(wav, { monoSampleRate: sampleRate });
}

async function openMic(log: Log, shared: boolean, requestedAt: number): Promise<Mic> {
  const askedAt = performance.now();
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  });
  // Diagnostic: when the browser handed the microphone over, on the page's
  // own clock (``performance.now()``, like "first sample at +N ms"), so
  // the three waits of a cold start can be told apart without the delay
  // the log line itself picks up on its way to the file: request to this
  // line, this line to "microphone open", "open" to the first sample.
  const grantedAt = performance.now();
  log(
    `microphone granted at +${Math.round(grantedAt - requestedAt)} ms from the start (getUserMedia took ${Math.round(grantedAt - askedAt)} ms)`,
  );
  let context: AudioContext | null = null;
  try {
    const track = stream.getAudioTracks()[0];
    if (!track) throw new DOMException('No audio track', 'NotFoundError');
    context = createContext(track.getSettings().sampleRate);
    const ctx = context;
    // Normally running already (Tauri's webviews allow audio without a
    // gesture); never awaited, a context the browser holds suspended would
    // block here.
    ctx.resume().catch(() => {});
    const source = ctx.createMediaStreamSource(stream);
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);

    const ring = new PcmRing(Math.round((PREROLL_MS / 1000) * ctx.sampleRate));
    const pendingFlushes = new Map<number, (ok: boolean) => void>();
    let flushSeq = 0;
    const mic: Mic = {
      stream,
      track,
      context: ctx,
      analyser,
      sampleRate: ctx.sampleRate,
      ring,
      take: null,
      flush: () => Promise.resolve(true),
      flushing: Promise.resolve(),
      stale: false,
      idleTimer: null,
      closed: false,
      shared,
      log,
      teardown: () => {},
    };
    const onBlock = (samples: Float32Array) => {
      if (mic.take) mic.take.push(samples);
      else ring.push(samples);
    };

    let tapNode: AudioNode | null = null;
    let keepAlive: GainNode | null = null;
    const direct = startDirectCapture(mic, onBlock);
    if (direct) {
      mic.flush = direct.flush;
      // The level analyser still lives in the graph. Silent on any output.
      keepAlive = ctx.createGain();
      keepAlive.gain.value = 0;
      analyser.connect(keepAlive);
      keepAlive.connect(ctx.destination);
      log('microphone: direct capture (the samples do not go through the audio graph)');
    } else if (typeof AudioWorkletNode !== 'undefined' && ctx.audioWorklet) {
      const url = URL.createObjectURL(new Blob([TAP_SOURCE], { type: 'text/javascript' }));
      try {
        await ctx.audioWorklet.addModule(url);
      } finally {
        URL.revokeObjectURL(url);
      }
      const tap = new AudioWorkletNode(ctx, TAP_NAME, {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        outputChannelCount: [1],
        channelCount: 1,
        channelCountMode: 'explicit',
        channelInterpretation: 'speakers',
        processorOptions: { blockFrames: BLOCK_FRAMES },
      });
      tap.port.onmessage = (event: MessageEvent<{ samples: Float32Array; flush?: number }>) => {
        onBlock(event.data.samples);
        if (event.data.flush !== undefined) {
          pendingFlushes.get(event.data.flush)?.(true);
          pendingFlushes.delete(event.data.flush);
        }
      };
      mic.flush = () =>
        new Promise<boolean>((resolve) => {
          const id = ++flushSeq;
          pendingFlushes.set(id, resolve);
          tap.port.postMessage({ flush: id });
          window.setTimeout(() => {
            if (pendingFlushes.delete(id)) resolve(false);
          }, FLUSH_TIMEOUT_MS);
        });
      tapNode = tap;
    } else {
      // No AudioWorklet in this webview: the deprecated main-thread
      // processor, which only ever delivers whole blocks.
      const processor = ctx.createScriptProcessor(BLOCK_FRAMES, 1, 1);
      processor.onaudioprocess = (event) => {
        onBlock(new Float32Array(event.inputBuffer.getChannelData(0)));
      };
      tapNode = processor;
      log('microphone: no AudioWorklet, using ScriptProcessor');
    }
    if (tapNode) {
      source.connect(tapNode);
      // The tap outputs silence; connected so the graph pulls it. With no
      // output device (``sinkId: none``) nothing is opened for it.
      tapNode.connect(ctx.destination);
    }

    const onEnded = () => {
      mic.log('microphone track ended');
      if (mic.take) {
        mic.stale = true;
        mic.take.onTrackEnded?.();
      } else {
        closeMic(mic, 'mic warm: closed (microphone ended)');
      }
    };
    // A graph that stops running records nothing meanwhile: say so.
    ctx.onstatechange = () => {
      if (!mic.closed) mic.log(`audio graph ${ctx.state}`);
    };
    const onMute = () => mic.log('microphone track muted');
    const onUnmute = () => mic.log('microphone track unmuted');
    const onDeviceChange = () => {
      if (mic.take) {
        if (!mic.stale)
          mic.log('audio devices changed: the microphone closes when this recording ends');
        mic.stale = true;
      } else {
        closeMic(mic, 'mic warm: closed (audio devices changed)');
      }
    };
    track.addEventListener('ended', onEnded);
    track.addEventListener('mute', onMute);
    track.addEventListener('unmute', onUnmute);
    navigator.mediaDevices.addEventListener?.('devicechange', onDeviceChange);
    mic.teardown = () => {
      track.removeEventListener('ended', onEnded);
      track.removeEventListener('mute', onMute);
      track.removeEventListener('unmute', onUnmute);
      navigator.mediaDevices.removeEventListener?.('devicechange', onDeviceChange);
      for (const resolve of pendingFlushes.values()) resolve(false);
      pendingFlushes.clear();
      ctx.onstatechange = null;
      direct?.stop();
      source.disconnect();
      tapNode?.disconnect();
      keepAlive?.disconnect();
    };

    log(
      `microphone open: ${track.label || 'unnamed device'} (${ctx.sampleRate} Hz, audio graph ${ctx.state}) at +${Math.round(performance.now() - requestedAt)} ms from the start`,
    );
    return mic;
  } catch (err) {
    stream.getTracks().forEach((track) => {
      track.stop();
    });
    context?.close().catch(() => {});
    throw err;
  }
}

/** Unread frames of audio the direct capture may hold: 6000 of 10 ms, a minute. */
const DIRECT_BUFFER_FRAMES = 6000;
/** How long ``flush`` lets the frames still on their way arrive. */
const DIRECT_FLUSH_MS = 20;

/** The part of WebCodecs' ``AudioData`` that the direct capture reads. */
interface AudioFrame {
  sampleRate: number;
  numberOfFrames: number;
  numberOfChannels: number;
  copyTo(destination: Float32Array, options: { planeIndex: number; format: 'f32-planar' }): void;
  close(): void;
}

type TrackProcessorConstructor = new (init: {
  track: MediaStreamTrack;
  maxBufferSize?: number;
}) => { readable: ReadableStream<AudioFrame> };

/** Set when the direct capture failed in this webview: later microphones use the audio graph. */
let directCaptureBroken = false;

interface DirectCapture {
  flush: () => Promise<boolean>;
  stop: () => void;
}

/**
 * Read the microphone's frames straight from the track
 * (``MediaStreamTrackProcessor``), without the audio graph. Why: the graph
 * is rendered by a thread that, on a busy computer, falls behind the
 * clock, and the capture buffer in front of it overflows in 10 ms pieces,
 * so recordings came out short, with clicks and missing syllables
 * (measured: 20-29 % of the audio lost with 8 cores busy). Frames read
 * directly lost nothing in the same tests, even with the page's main
 * thread frozen for seconds, as long as the queue is long
 * (``DIRECT_BUFFER_FRAMES``; the default one dropped half). Null when the
 * webview has no ``MediaStreamTrackProcessor`` (WebKitGTK) or it failed
 * here before: the caller then uses the audio-graph tap.
 */
function startDirectCapture(
  mic: Pick<Mic, 'track' | 'sampleRate' | 'log' | 'stale'>,
  onBlock: (samples: Float32Array) => void,
): DirectCapture | null {
  const Processor = (globalThis as { MediaStreamTrackProcessor?: TrackProcessorConstructor })
    .MediaStreamTrackProcessor;
  if (!Processor || directCaptureBroken) return null;
  let reader: ReadableStreamDefaultReader<AudioFrame>;
  try {
    reader = new Processor({ track: mic.track, maxBufferSize: DIRECT_BUFFER_FRAMES }).readable.getReader();
  } catch {
    return null;
  }
  let stopped = false;
  let delivered = 0;
  let checkedRate = false;
  void (async () => {
    try {
      while (!stopped) {
        const { value: frame, done } = await reader.read();
        if (done || !frame) break;
        try {
          if (!checkedRate) {
            checkedRate = true;
            if (frame.sampleRate !== mic.sampleRate) {
              mic.log(`microphone: frames at ${frame.sampleRate} Hz, the graph at ${mic.sampleRate} Hz`);
              mic.sampleRate = frame.sampleRate;
            }
          }
          const planes: Float32Array[] = [];
          for (let channel = 0; channel < frame.numberOfChannels; channel++) {
            const plane = new Float32Array(frame.numberOfFrames);
            frame.copyTo(plane, { planeIndex: channel, format: 'f32-planar' });
            planes.push(plane);
          }
          onBlock(mixToMono(planes));
          delivered++;
        } finally {
          frame.close();
        }
      }
    } catch (err) {
      if (!stopped) {
        directCaptureBroken = true;
        mic.stale = true;
        mic.log(
          `microphone: direct capture failed (${err instanceof Error ? err.name : String(err)}); the audio graph is used from now on`,
        );
      }
    }
  })();
  return {
    // The frames already queued are read in the same turn of the event loop,
    // so a short wait plus one new frame means everything captured before
    // the call is in (the audio-graph tap's flush gives the same).
    flush: () =>
      new Promise<boolean>((resolve) => {
        const seen = delivered;
        const started = performance.now();
        const poll = () => {
          if (delivered > seen) return resolve(true);
          if (stopped || performance.now() - started >= FLUSH_TIMEOUT_MS) return resolve(false);
          window.setTimeout(poll, 5);
        };
        window.setTimeout(poll, DIRECT_FLUSH_MS);
      }),
    stop: () => {
      stopped = true;
      reader.cancel().catch(() => {});
    },
  };
}

/**
 * An AudioContext at the microphone's rate with no output device where
 * the browser supports one (Chromium's ``sinkId: { type: 'none' }``): it
 * never opens or follows a speaker. With the direct capture it only feeds
 * the level meter; without it, it also carries the samples.
 */
function createContext(sampleRate: number | undefined): AudioContext {
  const base: AudioContextOptions = { latencyHint: 'playback' };
  if (sampleRate) base.sampleRate = sampleRate;
  const attempts: AudioContextOptions[] = [
    { ...base, sinkId: { type: 'none' } } as AudioContextOptions,
    base,
    {},
  ];
  for (const options of attempts) {
    try {
      return new AudioContext(options);
    } catch {
      // next
    }
  }
  return new AudioContext();
}

function clearIdleTimer(mic: Mic): void {
  if (mic.idleTimer !== null) {
    window.clearTimeout(mic.idleTimer);
    mic.idleTimer = null;
  }
}

function closeMic(mic: Mic, message: string): void {
  if (mic.closed) return;
  mic.closed = true;
  clearIdleTimer(mic);
  mic.teardown();
  mic.stream.getTracks().forEach((track) => {
    track.stop();
  });
  mic.context.close().catch(() => {});
  mic.ring.clear();
  if (current === mic) current = null;
  mic.log(message);
}
