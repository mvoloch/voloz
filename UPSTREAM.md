# Giving back to Voicebox

Improvements made in Voloz that are worth offering to
[jamiepine/voicebox](https://github.com/jamiepine/voicebox). The owner decides
when each one is sent; until then the fix lives only in Voloz (`wisprclone`).

How to keep this list:

- Add a row whenever a commit on `wisprclone` fixes or improves something that
  is not Voloz-specific (branding, updater, release workflow and the two-edition
  split stay here).
- When it is time to send one, put it on a branch cut from `upstream/main`
  (`fix/...` or `feat/...`), with its tests, and open the PR from there.

| Status | What | Voloz commit | Upstream-ready branch |
| --- | --- | --- | --- |
| PR open | Stale progress test: simulate a download above the tracker's 1 MB floor | `e56cd44` | `fix/test-hf-progress-threshold` → [#1220](https://github.com/jamiepine/voicebox/pull/1220) |
| Ready, not sent | Parent-PID watchdog never started (FastAPI ignores `on_event` with a lifespan), so a force-closed app left its server running and the next launch reused it | `a9ee027` | `fix/parent-watchdog-lifespan` (`3f9791c`) |
| Ready, not sent | Cloud transcription (any OpenAI-compatible `/audio/transcriptions`) and native Claude refinement; Portuguese in the language picker | `11a73ab` | `feat/cloud-dictation` |
| To port | Pooled HTTP connection for remote STT | `29d3ec9` | add to `feat/cloud-dictation` |
| To port | First-dictation warm-up: decode a silent clip and open remote connections at startup; `POST /capture/warmup` on record | `200c026` | add to `feat/cloud-dictation` |
| To port | Model line and "Refined with" show the custom STT/LLM model instead of hardcoded Whisper/Qwen3 | part of `f522394` | new `fix/` branch (split from the Voloz layout) |
| To discuss | Close to tray, single instance, free the hidden webview | `0b6ecc7` | behaviour change; ask upstream first |
