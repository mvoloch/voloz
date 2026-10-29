# Voloz — project status and handover

Voloz is a public fork of [jamiepine/voicebox](https://github.com/jamiepine/voicebox)
(MIT — keep the upstream credit) maintained at
[mvoloch/voloz](https://github.com/mvoloch/voloz). Goal: Wispr Flow–style
dictation (hotkey → speak → polished text pasted anywhere) without Wispr's
cost or weekly word cap, usable across Windows, Linux and Android, with the
user choosing cloud APIs or local models.

_Last updated: 2026-10-29 (style per target app, voice commands, text shortcuts, audio retention and lite first-run setup, all merged into `light`). Update this file whenever state or decisions change._

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
- `feat/autostart` — start Voloz at login (cut from `wisprclone` at
  `d5b0621`, not merged): `tauri-plugin-autostart` 2.5 writes the
  `HKCU\…\Run` entry with `--autostart`; such a launch keeps the main
  window hidden (it is now created hidden and shown at the end of setup)
  while its webview starts the server and arms the hotkey. On by default:
  enabled once on the first release-build launch (marker
  `.autostart-default-applied` in the app data dir), never again after the
  user turns it off; an enabled entry is rewritten on each launch so it
  follows the exe between the MSI and NSIS install paths. Switch in
  Settings → General. Type-checked and linted only — **not built or run
  on Windows yet** (to test: the Run value, hidden start with the tray icon
  and a working hotkey, the switch round-trip). The plugin writes the path
  unquoted (`C:\Program Files\Voloz\voloz.exe --autostart`), which
  Windows resolves but is worth checking on the MSI install.
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
2. Lite edition — `feat/lite-edition`, merged into the `light` integration
   branch (2026-10-29) with autostart, the refinement fix and vocabulary.
   First Windows build `lite-v0.8.2` (CI run 40851319053) installed and
   dictating on the owner's PC. New dictation features land on `light`
   first. Still to do: the owner's call on identity and update channel.
3. STT: ~~Groq direct~~ (done 2026-10-28, see Decisions). Vocabulary
   phase 1 is on `feat/vocabulary` (`32f35cb` backend, `31a4dc8` app), merged
   into `light`: Settings → Dictation → Vocabulary holds words/names and
   "heard as → write as" rules. Words go to a remote STT endpoint as the
   Whisper `prompt` and to refinement; rules run on the raw transcript
   right after STT (so they also cover OpenRouter, which ignored the prompt,
   and refinement off). The capture transcript is editable; saving a
   correction offers "Learn: Valois → Voloz?". Local Whisper gets no prompt
   yet (`services/transcribe.py`). Measured on Groq, 30 s Portuguese
   dictation, 3 runs each: no prompt "Valois" 3/3; prompt "Voloz" "Voloz"
   3/3; prompt "Voloz, Log-In, Pascoal" "Voloz" 3/3. On 3 s of silence
   the prompt "Voloz" came back as the transcript 3/3, so a transcript that
   is only the prompt is dropped; other silence hallucinations ("E aí",
   "Legenda por …") remain, as before.
   App context and voice commands are on `feat/app-context` (cut from
   `light` at `a6d149c`, not merged); see the section below.
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
  155 s from launch to healthy (94 s of it unpacking). Since `af3c449` the limit
  is 300 s and the error no longer points to macOS's Console.app; shipped
  in 0.8.2.
- Owner tested paste into an Explorer folder: works. Alt+F4 on the pill
  can't be reached by hand (the pill never takes focus); a WM_CLOSE sent
  to it left Voloz and the server running.

### Dictation bugs found testing 0.8.1 (2026-10-28)

The owner's mic stayed on for 11:39 with the pill hidden. Reproduced on
the Dell by driving the hotkey with `SendInput`, feeding the pill a TTS
WAV in place of the mic and watching it over WebView2 remote debugging
(`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=…`); the
new frontend was tested in the installed app by serving its build to the
pill through CDP `Fetch`. Fixed in `5aa3b5b`, shipped in 0.8.2:

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

Refinement echo (found 2026-10-29, fixed on `fix/refine-single-output`,
not merged): Claude Haiku sometimes returned the transcript followed by a
note about its edits ("

Cleaning up: ..."), which would be pasted.
Measured on one Portuguese sentence with "quer dizer": 3 of 6 calls in one
window, then 3 of 225. Claude now answers inside `<transcript>` tags and
only the tagged text is kept (local models unchanged): 0 of 300 calls on
four sentences. A prompt line forbidding notes made it far worse (139 of
150), so it was dropped.

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

## App context and voice commands (`feat/app-context`, 2026-10-29)

Two dictation features, both in Lite (they live in the captures router).

- **Style per target app.** The focus snapshot taken at chord-start already
  named the app (lowercased exe on Windows, bundle id on macOS); it now also
  carries the window title (Windows) and both go with the capture
  (`target_app`, `target_title` columns, shown as "In outlook" in the
  capture details). `backend/services/app_context.py` holds the table:
  email (Outlook, new Outlook `olk.exe`, Thunderbird, Mail; Gmail/Outlook
  tabs by title) → full sentences, paragraphs when long; chat (WhatsApp,
  Slack, Teams, Telegram, Discord, Signal; their web tabs) → short and
  casual, no final period on a single short sentence; code (VS Code,
  Cursor, Windsurf, Zed, Sublime, Visual Studio, JetBrains, Windows
  Terminal, PowerShell, cmd) → identifiers and casing kept, no prose
  restyling; AI assistants (Claude and ChatGPT desktop, and Claude,
  ChatGPT, Gemini, Perplexity, Copilot tabs) → a prompt: never answered,
  shortened or summarized, paragraphs when long (added 2026-10-29: on the
  owner's 4 longest real dictations, Haiku went from 1 paragraph to 2-7
  with the same words kept and no question answered). Other apps, or the "Adapt style to the app" switch off
  (Settings → Dictation → Refinement, on by default): the prompt is
  byte-for-byte the old one. Both Claude Haiku and Qwen 3.8 27B kept the
  final period on "Beleza, valeu." despite the prompt line (the few-shot
  examples all end in one), so for chat it is dropped in code
  (`finish_for_style`). Measured once per case, 2026-10-29: a long
  three-topic Portuguese dictation got paragraphs for Outlook on both
  models and stayed one block elsewhere; "beleza valeu" → "Beleza, valeu"
  for WhatsApp, "Beleza, valeu." for Outlook/Notepad/VS Code. In VS Code
  both models still end a dictated sentence with a period (left as is: a
  sentence dictated into an editor is usually a comment or a prompt), and
  Qwen left "use state" as two words in every app while Claude wrote
  `useState` in every app — that is the technical-terms rule, not the style.
- **Voice command** (third chord, default left Ctrl+Alt+Win, configurable
  under Settings → Dictation; held like push-to-talk). At chord-start Rust
  reads the selection with UIAutomation TextPattern (`Some("")` = nothing
  selected); when the control has no TextPattern, the pill calls
  `copy_selection` after the chord is released: it waits for the modifiers
  to come up, saves the clipboard, sends Ctrl+C, waits up to 500 ms for the
  clipboard sequence number to move (no move = nothing selected), reads the
  text and restores the clipboard. Terminals are skipped (Ctrl+C there
  interrupts the program). The audio becomes a capture with source
  `command`; `POST /captures/{id}/command {selection}` runs the dedicated
  prompt (`backend/services/voice_commands.py`: rewrite the selection, or
  draft from the instruction when it is empty, keep the selection's
  language, `<result>` tags for Claude) and stores the result as the
  refined text; the pill pastes it over the selection. No LLM configured →
  409 and the pill says so; a failed command pastes nothing.
  Live, 4 instructions × Haiku and Qwen 3.8 27B: "deixa mais formal",
  "traduz para português", "turn this into bullet points" (on a Portuguese
  selection: bullets stayed Portuguese), "responde dizendo que aceito…"
  (no selection: a reply was drafted) — all 8 correct, no notes or
  preambles.

Only compiled, never run: the Rust side (UIA selection, `copy_selection`,
third chord) and the UI. To check on Windows: the chord with Word, Outlook,
WhatsApp, Chrome (Gmail) and VS Code; whether a bare Ctrl+Alt+Win release
opens the Start menu; that the clipboard is unchanged after a command; in
VS Code with nothing selected, Ctrl+C copies the whole line (if UIA gives
no TextPattern there, the command would treat that line as the selection
and paste a rewritten line at the cursor). macOS: no selection reading yet
(commands draft only); Linux has no paste at all yet.

## Lite edition (`feat/lite-edition`, 2026-10-28)

One codebase, two builds. `backend/edition.py` says which edition the
server is: lite when torch is not installed, studio otherwise
(`VOICEBOX_EDITION=lite|studio` forces it).

- **Server:** torch is imported only where it is used; lite serves health,
  captures, settings, transcription, llm and tasks, and skips MCP, CUDA/ROCm
  checks, effect presets and the TTS unload. `build_binary.py --lite` builds
  the same `voicebox-server` from `backend/requirements-lite.txt`.
- **Frontend:** `VITE_EDITION=lite` drops the Studio routes, sidebar group
  and settings tabs and the calls to Studio endpoints.
- **Desktop/CI:** `tauri/src-tauri/tauri.lite.conf.json` is merged with
  `--config`; `.github/workflows/release-lite.yml` builds Windows on
  `lite-v*` tags into its own draft release (`Voloz Lite v…`), so its
  installers don't collide with the studio ones.

Measured on GB4 (Linux, one machine): frozen lite server 157 MB, healthy
in 2.4 s; a real dictation (Groq + Claude Haiku) works end to end with no
torch loaded; unfrozen RSS ~320 MB. Studio tests unchanged (394 passed /
13 skipped). Windows size and startup are not measured yet.

Owner decisions pending (the build works either way):

- **Identity.** The overlay keeps `productName` "Voloz" and the identifier
  `io.github.mvoloch.voloz`, matching the decision that lite *is* "Voloz"
  and the full build becomes "Voloz Studio". Consequence today: installing
  lite replaces the installed studio build (same identifier) and keeps its
  settings, keys and captures. Renaming the full build to "Voloz Studio"
  (new identifier, its own data dir) is what lets both be installed.
- **Updates.** Lite builds make no updater artifacts and point the updater
  at `releases/download/lite-latest/latest.json`, which doesn't exist: a
  lite install must never update to a studio build. A lite update channel
  is still to be designed.
- Both editions use port 17493 and the desktop app reuses a server already
  running there, so running both at once is not supported.
- Known noise: the desktop's speak monitor subscribes to `/events/speak`,
  which lite doesn't serve (it lives with MCP); it retries with backoff
  capped at 30 s, so one log line every 30 s.

## Shortcuts, audio retention, first-run setup (`feat/shortcuts-privacy-onboarding`, 2026-10-29)

Cut from `light` at `a6d149c`, not merged. All three work in lite.

- **Text shortcuts** (Settings → Dictation → Text shortcuts): "meu
  endereço" → a saved, possibly multi-line text. A dictation that is only
  the trigger (case, accents, punctuation and fillers like "é", "hum"
  ignored) pastes the text verbatim and skips refinement. A trigger inside
  a longer dictation is expanded *after* refinement, on the LLM's output,
  so the model never sees the saved text (checked live: a 30 s Portuguese
  dictation refined by Qwen and by Haiku kept a two-line shortcut
  byte-for-byte; since `fix/audit` the trigger itself goes to the model
  as a placeholder, see below). With refinement off it is expanded in the
  raw text. On
  Windows the pill converts line breaks to CRLF before the paste (classic
  edit controls drop a bare LF); the Rust paste code is unchanged.
- **Keep dictation audio** (Settings → Dictation → Storage): Always (the
  default, today's behaviour — the owner decides whether to change it),
  30 days, 7 days, Never. Applied at server startup, after each capture
  and when the setting changes (a shorter choice asks first). Text history
  stays; a capture without audio shows "Audio not kept" and its Export →
  Audio is disabled. The app has no retranscribe button; the API answers
  410 for such a capture.
- **Lite first-run setup**: while neither custom STT nor custom LLM is
  set, lite opens on "Set up Voloz": paste one free Groq key ("Test and
  save" checks it with `GET /models`) and transcription and refinement go
  to Groq (`whisper-large-v3-turbo`, refinement model `GROQ_LLM_MODEL` in
  `backend/services/quick_setup.py`, now `qwen/qwen3.8-27b` — one constant
  because the default is still being measured). Recommended, optional: an
  Anthropic key, which moves refinement to `claude-haiku-5-5` (checked with
  `GET /v1/models/claude-haiku-5-5`, no tokens); STT stays on Groq. "Set up
  manually" opens the settings for that session. Never shown in studio.
- **Rate limits**: a 429 from the LLM no longer surfaces as a 500 with the
  provider's error body. The pill already pasted the raw transcript when
  refinement failed (`3353597`); now it says "Refinement skipped (rate
  limit), raw text pasted". **Groq's free tier also caps tokens per day:
  200 000 for `qwen/qwen3.8-27b`.** One refinement sends ~1.5 k tokens
  (prompt + examples + transcript, estimated), so a Groq-only user gets on
  the order of 100+ refinements a day before every dictation pastes raw.
  On 2026-10-29 the shared key reached 199 048 of 200 000 during these
  tests and the parallel model comparison. That is a reason to recommend
  the Anthropic key, or to pick a Groq model with a larger daily budget.
- **Pre-existing bug fixed** (`0c50d51`): `config.to_storage_path` matched
  the first path component named `data`, so a data dir under `/mnt/data`
  stored unusable capture paths (no playback; retention refused to delete
  them). Installed apps' default data dirs were not affected; rows already
  stored that way stay broken.

## Review fixes (`fix/audit`, 2026-10-29)

Cut from `light` at `043c2fe`, not merged. Bugs found by a code review of
`light`:

- **Shortcut lost when refinement failed.** With a refinement to follow,
  `transcript_raw` keeps triggers unexpanded, and a failed refine (a Groq
  429, say) pasted it: "meu endereço" instead of the address. `POST
  /captures` now also returns `fallback_text` (raw transcript, shortcuts
  expanded; not stored) and the pill pastes that when the refine fails.
- **Shortcut missed when the LLM respelled the trigger.** Triggers were
  matched on the LLM's output, so "meu email" refined to "meu e-mail"
  was never expanded. Each trigger now goes to the model as a placeholder
  (`[[S1]]`, `[[S2]]`…) with one prompt line telling it to copy them
  (added only when there are placeholders; every other prompt is
  byte-identical), and the placeholders become the shortcut texts after
  refinement. A placeholder missing, repeated or renamed in the output is
  not guessed: the unrefined text with shortcuts expanded is stored
  (labelled as a shortcut) and a warning logged. Live, 5 Portuguese
  dictations with 1–2 triggers × 2 runs: Claude Haiku kept 14/14
  placeholders, Groq `openai/gpt-oss-120b` 14/14; no fallback.
- **Voice command in a code editor rewrote the current line.** With
  nothing selected, VS Code, Cursor, Windsurf, Zed, JetBrains IDEs,
  Visual Studio and Sublime copy the whole line on Ctrl+C, so the
  synthetic-copy fallback handed that line to the command, which pasted
  its rewrite at the caret. `NO_SYNTHETIC_COPY` (`main.rs`) now holds
  every Windows exe of the code style in `app_context.py` (a test keeps
  them in step): there the command drafts unless UIAutomation reported a
  selection. Trade-off: a selection UIAutomation can't read in those
  editors is not used. Compiled and linted for Windows, not run.

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
