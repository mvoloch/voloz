import { IS_LITE } from '@/lib/edition';
import { cn } from '@/lib/utils/cn';

/** "voloz" set in Space Grotesk Medium, as outlines (from branding/voloz-wordmark.svg). */
const WORD_PATH =
  'M21.35 14 18.5 5.55H20.37L22.61 12.7H22.89L25.13 5.55H27L24.16 14ZM32.29 14.24Q31.02 14.24 30.03 13.72Q29.05 13.19 28.48 12.22Q27.91 11.24 27.91 9.9V9.65Q27.91 8.31 28.48 7.34Q29.05 6.37 30.03 5.84Q31.02 5.31 32.29 5.31Q33.55 5.31 34.55 5.84Q35.54 6.37 36.11 7.34Q36.67 8.31 36.67 9.65V9.9Q36.67 11.24 36.11 12.22Q35.54 13.19 34.55 13.72Q33.55 14.24 32.29 14.24ZM32.29 12.66Q33.45 12.66 34.18 11.92Q34.91 11.17 34.91 9.85V9.7Q34.91 8.38 34.19 7.63Q33.47 6.89 32.29 6.89Q31.14 6.89 30.41 7.63Q29.68 8.38 29.68 9.7V9.85Q29.68 11.17 30.41 11.92Q31.14 12.66 32.29 12.66ZM38.77 14V2H40.53V14ZM47.02 14.24Q45.75 14.24 44.76 13.72Q43.78 13.19 43.21 12.22Q42.65 11.24 42.65 9.9V9.65Q42.65 8.31 43.21 7.34Q43.78 6.37 44.76 5.84Q45.75 5.31 47.02 5.31Q48.29 5.31 49.28 5.84Q50.27 6.37 50.84 7.34Q51.41 8.31 51.41 9.65V9.9Q51.41 11.24 50.84 12.22Q50.27 13.19 49.28 13.72Q48.29 14.24 47.02 14.24ZM47.02 12.66Q48.18 12.66 48.91 11.92Q49.64 11.17 49.64 9.85V9.7Q49.64 8.38 48.92 7.63Q48.2 6.89 47.02 6.89Q45.87 6.89 45.14 7.63Q44.41 8.38 44.41 9.7V9.85Q44.41 11.17 45.14 11.92Q45.87 12.66 47.02 12.66ZM52.93 14V11.84L57.92 7.28V7.04H53.11V5.55H59.84V7.73L54.84 12.29V12.51H59.98V14Z';

/** Voice bars on the 16-unit grid: [x, y, height]. */
const BARS = [
  [1, 6, 4],
  [4, 3, 10],
  [7, 5, 6],
] as const;

interface VolozWordmarkProps {
  /** Pulse the bars like a level meter and blink the caret. */
  animated?: boolean;
  className?: string;
}

/**
 * Horizontal Voloz logo: three voice bars, a text caret and the name.
 * Bars and name follow the text colour; the caret uses the accent colour.
 */
export function VolozWordmark({ animated = false, className }: VolozWordmarkProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 60.98 16"
      role="img"
      aria-label="Voloz"
      className={cn('text-foreground', className)}
    >
      <g fill="currentColor">
        {BARS.map(([x, y, h], i) => (
          <rect
            key={x}
            x={x}
            y={y}
            width={2}
            height={h}
            rx={1}
            className={animated ? `voloz-level voloz-level-${i + 1}` : undefined}
          />
        ))}
      </g>
      {/* Lite paints the caret in the voice red; Studio in its accent */}
      <g
        fill={IS_LITE ? 'hsl(var(--signal))' : 'hsl(var(--accent))'}
        className={animated ? 'voloz-caret' : undefined}
      >
        <rect x={12} y={2} width={2} height={12} />
        <rect x={11} y={2} width={4} height={1} />
        <rect x={11} y={13} width={4} height={1} />
      </g>
      <path fill="currentColor" d={WORD_PATH} />
    </svg>
  );
}
