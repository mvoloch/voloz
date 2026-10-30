# Voloz — project status and handover

Voloz is a public fork of [jamiepine/voicebox](https://github.com/jamiepine/voicebox)
(MIT — keep the upstream credit) maintained at
[mvoloch/voloz](https://github.com/mvoloch/voloz). Goal: Wispr Flow–style
dictation (hotkey → speak → polished text pasted anywhere) without Wispr's
cost or weekly word cap, usable across Windows, Linux and Android, with the
user choosing cloud APIs or local models.

_Last updated: 2026-10-30 (`light`: the OpenAI API works as the refinement endpoint, without reasoning; released in Lite 0.8.10). Update this file whenever state or decisions change._

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
  `backend/services/quick_setup.py`, now `openai/gpt-oss-120b` (was
  `qwen/qwen3.8-27b` until `fix/audit`, see below) — one constant).
  Recommended, optional: an
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
  For `openai/gpt-oss-120b`, the default since `fix/audit`, Groq's
  response headers say 8 000 tokens per minute and 1 000 requests per
  day; two refinements measured 1 245 and 1 714 tokens (about 1 170 of
  prompt, the rest mostly reasoning), so about five refinements a
  minute. Its daily token cap is not in the headers and was not checked.
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
- **Quick setup asked Groq for a model it would not use.** The Groq key
  check required both the transcription and the Groq refinement model
  even when an Anthropic key moves refinement to Claude. Now the
  refinement model is required only when Groq refines.
- **A transcription rate limit lost the dictation with a 500.** A 429
  from the STT endpoint was a plain error: `POST /captures` answered 500
  with the provider's body. It is now `STTRateLimitError`, the route
  answers 429 with a short message and the pill says "Transcription
  failed (rate limit), try again in a minute" (en, pt-BR). **The audio is
  still lost:** when STT fails, `create_capture` deletes the file it had
  just written (no row would point at it), so nothing can be
  retranscribed later. A recovery path (keep the file, row without a
  transcript, retry) is not built.
- **Dictating only a vocabulary word returned nothing.** A transcript
  equal to the Whisper prompt was always dropped (Groq echoes the prompt
  on silence), so saying just "Voloz" pasted nothing. The echo is now
  dropped only when the audio has no speech: `utils/audio.has_speech`
  needs 150 ms of 20 ms frames at or above -40 dBFS RMS (the frame test
  the TTS trimming already uses), so a click doesn't count. Checked on
  synthetic audio only; the level of a real silent dictation (the webview
  records with auto gain and noise suppression on) was not measured.
- **Free refinement default is now `openai/gpt-oss-120b`** (was
  `qwen/qwen3.8-27b`; `GROQ_LLM_MODEL` in `quick_setup.py`). Measured
  2026-10-29 on the owner's 27 real dictations × 2 runs: gpt-oss-120b cut
  or translated none, median 1.29 s, but writes more formally (34
  colloquial words kept vs 53 for Haiku); qwen3.8-27b took 0.55 s but
  rewrote one dictation's meaning in 2 of 2 runs and translated a noisy
  one. The setup screen's copy now says the free model is more formal
  (not that it rewrites or translates) and "about five refinements a
  minute" (was six). Existing installs keep the model they stored.

## Stuck recording (`fix/stuck-recording`, 2026-10-29)

Cut from `light` at `ac00c66` (Lite 0.8.3), not merged. On the owner's PC
(0.8.2) the pill stayed on "Recording" with the timer running and no
chord, click or right-click stopped it; only Quit from the tray did. No
capture reached the server. The trigger is not confirmed (there were no
logs of chord events or recorder state); the code path that makes it
unrecoverable is.

- **The recorder can always end.** When the MediaRecorder stopped by
  itself (its track ended: device removed, Bluetooth profile switch, a
  Windows audio change; or a recorder error) nothing reset `isRecording`
  and the timer kept ticking; every later stop, and the 10 min cap, only
  acted on a recorder that was not `inactive`, so they were all no-ops,
  and `DictateWindow` ignores `dictate:start` while recording. Now
  `recorderLifecycle.ts` ends a recording once for any cause (stop
  request, track `ended`, recorder `stop` or `error` event), leaves the
  recording state at once, and still delivers what was recorded — on the
  recorder's `stop` event, or 3 s later with what was collected if that
  event never comes. A stop request is never a no-op. A stop held while
  the microphone is opening gives up on that start after 5 s. WAV
  conversion has a 10 s limit (the WebM is sent instead), and
  `convertToWav` closes its AudioContext when decoding fails (it leaked
  one per failure). The pill says "The microphone stopped; what was
  recorded was sent" when it ended by itself. Tests:
  `bun test app/tests` (Bun's runner; the app has no JS test runner).
- **Stop always works, and shows.** The pill's stop control was the 8 px
  dot; it is now a 24 px button with a stop square ■ (aria-label, tooltip
  "Stop recording (Esc)") after the timer, pulled into the right padding.
  Esc stops a recording dictation and transcribes it: a second raw
  `keytap::Tap` in `hotkey_monitor.rs` (`EscapeWatcher`) watches for
  Esc key-down while the pill reports a recording
  (`set_dictation_recording`), and sends `dictate:stop` with
  `{source: "escape"}`. Not an Esc chord in the `ChordMatcher`: while
  hands-free is on the matcher suppresses every other chord, which is
  exactly when Esc is needed. The tap only observes, so Esc still reaches
  the focused app. The pill resets the hotkey state after an Esc stop
  (the hands-free toggle would otherwise stay on in keytap).
- **Dictation log.** `<app data>/logs/dictation.log` (next to
  `watchdog.log`; `%APPDATA%\io.github.mvoloch.voloz\logs` on Windows),
  moved to `dictation.log.1` at 1 MB. `[rust]` lines: chord
  start/stop/restart with the chord action, Esc stops, and each
  `dictate:start`/`dictate:stop` sent. `[dictate]`/`[main]` lines (from the
  webview through the `dictation_log` command): microphone opened (device
  label), recorder state changes, track ended/muted, recorder errors, stop
  requests and what they did, upload/refine results with sizes and HTTP
  status, paste result. No transcript text, keys or window titles. Read
  it first the next time a dictation misbehaves.
- **Orphan server after Quit (Windows).** The Windows server is a
  PyInstaller onefile exe: the process the app spawns is the bootloader,
  which unpacks to `%TEMP%\_MEI…`, runs the real server as its child,
  waits for it and then deletes the folder. Quitting only reached the
  child (`/shutdown` and the parent-PID watchdog both run inside it);
  nothing on Windows ever ended the bootloader (Unix kills the process
  group), so one that did not exit by itself was left behind. Why it did
  not exit on the owner's PC is not confirmed — most likely still
  removing its unpacked folder (files held by Defender) or blocked on
  it; it needs a look at the live process next time. Fix
  (`server_job.rs`): the spawned server goes into a job object with
  kill-on-close (and breakaway allowed), so it ends when the app process
  ends, crash included; a normal quit first waits up to 5 s for it to
  exit by itself (so it can clean `%TEMP%`) and writes a line to
  `dictation.log` if it had to be ended. "Keep server running" lifts
  kill-on-close before exiting. Compiled for Windows only, not run.
  Checked on the owner's PC with 0.8.4 (2026-10-29): Quit from the tray
  left no process, but the server's `%TEMP%\_MEI…` folder (0.24 GB, all
  735 files) stayed — the bootloader was ended before it cleaned up, and
  older crashes had left 7.2 GB of such folders. 0.8.5
  (`prepare_server_temp_dir` in `main.rs`): the sidecar runs with
  TMP/TEMP set to `%LOCALAPPDATA%\io.github.mvoloch.voloz\server-temp`,
  emptied before each start (only once no server is on the port, so a
  "Keep server running" server is never touched). The user's TEMP is
  never cleaned.
- **Hands-free shows on the pill.** A second "stuck" recording was
  hands-free entered by accident (Shift brushed while holding Ctrl+Win;
  pressing Ctrl+Shift+Win again stopped it): the pill looked the same in
  both modes. Rust now says the mode: `dictate:start` carries
  `hands_free` (started by the toggle chord) and `dictate:restart` (the
  push-to-talk → hands-free upgrade, which the pill used to ignore)
  carries it too. The pill reads "Hands Free" (in every language, the
  owner's call: the name of the mode, like a brand term) instead of
  "Recording", timer and stop button unchanged; the switch is logged.
- **The pill's bars are a level meter while recording.** They used to
  animate whatever the microphone heard. `PillLevelBars` reads the
  recording's own MediaStream through a Web Audio `AnalyserNode` (RMS per
  animation frame, linear in dB from -55 dBFS — below it the bars stay
  flat — to -15 dBFS, fast rise and gentle fall) and writes the heights
  straight to the DOM. Its AudioContext is closed when the recording
  ends. Same five bars and size; the other states (transcribing, agent
  speech, the settings preview) keep their animation. The pill had no
  reduced-motion handling, so none was added. Mapping tested in
  `app/tests/vuMeter.test.ts`. Not seen on a real microphone yet; the
  knobs are `NOISE_FLOOR_DB` / `FULL_SCALE_DB` in
  `app/src/components/CapturePill/vuMeter.ts`.
- **The pill is in English, with standard symbols.** Owner's call: the
  pill reads like a phone's status pill in every app language. Its
  strings are read through `pillT` (`i18n.getFixedT('en')`,
  `app/src/i18n/index.ts`), so the `captures.pill` entries of the other
  locales are no longer used by it (left in place). Microphone errors
  (`common.microphone.*`, shared with the voice-profile forms) and server
  error details stay as they were. Symbols (lucide-react, already
  shipped): recording and Hands Free a gently pulsing red dot; stop a ■
  button after the timer; transcribing/refining a spinner (`Loader2`);
  done a check; agent speech a speaker (`Volume2`); errors and notices a
  warning triangle (`TriangleAlert`, was a circled "!"). The level-meter
  bars stay next to the timer. The pill is about 34 px wider while
  recording (14 px symbol slot instead of the 8 px dot, plus the stop
  button); height, shape and colours unchanged.

## Lighter Lite (`feat/lite-086`, 2026-10-29)

Why: on the owner's PC Lite 0.8.5 used 1,073 MB 12 min after start
(server 694 MB, WebView2 355 MB in 9 processes, the largest renderer
159 MB), and the first dictation after start took 11.9 s because the
warm-up was still importing librosa.

- **Server without librosa.** `utils/audio.load_audio` decodes with
  soundfile and resamples with soxr at `soxr_hq` — librosa.load's own
  steps, same samples bit for bit (checked on `valois.wav` and a 48 kHz
  stereo copy, at 16/24/44.1 kHz and native). Formats soundfile can't
  read go through librosa when it is installed (studio); without it
  (lite) `UnsupportedAudioError`, and `/captures` and `/transcribe`
  answer 415. A WAV capture's duration comes from its header.
  `requirements-lite.txt` has soxr and no librosa/numba; the lite build
  excludes librosa, numba, llvmlite, scipy, sklearn, joblib, audioread,
  resampy, pooch, msgpack; CI fails if librosa is importable.
- **Imported files become WAV in the app (lite).** Upload decodes the
  file in the webview (`convertToWav(file, { monoSampleRate: 16000 })`)
  and sends 16 kHz mono WAV; a format the webview can't decode shows
  "Couldn't read this audio format". Studio unchanged.
- **No main window at login (lite).** `tauri.lite.conf.json` repeats the
  main window with `"create": false` (merge-patch replaces the array, so
  keep it in step with `tauri.conf.json`). `show_main_window` builds it
  for a normal launch, the tray and a second launch; a launch at login
  leaves it unbuilt. The dictate window then does what the main window's
  shell did for dictation (`BootWithoutMainWindow`): keep-running flag to
  Rust, `start_server`, and once settings and readiness load, one
  `enable_hotkey`/`disable_hotkey` (`hotkeyCommand`, shared with
  `useChordSync`). `start_server` is single-flight (`start_lock`), so the
  main window opened during that start waits and gets the running server.
  Closing to the tray already destroyed the window (`0b6ecc7`). Left in
  the main window on purpose: update check (lite has no channel), server
  log view, captures-list sync events, the macOS accessibility prompt
  (`system:accessibility-missing` has no listener while the window is
  closed, as before). Studio still creates the window hidden at login.
- **Clipboard after a paste.** The restore used to be skipped whenever
  the clipboard sequence number moved, so a clipboard monitor
  (QuickTextPaste, Plaud, Grammarly) re-writing the dictation left it on
  the clipboard. Now it is restored when the clipboard still holds
  exactly the text Voloz wrote (`clipboard::restore_decision`) and kept
  only when it holds something else; each outcome goes to
  `dictation.log` with the process that owns the clipboard (no
  contents). Voloz's writes (the staged text and the restored clipboard)
  carry `ExcludeClipboardContentFromMonitorProcessing`,
  `CanIncludeInClipboardHistory` = 0 and `CanUploadToCloudClipboard` = 0.
  Clippy-checked only; which monitor rewrote it on the owner's PC is for
  the log to tell.

Measured on GB4 (Linux, frozen lite server, 3 launches each; a 2 s
48 kHz dictation posted as soon as `/health` answered):

| | 0.8.5 | now |
| --- | --- | --- |
| Binary | 158 MB | 63 MB |
| Unpacked (`_MEI`) | 390 MB | 141 MB |
| Launch to healthy | 2.2 s | 1.3–1.9 s |
| RSS after warm-up (bootloader + server) | 320–339 MB | 150 MB |
| First capture, local fake STT | 6.9 / 2.0 / 1.9 s | 0.77 / 0.08 / 0.10 s |
| First capture, Groq | 2.26–2.32 s | 0.43–0.52 s |
| Second capture, Groq | 0.34–0.39 s | 0.32–0.40 s |

The release lite app run under Xvfb with `--autostart`: one WebKit
renderer (175 MB RSS), the server started by the dictate window and the
hotkey armed (`dictation.log`); a second launch built the main window (a
second renderer, 224 MB) on the same server. A normal launch builds
both windows and the dictate window doesn't boot. Windows (WebView2
memory, MSI/NSIS, the first dictation after login on the Dell) is not
measured yet.

## Lost release, lost first words (`feat/lite-087`, 2026-10-29)

From the owner's `dictation.log` (Lite 0.8.6, Windows): one push-to-talk
dictation logged `chord start` and never `chord stop`, and ran 39 s until
the pill's stop button (the first dictation after closing the main window
to the tray); and after a Bluetooth headset was turned off the time from
`chord start` to the recorder's `onstart` went from 0.2–0.4 s to 0.74–2.7 s,
so the first words were not recorded.

- **A chord release can't be lost.** Cause not proven (no log of hotkey
  changes then). What the code shows: every time the main window opened,
  `useChordSync` tore the matcher down and rebuilt it — `disable_hotkey`
  when capture settings loaded before readiness (`canRecord` false until
  then), then `enable_hotkey` with the same chords, and `enable_hotkey`
  always rebuilt. A matcher rebuilt while a chord is held never saw the
  press and ignores the release. A key-up the low-level hook missed
  (Windows skips a hook that answers late under load) would look the
  same in the log. Now: `useChordSync` waits for readiness; Rust keeps
  the matcher when the chords sent are the ones armed; a rebuild while a
  chord is in progress ends that dictation with `dictate:stop`
  (`source: rebuild` — keytap can't be told which keys are already held,
  so carrying the chord over would mean tracking releases outside
  keytap); and on Windows, while a push-to-talk or voice-command chord is
  in progress, a thread reads its keys with `GetAsyncKeyState` every
  100 ms and, if none has been down for 300 ms and no release came,
  sends `dictate:stop` (`source: safety`) and logs `[rust] safety stop:
  chord keys released but no release event`. It ends with the chord, on a
  hands-free upgrade (no safety stop in hands-free), or when the
  recording stops another way; nothing runs between dictations. A
  non-chord stop makes the pill reset keytap (`reset_hotkey_state`), which
  also clears keys a missed key-up left "held". The log now has every
  spawn/update/reset of the chords with its caller and the chord in
  progress (`[rust] hotkey: update_bindings (from enable_hotkey, chord
  active=PushToTalk): …`), and `[rust] main window created/destroyed`.
  Compiled and clippy-checked for Windows; the polling and the release
  timing are not run on Windows yet. Knobs: `RELEASE_POLL` /
  `RELEASE_GRACE` in `hotkey_monitor.rs`.
- **The first words are kept.** The recorder was MediaRecorder (WebM/Opus)
  decoded to WAV after the stop; speech before its `onstart` was lost, and
  `onstart` took 0.58–1.48 s on the owner's PC after the headset change
  (on top of 0.1–1.24 s of `getUserMedia`). Now `micCapture.ts` takes PCM
  straight from the stream: one AudioContext at the microphone's rate
  (`sinkId: none` where Chromium supports it, so no speaker is opened),
  source → AudioWorklet tap posting mono blocks of 2048 samples, and
  source → the pill's level analyser (no second AudioContext). A
  recording keeps every block from the first one the stream delivers; on
  stop the tap hands over its partial block. The WAV is mono 16 kHz,
  resampled by the same `convertToWav(…, { monoSampleRate })` imported
  files use; if that fails or takes 10 s, the device-rate WAV is sent (the
  server resamples). Before, the dictation WAV was at the decode
  context's rate (usually 48 kHz) and the server resampled it, so the STT
  still gets 16 kHz mono but the upload is a third of the size.
  `recorderLifecycle.ts` is unchanged: stop always ends a recording, a
  track ending ends it and delivers what was recorded, the 10 min cap and
  "too short" as before. Voice-profile recordings (studio) use the same
  path at the device rate, closed right after. No AudioWorklet in the
  webview → `ScriptProcessorNode` (logged).
- **Warm microphone.** The dictate pill keeps the stream and graph open
  `MIC_WARM_MS` (3 min, `micCapture.ts`) after a dictation, feeding a
  ring of the last `PREROLL_MS` (300 ms). A chord inside that window
  starts recording at once and begins 300 ms before the press. After
  3 min idle the stream closes (the Windows microphone indicator goes
  off); it closes at once when its track ends or on `devicechange` (during
  a recording: when that recording ends), so a warm stream never records
  from a device that stopped being the default. The main window's
  Captures tab doesn't keep it warm. Log lines: `microphone open: <device>
  (<rate> Hz, audio graph <state>)`, `first sample at +N ms from the start
  (cold mic)` / `at -300 ms (warm mic)`, `mic warm: kept open`, `mic warm:
  reused (pre-roll N ms)`, `mic warm: closed after 3 min idle` /
  `(audio devices changed)` / `(microphone ended)`.

Measured on GB4 in headless Chrome 156 with a fake microphone playing a
dictation (`v87-harness`; not WebView2, not Windows, not a real device):
first sample 13–20 ms after the start cold (old path on the same fake
device: `getUserMedia` 2–125 ms then `onstart` 51–54 ms, all lost), 0 ms warm
with 300 ms of pre-roll; a 4 s take held 3.99 s of samples and a 3 s warm
one 3.29 s (pre-roll included); a press while the previous take was still
flushing reused the microphone; a short warm window closed by itself. The
browser's 16 kHz conversion vs soxr HQ (the server's resampler) on the
same captured 48 kHz samples: 74.5 dB apart below 4 kHz (99.5 % of the
energy), the differences in 4–8 kHz (likely a sub-sample timing offset
between the two; the test clip has nothing above 8 kHz, so aliasing isn't
exercised). `pcmToWav` writes the same bytes as the old decode-to-WAV
writer for the same samples (`app/tests/pcm.test.ts`). CPU while warm and
idle, all Chrome processes, Core Ultra 7 155H: about 6–6.5 % of one core
(stream alone with echo cancellation/noise suppression/AGC ~2.8 %, the
graph about as much again; 0.15–0.25 % with the microphone closed). Not
measured on Windows; expect the same order there, for 3 min after each
dictation. Cheaper option if it matters: suspend the graph while idle
(about half the cost, no pre-roll). Untested on Windows: everything in
this item.

## Paste that can't reach its window (`light`, 2026-10-30)

Seen on the Dell with 0.8.7 (2026-10-30, 11:45 and 12:28 local): two
dictations transcribed and refined, then `paste failed` 7–11 ms after
`refine ok`, with no clipboard line; the owner copied the text from the
app. A paste that works takes ~600 ms (120 ms settle + 400 ms consume), so
these failed before touching the clipboard: returning to the window the
dictation started in (`focus_capture::activate`). On Windows that fails
fast when `SetForegroundWindow` is refused (another window came to the
front during the dictation and the foreground lock keeps a background
process from taking it back) or the window is gone. The text then only
reached the captures list. The log had neither the app nor the reason, so
which of the two it was is unknown.

Fix (not released, untested on Windows): when the window can't be brought
back (or the modifier wait gives up), `paste_final_text` leaves the text
on the clipboard and returns `copied`; the pill says "Not pasted. The text
is copied: Ctrl+V" for 6 s, and a click on it copies the text again (the
error pill otherwise copies its own message). Not done during a new
recording. The user's clipboard is not restored in that case, by design.
Log lines: `[rust] paste: could not return to <exe> (in front: <exe>):
<reason>; text left on the clipboard`, then `paste failed: text left on
the clipboard`; other failures now log their message (`paste failed:
<message>`), and a paste skipped for Voloz's own window says so instead of
`paste ok`. Only exe names are logged, never window titles or text.

Gate on GB4: `tsc` clean; Windows clippy the same 18 warning lines as
before; Linux `cargo check` clean; biome the same findings as before in
the three files touched.

## OpenAI as the refinement model (`light`, 2026-10-30)

Benchmark on 12 of the owner's dictations (2–59 s, 3 runs each, the app's
own prompt and examples; script and report kept outside the repo since
they hold dictation text). Refine, median per call: Claude Haiku 5.5
2.03 s, `gpt-6-luna` 1.68 s, `gpt-5.6-luna` 1.68 s (both with
`reasoning_effort: "none"`); `gpt-6-luna` with `low` 1.71 s but up to
6.2 s and rewrote more. On 13–30 s dictations Haiku took 2.0–3.0 s and
luna 1.3–1.9 s. Quality about even: Haiku stays closer to the words
(but once turned "dois erres" into "dois erros"); luna punctuates and
drops fillers more, and once dropped a clause. The app's real refine
time on the Dell with Haiku (dictation.log, 55 dictations, upload ok →
refine ok): median 2.25 s, p90 3.66 s.

Transcription in the same run: Groq whisper-large-v3 0.52 s after
release (1.14 s at 59 s of audio); `gpt-transcribe` 1.31 s (4.08 s at
59 s) with better names ("Claude", "Voloz") and fewer dropped words;
`gpt-live-transcribe` 0.59 s (`minimal`) / 0.65 s (`low`), flat with
length, but `minimal` garbled the first words of 5 of 12 clips (other
scripts and languages). Decision: keep Groq for transcription; Realtime
is not worth its price for dictation.

What the OpenAI API needed (checked against it on 2026-10-30): its
GPT-5/6 models reject `max_tokens` ("use max_completion_tokens"), and
without `reasoning_effort` they reason at medium effort. With
`reasoning_effort: "none"` they accept `temperature` 0.2. `gpt-5-mini`
only goes down to `minimal`; `gpt-4.1-mini` refuses the field.
`OpenAICompatLLMBackend` now, for `api.openai.com` only, sends
`max_completion_tokens` and `reasoning_effort: "none"`, and drops the
latter for that backend after a 400 that names it. Other servers get
the same request as before. Known gap, not fixed: `gpt-5` / `gpt-5-mini`
still fail (after the retry they reason at default effort and refuse
`temperature` 0.2).

To use it: refinement endpoint `https://api.openai.com/v1`, model
`gpt-6-luna`, the user's own OpenAI key. Gate on GB4: pytest 557 → 561
passed (+4 new), 13 skipped, 0 failed; ruff: 1 new UP045 on
`is_openai_endpoint` (`Optional`, matching the file), the rest as before.

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
