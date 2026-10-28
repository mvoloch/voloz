# Voloz — project status and handover

Voloz is a public fork of [jamiepine/voicebox](https://github.com/jamiepine/voicebox)
(MIT — keep the upstream credit) maintained at
[mvoloch/voloz](https://github.com/mvoloch/voloz). Goal: Wispr Flow–style
dictation (hotkey → speak → polished text pasted anywhere) without Wispr's
cost or weekly word cap, usable across Windows, Linux and Android, with the
user choosing cloud APIs or local models.

_Last updated: 2026-10-27 (0.8.0). Update this file whenever state or decisions change._

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
3. STT: Groq direct (needs a Groq key) and a vocabulary/prompt setting for
   names and jargon (Whisper `prompt`; OpenRouter ignores it).
4. Streaming STT + incremental refinement to approach Wispr's ~0.7 s.
5. Linux build and test on GB4; then Android.
6. Studio: cloud TTS/cloning engine (ElevenLabs/Cartesia/Fish Audio).
   Known bug: a Qwen3-TTS 1.7B generation on CPU failed with the generic
   "Worker exited without writing terminal status"
   (`backend/services/task_queue.py`); retry and delete also failed — root
   cause not captured yet.

## Developing on GB4

```bash
. ~/.config/voloz/dev-env.sh   # caches/toolchains on /mnt/data, PATH for uv/bun
cd $VOLOZ_HOME
backend/venv/bin/python -m pytest backend/tests -q   # backend tests
bunx tsc -p app/tsconfig.json --noEmit               # frontend typecheck
(cd tauri/src-tauri && cargo check)                  # Rust
```

Release a Windows build: bump the version in the files listed in
`.bumpversion.cfg`, commit on `wisprclone`, then push a `v<version>` tag to
`origin`. The installer lands in a draft release.

Test baseline on GB4 (2026-10-27, `wisprclone` at 0.8.0): pytest
383 passed / 0 failed / 13 skipped; `tsc` clean; `cargo check` clean.
`biome check` has 8 pre-existing findings in files the fork touched
(import order, formatting, two a11y rules) — none introduced by the fork.
The old `test_hf_progress_tracker` failure was a stale test, fixed here
and upstream (jamiepine/voicebox#1220). On Windows the branch had the same 5–6
environment-only failures as clean upstream (ffmpeg/ROCm/HF cache/offline
guard/progress).

One-time setup already done on GB4: Tauri system packages (apt), `uv` +
Python 3.12 (in `/mnt/data/dev/.uv`), `backend/venv` with CPU PyTorch,
`bun install`, dev sidecar placeholders, `gh` logged in over HTTPS
(account mvoloch) so GB4 can push directly.
