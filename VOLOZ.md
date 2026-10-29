# Voloz — project status and handover

Voloz is a public fork of [jamiepine/voicebox](https://github.com/jamiepine/voicebox)
(MIT — keep the upstream credit) maintained at
[mvoloch/voloz](https://github.com/mvoloch/voloz). Goal: Wispr Flow–style
dictation (hotkey → speak → polished text pasted anywhere) without Wispr's
cost or weekly word cap, usable across Windows, Linux and Android, with the
user choosing cloud APIs or local models.

_Last updated: 2026-10-28 (0.8.1: audit fixes, Groq, open findings fixed). Update this file whenever state or decisions change._

## Where things are

| What | Where |
| --- | --- |
| Source of truth (code + this doc) | GitHub `mvoloch/voloz` |
| Main dev machine | GB4 (Linux, `ssh gb4`), checkout at `/mnt/data/dev/voloz` |
| Windows installers | GitHub Actions on tag push (`v*`) → **draft** release on the fork |
| Secrets (never in git) | GB4 `~/.config/wisprclone/` (`keys.env`, updater signing key + password), mode 600 |

Remotes on GB4: `origin` = mvoloch/voloz, `upstream` = jamiepine/voicebox.

## Branches

- `feat/cloud-dictation` — upstreamable feature, kept clean for a PR to
  jamiepine/voicebox: OpenAI-compatible remote STT backend
  (`backend/backends/openai_compat_stt_backend.py`), native Claude refinement
  backend (`backend/backends/anthropic_backend.py`, used when the custom LLM
  endpoint is `api.anthropic.com`), Portuguese in the language picker,
  pt-BR strings, tests in `backend/tests/test_cloud_dictation.py`.
- `wisprclone` — the fork's working branch = feature + fork-only config:
  updater pubkey/endpoint → this fork, `release.yml` builds Windows only,
  close-to-tray + single instance + main webview freed when hidden
  (`tauri/src-tauri/src/tray.rs`), pooled STT HTTP connection,
  `scripts/cloud-dictation/`, version 0.6.1.
- The fork also carries ~75 upstream branches inherited at fork time
  (cleanup pending — ask the owner first).

## Decisions

- **Two editions, one codebase:** **Voloz** (lite, Wispr-like: Captures +
  Settings only) and **Voloz Studio** (full: voice generation, cloning,
  stories, effects). Both minimise to the tray. In both the user chooses
  cloud APIs or local models. The lite installer ships cloud-only; local
  models come as an optional on-demand download (reuse the CUDA-libs
  download mechanism Voicebox already has).
- **Transcription:** Groq direct, `https://api.groq.com/openai/v1` with
  `whisper-large-v3-turbo` (since 2026-10-28; was OpenRouter). Same model,
  about 4x faster here and steady; it also honours Whisper's `prompt`.
  Rollback: set the STT endpoint back to `https://openrouter.ai/api/v1`,
  model `openai/whisper-large-v3-turbo`, with the OpenRouter key.
- **Refinement model:** `claude-haiku-5-5` (best quality of 5 measured; see
  below). Native SDK, `effort: "low"`, no sampling parameters.
- **Builds:** Windows on GitHub Actions (PyInstaller can't cross-compile
  from Linux); Linux builds on GB4.
- **Platforms:** Windows → Linux → Android (floating bubble + Accessibility
  Service, like Wispr Flow Android). iOS and macOS in the backlog.

## Measurements (2026-10-27)

24 s of Portuguese speech:

| Path | Result |
| --- | --- |
| Local, CPU only (Core Ultra 7 268V): Whisper Turbo + Qwen3 0.6B–1.7B | 15–31 s; Qwen barely refined |
| Cloud in the installed app: OpenRouter `whisper-large-v3-turbo` + Haiku | 2.6–6.8 s, median 3.2 s |
| STT by provider (OpenRouter routing) | Groq 0.6–0.8 s; DeepInfra 1.7–5 s |

Refinement, same Voicebox prompt, 9 calls each: Haiku 1.36 s (best quality),
gpt-oss-120b 1.49 s, llama-3.3-70b 1.42 s, gemini-3.5-flash-lite 1.67 s,
gemini-2.5-flash-lite 0.86 s (fastest, weaker cleanup).

Memory, app idle on Windows: 1.24 GB (WebView2 785 MB in 9 processes,
Python server 362 MB — ~337 MB of it is PyTorch loaded at startup, Rust
89 MB). Idle CPU ~0.

### Groq vs OpenRouter (2026-10-28, Dell, 25 s Portuguese clip)

STT only, warm connection, 6 runs each, median (min–max):

| Endpoint | 48 kHz WAV (what the app sent) | 16 kHz WAV |
| --- | --- | --- |
| OpenRouter | 3.40 s (2.28–3.86); a later run 2.19 s | 1.21 s (0.72–2.13) |
| Groq | 0.87 s (0.72–1.05); a later run 0.45 s | 0.63 s (0.46–0.87) |

Whole dictation in the installed 0.8.0 app after switching to Groq, 8 runs:
transcribe median 0.61 s, refine (Haiku) median 1.2 s, total median 1.8 s
(1.6–3.0 s), against a 3.2 s median with OpenRouter on 2026-10-27. Refinement
is now the larger share. English and Portuguese clips (2 + 4) kept their
language through refinement; none was translated. Since `f13529f` the app
uploads 16 kHz itself. One clip, one machine, one evening: indicative.

### First-dictation latency (fixed in `200c026`, shipped in 0.8.0)

Installed 0.7.0 on the Dell: first dictation after launch 15.3 s, later
ones 1.1–3.3 s. Measured in a fresh process: first audio decode 1.36 s
(GB4) / 3.86 s (Windows, from source; the frozen app adds more, likely
antivirus scanning DLLs on first load — inferred), first remote
transcription ~1.5–2 s slower than the next. Fix: decode a silent clip and
open the remote connections at server startup, and `POST /capture/warmup`
when recording starts (HEAD for STT, `models.list` for Claude, 120 s
keep-alive). Still to confirm in an installed build.

## Next steps

1. ~~Rename inside the app~~ — done in `fb654ee`: product "Voloz",
   identifier `io.github.mvoloch.voloz`, updater → mvoloch/voloz, README and
   About credit Voicebox. Kept on purpose: `X-Voicebox-Client-Id`,
   `voicebox_*` MCP tools, `voicebox-server`/`voicebox-mcp` sidecars. The new
   identifier is a separate install and data dir: uninstall Voicebox 0.6.x
   first (both use port 17493) and reconfigure endpoints/keys
   (`scripts/cloud-dictation/configure-voicebox.ps1`).
2. Lite edition: build flag that hides Studio routes in the frontend and a
   torch-free backend (lazy PyTorch: `backend/app.py`, `routes/health.py`,
   `utils/hf_offline_patch.py` import it at startup today).
3. STT: ~~Groq direct~~ (done 2026-10-28, see Decisions). Still to do: a
   vocabulary/prompt setting for names and jargon (Whisper `prompt`; Groq
   honours it, OpenRouter ignored it).
4. Streaming STT + incremental refinement to approach Wispr's ~0.7 s.
5. Linux build and test on GB4; then Android.
6. Studio: cloud TTS/cloning engine (ElevenLabs/Cartesia/Fish Audio).
   Known bug: a Qwen3-TTS 1.7B generation on CPU failed with the generic
   "Worker exited without writing terminal status"
   (`backend/services/task_queue.py`); retry and delete also failed — root
   cause not captured yet. Since `721b564` the next failure stores its real
   cause; likely candidates are a SQLite error or memory (1.7B loads as fp32
   on CPU, several GB).
7. Installer and updater choices (need the owner's call before the first
   public release): the updater always installs the MSI (`windows-x86_64`
   in latest.json), which needs admin on every update — ship NSIS only or
   prefer NSIS in latest.json; set `bundle.publisher` (today "github", from
   the identifier); drop `v1Compatible` updater zips (+1.1 GB per release);
   CPU server as PyInstaller onedir to stop unpacking 1.3 GB per launch
   (41–63 s measured on the Dell with 0.8.0, 58–94 s with 0.8.1; onefile
   leaves 1.3 GB in `%TEMP%` per force-killed server). Until then the app
   waits up to 300 s for the server (was 120 s; see below).

### Installing 0.8.1 on the Dell (2026-10-28)

- The Dell is still enrolled in the Log-In MDM (two enrollments, state 1),
  so Defender's ASR rule `01443614` ("block executables unless prevalent")
  blocked the new `voloz.exe` (event 1121). The owner added an ASR-only
  exclusion for `C:\Program Files\Voloz` (antivirus scanning stays on;
  undo: `Remove-MpPreference -AttackSurfaceReductionOnlyExclusions
  'C:\Program Files\Voloz'`, as admin). The MDM policy did not override it.
  Every new build has a new hash, so without the exclusion each release
  would be blocked again.
- The first two server starts after the install unpacked at 5–7 MB/s and
  hit the 120 s startup limit ("Server startup timeout"); a later start took
  155 s from launch to healthy (94 s of it unpacking). Since the next commit
  after `7c841cb` the limit is 300 s and the error no longer points to
  macOS's Console.app; this ships in the next build, not in 0.8.1.
- Owner tested paste into an Explorer folder: works. Alt+F4 on the pill
  can't be reached by hand (the pill never takes focus); a WM_CLOSE sent
  to it left Voloz and the server running.

### Dictation bugs found testing 0.8.1 (2026-10-28)

The owner's mic stayed on for 11:39 with the pill hidden. Reproduced on
the Dell by driving the hotkey with `SendInput`, feeding the pill a TTS
WAV in place of the mic and watching it over WebView2 remote debugging
(`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=…`); the
new frontend was tested in the installed app by serving its build to the
pill through CDP `Fetch`. Fixed (not in a build yet):

- A notice's hide timer ("too short" after a quick tap) was not cleared
  by the next recording: the pill hid mid-recording while the mic ran.
  Reproduced every time on 0.8.1; gone with the fix.
- Hands-free is entered by touching Shift while holding push-to-talk
  (`dictate:restart`, which nothing listened to): release no longer stops,
  with no visible change. The pill now stays visible; recordings stop at
  10 min (`MAX_RECORDING_DURATION_S`, under the ~13 min 25 MB upload limit).
- After a hands-free recording ended by the pill's stop button or the cap,
  keytap kept the toggle active and ignored push-to-talk until the toggle
  chord was pressed again. New `reset_hotkey_state` command rebuilds the
  matcher; the pill calls it when a recording ends without a chord (seen
  over IPC; the Rust side is compiled, not run).
- A paste fired while the user held the chord for the next dictation
  became Win+Ctrl+V (Windows' sound-output flyout) and the text was lost.
  `paste_final_text` now waits for Win/Ctrl/Shift/Alt to be released
  (compiled, not run).
- `useAudioRecording.stopRecording` read React state; a release between
  the mic opening and the re-render was dropped. Now it asks the recorder.
  Not reproduced in 12 timed attempts (30–150 ms), so rare.

Still open: Whisper turns silence into "." / "Gracias." / "...", which is
pasted; after each paste on the Dell the clipboard kept the transcript
(the restore was skipped; cause not looked at).

### Findings from the 2026-10-28 audit

Fixed in 0.8.1 (`5c29190`, `b3ff491`, `c9068b4`): auto-paste into the window that had focus (not the first
window of that process); Alt+F4 on the dictate pill only hides it; Linux
microphone permission on the rebuilt main window and the pill; awaited
stop signal in audio capture; stderr survives a non-cp1252 character;
`init_db()` once per startup; one model load at a time per backend;
pt-BR relative dates and dictation/microphone messages.

Left open on purpose:

- Cancelling a running TTS job does not stop inference: the worker
  thread keeps going and the next queued job starts beside it. Stopping
  it needs an interrupt hook in each engine (Studio only).
- Superseded STT/LLM HTTP clients are not closed on settings changes;
  they are freed by GC. Closing them on change could cut a dictation that
  is still using the old client, so the leak is the lesser risk.
- Backend error texts shown in the pill (e.g. a Claude or Groq error)
  stay in English; they come from the server and the provider.

## Developing on GB4

```bash
. ~/.config/voloz/dev-env.sh   # caches/toolchains on /mnt/data, PATH for uv/bun
cd $VOLOZ_HOME
backend/venv/bin/python -m pytest backend/tests -q   # backend tests
bunx tsc -p app/tsconfig.json --noEmit               # frontend typecheck
(cd tauri/src-tauri && cargo check)                  # Rust
# Rust for Windows (the cfg(windows) code), with the rustup toolchain kept
# apart from the system cargo:
(cd tauri/src-tauri && PATH=/mnt/data/dev/.rustup-bin:$PATH \
  RUSTUP_HOME=/mnt/data/dev/.rustup CARGO_HOME=/mnt/data/dev/.cargo \
  cargo clippy --target x86_64-pc-windows-gnu \
  --target-dir /mnt/data/dev/voloz-wincheck-target)
```

The Windows check needs empty placeholders
`tauri/src-tauri/binaries/voicebox-{server,mcp}-x86_64-pc-windows-gnu.exe`
(git-ignored; already there on GB4). It type-checks and lints; it does not
build an installer.

Release a Windows build: bump the version in the files listed in
`.bumpversion.cfg`, commit on `wisprclone`, then push a `v<version>` tag to
`origin`. The installer lands in a draft release.

Test baseline on GB4 (2026-10-28, `wisprclone` at `c9068b4`): pytest
394 passed / 0 failed / 13 skipped; `tsc` clean; `cargo check` and clippy
clean of new warnings on Linux and Windows (16 pre-existing on Windows).
`biome check` has 8 pre-existing findings in files the fork touched
(import order, formatting, two a11y rules) — none introduced by the fork.
The old `test_hf_progress_tracker` failure was a stale test, fixed here
and upstream (jamiepine/voicebox#1220). On Windows the branch had the same 5–6
environment-only failures as clean upstream (ffmpeg/ROCm/HF cache/offline
guard/progress).

One-time setup already done on GB4: Tauri system packages (apt), `uv` +
Python 3.12 (in `/mnt/data/dev/.uv`), `backend/venv` with CPU PyTorch,
`bun install`, dev sidecar placeholders, `gh` logged in over HTTPS
(account mvoloch) so GB4 can push directly; `mingw-w64` (apt) and a rustup
toolchain 1.93.1 with the `x86_64-pc-windows-gnu` target in
`/mnt/data/dev/.rustup` (proxies in `/mnt/data/dev/.rustup-bin`, not on PATH).
