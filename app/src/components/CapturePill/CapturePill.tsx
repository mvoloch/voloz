import { motion } from 'framer-motion';
import { Check, Loader2, TriangleAlert, Volume2 } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { pillT } from '@/i18n';
import { IS_LITE } from '@/lib/edition';
import { cn } from '@/lib/utils/cn';
import {
  BAR_MIN_PX,
  barHeights,
  levelFromRms,
  rms,
  smoothLevel,
} from './vuMeter';

/**
 * Pill state machine shared between the settings preview and the live
 * recording pill in the Captures tab.
 */
export type PillState =
  | 'recording'
  | 'transcribing'
  | 'refining'
  | 'speaking'
  | 'completed'
  | 'rest'
  | 'error';

const PILL_LABEL_KEYS: Record<Exclude<PillState, 'rest' | 'error'>, string> = {
  recording: 'captures.pill.recording',
  transcribing: 'captures.pill.transcribing',
  refining: 'captures.pill.refining',
  speaking: 'captures.pill.speaking',
  completed: 'captures.pill.completed',
};

function barModeFor(
  state: Exclude<PillState, 'error'>,
): 'generating' | 'playing' | 'idle' {
  if (state === 'recording' || state === 'speaking') return 'playing';
  if (state === 'completed' || state === 'rest') return 'idle';
  return 'generating';
}

export function PillAudioBars({ mode }: { mode: 'generating' | 'playing' | 'idle' }) {
  return (
    <div className="flex items-center gap-[2px] h-5 shrink-0">
      {[0, 1, 2, 3, 4].map((i) => (
        <motion.div
          key={`${mode}-${i}`}
          className={cn(
            'w-[3px] rounded-full',
            IS_LITE
              ? mode === 'idle'
                ? 'bg-white/30'
                : 'bg-white'
              : mode === 'idle'
                ? 'bg-accent/30'
                : 'bg-accent',
          )}
          animate={
            mode === 'generating'
              ? { height: ['6px', '16px', '6px'] }
              : mode === 'playing'
                ? { height: ['8px', '14px', '4px', '12px', '8px'] }
                : { height: '8px' }
          }
          transition={
            mode === 'generating'
              ? { duration: 0.6, repeat: Infinity, delay: i * 0.08, ease: 'easeInOut' }
              : mode === 'playing'
                ? { duration: 1.2, repeat: Infinity, delay: i * 0.15, ease: 'easeInOut' }
                : { duration: 0.4, ease: 'easeOut' }
          }
        />
      ))}
    </div>
  );
}

/**
 * The recording's level meter: the same five bars, driven by the
 * microphone's actual level (RMS per animation frame of the recording
 * graph's ``AnalyserNode``, so no second AudioContext). Flat in silence,
 * moving only with sound. Heights are written to the DOM directly so the
 * meter doesn't re-render React 60 times a second. It stops reading when
 * the analyser goes away (recording ended) or the pill unmounts.
 */
/** One per entry of ``BAR_WEIGHTS``, as stable React keys. */
const LEVEL_BAR_IDS = ['outer-left', 'left', 'center', 'right', 'outer-right'] as const;

export function PillLevelBars({ analyser }: { analyser: AnalyserNode | null }) {
  const barsRef = useRef<(HTMLDivElement | null)[]>([]);

  useEffect(() => {
    const paint = (level: number) => {
      const heights = barHeights(level);
      barsRef.current.forEach((bar, i) => {
        if (bar) bar.style.height = `${heights[i]}px`;
      });
    };
    paint(0);
    if (!analyser) return;

    const samples = new Float32Array(analyser.fftSize);
    let level = 0;
    let frame = requestAnimationFrame(function tick() {
      analyser.getFloatTimeDomainData(samples);
      level = smoothLevel(level, levelFromRms(rms(samples)));
      paint(level);
      frame = requestAnimationFrame(tick);
    });

    return () => {
      cancelAnimationFrame(frame);
    };
  }, [analyser]);

  return (
    <div className="flex items-center gap-[2px] h-5 shrink-0">
      {LEVEL_BAR_IDS.map((id, i) => (
        <div
          key={id}
          ref={(el) => {
            barsRef.current[i] = el;
          }}
          className={cn('w-[3px] rounded-full', IS_LITE ? 'bg-white' : 'bg-accent')}
          style={{ height: `${BAR_MIN_PX}px` }}
        />
      ))}
    </div>
  );
}

/**
 * The leading status symbol, in the vocabulary of phone status pills: a red
 * dot while recording (hands-free too), a spinner while the text is being
 * made, a speaker for agent speech, a check when done.
 */
function PillSymbol({ state }: { state: Exclude<PillState, 'error'> }) {
  if (state === 'recording') {
    return (
      <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center" aria-hidden>
        <span className={cn('h-2.5 w-2.5 rounded-full animate-pulse', IS_LITE ? 'bg-signal' : 'bg-red-500')} />
      </span>
    );
  }
  if (state === 'transcribing' || state === 'refining') {
    return <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />;
  }
  if (state === 'speaking') {
    return <Volume2 className="h-3.5 w-3.5 shrink-0" aria-hidden />;
  }
  return (
    <Check
      className={cn('h-3.5 w-3.5 shrink-0', IS_LITE && 'text-emerald-400')}
      strokeWidth={3}
      aria-hidden
    />
  );
}

function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Floating pill shown during capture. `state` drives the label, symbol,
 * and bar motion; `elapsedMs` freezes at whatever the caller last passed in
 * (recording advances the timer, transcribing/refining hold the final value).
 * The ``error`` state renders a destructive variant — a clickable pill that
 * copies its message (or ``errorCopyText``) to the clipboard on press and
 * calls ``onDismiss``.
 */
export function CapturePill({
  state,
  elapsedMs,
  onStop,
  stopTitle,
  handsFree,
  levelSource,
  errorMessage,
  errorCopyText,
  onDismiss,
  notch = false,
  className,
}: {
  state: PillState;
  elapsedMs: number;
  onStop?: () => void;
  /** Tooltip of the stop button; defaults to its aria label. */
  stopTitle?: string;
  /** A hands-free recording: labelled so, since only the chord or stop ends it. */
  handsFree?: boolean;
  /**
   * The recording microphone's level analyser. When given (null while the
   * mic is opening), the recording bars are a level meter instead of an
   * animation.
   */
  levelSource?: AnalyserNode | null;
  errorMessage?: string | null;
  /** What a click on the error pill copies instead of its message. */
  errorCopyText?: string | null;
  onDismiss?: () => void;
  /** Lite: drawn as a notch hanging from the top edge of the screen (the dictate window). */
  notch?: boolean;
  className?: string;
}) {
  // English in every app language, like a phone's status pill.
  const t = pillT;

  if (state === 'error') {
    return (
      <ErrorPill
        message={errorMessage ?? t('captures.pill.errorFallback')}
        copyText={errorCopyText ?? null}
        onDismiss={onDismiss}
        notch={notch}
        className={className}
      />
    );
  }

  const visible = state !== 'rest';
  const labelText = t(
    state === 'recording' && handsFree
      ? 'captures.pill.handsFree'
      : state === 'rest'
        ? PILL_LABEL_KEYS.recording
        : PILL_LABEL_KEYS[state],
  );
  const barMode = barModeFor(state);

  // While recording, a stop button (■, the universal stop) closes the pill
  // after the timer: a 24 px target pulled into the right padding.
  const stopButton = onStop && state === 'recording' ? (
    <button
      type="button"
      onClick={onStop}
      aria-label={t('captures.pill.stopAria')}
      title={stopTitle ?? t('captures.pill.stopAria')}
      className={cn(
        '-mr-2 flex h-6 w-6 shrink-0 cursor-pointer items-center justify-center rounded-full transition-colors focus:outline-none focus-visible:ring-2',
        IS_LITE
          ? 'bg-white/15 hover:bg-white/25 focus-visible:ring-white/50'
          : 'bg-accent/15 hover:bg-accent/30 focus-visible:ring-accent/50',
      )}
    >
      <span className={cn('h-2.5 w-2.5 rounded-[2px]', IS_LITE ? 'bg-white' : 'bg-accent')} />
    </button>
  ) : null;

  // Completed gets an inset accent stroke (via box-shadow, not Tailwind's
  // ring — ring utility doesn't compose with arbitrary shadow-[…]) to mark
  // the success moment without changing the pill's dimensions.
  const completedStroke =
    state === 'completed' && !IS_LITE
      ? 'shadow-[inset_0_0_0_2px_hsl(var(--accent)/0.6)]'
      : null;

  return (
    <div
      className={cn(
        'inline-flex items-center gap-3',
        IS_LITE
          ? cn(
              // Lite: black like the phone's hardware, white type, red only for the mic
              'bg-[#0B0B0C] text-white/90',
              notch ? 'pill-notch h-[38px] rounded-b-[19px] px-5' : 'h-10 rounded-full px-4 shadow-lg',
            )
          : cn(
              'px-4 h-10 rounded-full text-accent',
              'bg-white/80 ring-1 ring-black/5 shadow-lg backdrop-blur-xl',
              'dark:bg-black/55 dark:ring-0 dark:shadow-none dark:backdrop-blur-md',
            ),
        completedStroke,
        'transition-opacity duration-300 ease-out',
        visible ? 'opacity-100' : 'opacity-0 pointer-events-none',
        className,
      )}
    >
      <PillSymbol state={state} />
      <span className="text-sm font-medium shrink-0" style={{ minWidth: '104px' }}>
        {labelText}
      </span>
      {state === 'recording' && levelSource !== undefined ? (
        <PillLevelBars analyser={levelSource} />
      ) : (
        <PillAudioBars mode={barMode} />
      )}
      <span
        className={cn(
          'text-xs tabular-nums font-medium shrink-0 -ml-1',
          IS_LITE ? 'font-mono text-white/55' : 'text-accent/70',
        )}
      >
        {formatElapsed(elapsedMs)}
      </span>
      {stopButton}
    </div>
  );
}

function ErrorPill({
  message,
  copyText,
  onDismiss,
  notch = false,
  className,
}: {
  message: string;
  copyText: string | null;
  onDismiss?: () => void;
  notch?: boolean;
  className?: string;
}) {
  const t = pillT;
  const handleClick = async () => {
    try {
      await navigator.clipboard.writeText(copyText ?? message);
    } catch {
      // Clipboard access can be denied in rare webview configs — ignore,
      // we still want the dismiss to land.
    }
    onDismiss?.();
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      title={t(copyText ? 'captures.pill.copyTextTooltip' : 'captures.pill.errorCopyTooltip')}
      className={cn(
        'inline-flex items-center gap-2.5',
        IS_LITE
          ? cn(
              'bg-[#0B0B0C] text-red-300 hover:bg-black',
              notch ? 'pill-notch h-[38px] rounded-b-[19px] px-5' : 'h-10 rounded-full px-4 shadow-lg',
            )
          : cn(
              'px-4 h-10 rounded-full',
              'bg-white/85 ring-1 ring-destructive/25 shadow-lg backdrop-blur-xl text-red-600 hover:bg-white',
              'dark:bg-black/65 dark:ring-0 dark:shadow-none dark:backdrop-blur-md dark:text-red-300 dark:hover:bg-black/80',
            ),
        // 370 + the notch ears fit the 420 px pill window
        IS_LITE ? 'max-w-[370px] transition-colors' : 'max-w-[380px] transition-colors',
        'focus:outline-none focus:ring-2 focus:ring-red-400/50',
        className,
      )}
    >
      <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
      <span className="text-sm font-medium truncate">{message}</span>
    </button>
  );
}

