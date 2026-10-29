/**
 * Which edition this build is, fixed at build time by ``VITE_EDITION``.
 *
 * ``lite`` is dictation only: the Captures home and Settings, for the lite
 * server that has no voice generation, voice profiles or local models
 * (transcription and refinement go to the user's own endpoints). Unset or
 * anything else builds the full studio app.
 */
export const IS_LITE = import.meta.env.VITE_EDITION === 'lite';
