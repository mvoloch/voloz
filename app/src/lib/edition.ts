/**
 * Which edition this build is, fixed at build time by ``VITE_EDITION``.
 *
 * ``lite`` is dictation only: the Captures home and Settings, for the lite
 * server that has no voice generation, voice profiles or local models
 * (transcription and refinement go to the user's own endpoints). Unset or
 * anything else builds the full studio app.
 */
export const IS_LITE = import.meta.env.VITE_EDITION === 'lite';

// The lite look (index.css: ``.edition-lite``) hangs off <html>, so every
// window of the app (main, pill) gets it before the first paint.
if (IS_LITE && typeof document !== 'undefined') {
  document.documentElement.classList.add('edition-lite');
}
