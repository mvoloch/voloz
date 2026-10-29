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
| To port | `/watchdog/disable` set a flag on a second copy of `server.py` (imported as `backend.server` while running as `__main__`), so keep-running relied on the sentinel alone | `a219b01` | new `fix/` branch, after the watchdog one |
| To port | Refinement: skip the LLM when nothing is left (silence loop); a Claude reply cut at `max_tokens` is an error; a stored key is dropped when its endpoint moves to another host | `8cfad68` | empty-input guard applies to upstream as is; the other two go with `feat/cloud-dictation` |
| To port | Remote STT uploads WAV at 16 kHz mono (48 kHz from the webview was 3x the bytes and hit 25 MB limits after ~4 min) | `f13529f` | add to `feat/cloud-dictation` |
| To port | Dictation: a hotkey tap released before `getUserMedia` resolved left the mic recording; overlapping dictations shared one focus snapshot (second one never pasted); a refine error dropped the dictation | `3353597` | new `fix/` branch (upstream `DictateWindow` / hooks) |
| To port | Capture details transcript did not refresh after a refine (uncontrolled textarea); "Qwen3 ·" hardcoded in refinedHint for es/fr/it/ja/ko/zh | part of `6f61567` | new `fix/` branch |
| To port | Failed settings saves rolled back silently; updater showed an error while no release is published (`ReleaseNotFound` not matched) | `b16e699` | new `fix/` branch |
| To port | Desktop: failed start left `state.child` set (dead server reported as running); exit relies on the 3 s watchdog; console windows flash from PowerShell/tasklist/taskkill; reuse of a server from another version | `8f74db6` | new `fix/` branch; the version check needs the server's `/` route, which upstream has |
| To port | TTS job failure only said "Worker exited without writing terminal status": roll the session back before writing the failure, keep the cause in the fallback message | `721b564` | new `fix/` branch |
| To port | Desktop: auto-paste on Windows went to the first window of the target process, not the focused one (snapshot now keeps the HWND); Alt+F4 on the dictate pill ran the quit flow and stopped the server; Linux mic permission missing on the rebuilt main window and the pill; audio-capture stop built the stop-signal future without awaiting it (Linux, Windows, macOS) | `5c29190` | new `fix/` branch; the tray rebuild part only applies with `0b6ecc7` |
| To port | Server: one unencodable character sent all later stdout/stderr to devnull; `init_db()` ran twice per startup; concurrent model loads loaded the model twice (`serialize_model_load` on the 15 local loaders) | `b3ff491` | new `fix/` branch |
| To port | Desktop: 120 s server-startup limit is too short for the Windows onefile server (1.3 GB unpacked per launch, 58–94 s on a Defender-scanned machine); now 300 s, and the timeout error no longer tells Windows/Linux users to check macOS's Console.app | `af3c449` | new `fix/` branch |
| To port | Dictation: a notice's hide timer hid the pill mid-recording; hands-free entered via `dictate:restart` was invisible and unbounded (now a 10 min cap); keytap's toggle stayed active after a non-chord stop (`reset_hotkey_state`); a paste while the chord was held became Win+Ctrl+V; stop read stale React state | `5aa3b5b` | new `fix/` branch |
| To port | i18n: relative dates had no date-fns locale for it/ko (and pt-BR); dictation pill and microphone messages were hardcoded English | `c9068b4` | it/ko and the keys apply upstream; the pt-BR part goes with `feat/cloud-dictation` |
