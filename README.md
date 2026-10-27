<h1 align="center">Voloz</h1>

<p align="center">
  <strong>Speak anywhere. Get polished text in any app.</strong><br/>
  Wispr Flow–style dictation with cloud APIs or local models — your choice —
  plus a full voice studio.
</p>

<p align="center">
  A fork of <a href="https://github.com/jamiepine/voicebox">Voicebox</a> by
  <a href="https://github.com/jamiepine">Jamie Pine</a>, the open-source AI
  voice studio. All the voice-studio credit is theirs; Voloz adds a
  dictation-first focus on top.
</p>

---

## What Voloz is

Hold a hotkey, speak, release: Voloz transcribes what you said, cleans it up
(removes "uh", self-corrections and filler, fixes punctuation) and pastes it
into whatever app has focus. It is meant as a free, open alternative to Wispr
Flow without a weekly word cap — you bring your own API keys, or run models
locally.

Two editions are planned from this one codebase:

| Edition | For | Status |
| --- | --- | --- |
| **Voloz** | Dictation only, Wispr-style: hotkey, pill, captures history, settings. Small installer. | In progress |
| **Voloz Studio** | Everything Voicebox does — voice cloning, text-to-speech, stories, effects — plus Voloz dictation. | Current builds |

Both minimise to the system tray, and in both you choose, per step, between a
cloud API and a local model.

## What Voloz adds to Voicebox

- **Cloud transcription.** Point captures at any OpenAI-compatible
  `/audio/transcriptions` endpoint — OpenRouter, Groq, OpenAI or a self-hosted
  Whisper — instead of running Whisper on your machine.
- **Claude refinement.** Set the custom LLM endpoint to
  `https://api.anthropic.com/v1` and Voloz uses the native Anthropic SDK
  (`claude-haiku-5-5` recommended) for cleanup.
- **Tray and single instance.** Closing the window sends Voloz to the tray
  (the hotkey keeps working); launching it again focuses the running copy.
- **Portuguese** in the transcription language picker, and pt-BR strings for
  the new settings.

### Why cloud by default

Measured with 24 s of Portuguese speech on a laptop without a dedicated GPU:

| Path | Time until text is ready |
| --- | --- |
| Local Whisper Turbo + local Qwen3 refinement (CPU) | 15–31 s |
| OpenRouter `whisper-large-v3-turbo` + Claude Haiku | 2.6–6.8 s (median 3.2 s) |

On a machine with a capable GPU, local models are a good choice — the option
stays available.

## Getting started

Voloz is early. Windows installers are published as releases on this
repository; Linux and Android builds are next, then macOS and iOS.

1. Install from [Releases](https://github.com/mvoloch/voloz/releases). The
   installer is not code-signed yet, so Windows SmartScreen will warn
   ("More info" → "Run anyway").
2. Open **Settings → Captures**:
   - **Transcription → Custom transcription endpoint**: e.g.
     `https://openrouter.ai/api/v1` and `openai/whisper-large-v3-turbo`, plus
     your API key.
   - **Refinement → Custom LLM endpoint**: `https://api.anthropic.com/v1` and
     `claude-haiku-5-5`, plus your Anthropic API key.
   - Pick your language, then turn on the **global shortcut** under
     Dictation.
3. Hold the shortcut (default on Windows: right Ctrl + right Shift), speak,
   release.

API keys are stored only in Voloz's local database on your machine and are
never sent anywhere except the endpoint you configured. The settings API never
returns them.

`scripts/cloud-dictation/` has PowerShell helpers to set the keys and
configure the endpoints from a terminal, and a latency test script.

## Developing

Project status, decisions, measurements and the roadmap live in
[VOLOZ.md](VOLOZ.md). Voicebox's developer docs in [`docs/`](docs/) still apply
to the shared codebase (backend in Python/FastAPI, app in Tauri + React).

Contributions are welcome — issues and pull requests on this repository.
Improvements that make sense for Voicebox itself are offered upstream too.

## Credits and license

Voloz is built on [Voicebox](https://github.com/jamiepine/voicebox) by
[Jamie Pine](https://github.com/jamiepine) and its contributors — if you like
the voice studio, consider [supporting Jamie](https://buymeacoffee.com/jamiepine).

MIT licensed, like Voicebox. See [LICENSE](LICENSE).

---

**Em português:** o Voloz é um fork do Voicebox focado em ditado — segure um
atalho, fale e receba o texto pronto em qualquer app, usando APIs na nuvem ou
modelos locais. Mantido por [Marcus Voloch](https://github.com/mvoloch).
