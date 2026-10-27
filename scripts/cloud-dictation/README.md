# Cloud dictation helpers (Windows)

Helpers used while bringing up cloud dictation (OpenRouter STT + Claude
refinement) on a CPU-only Windows machine.

| Script | What it does |
| --- | --- |
| `set-keys.ps1` | Prompts (masked) for `OPENROUTER_API_KEY` and `ANTHROPIC_API_KEY` and stores them as user environment variables. Never prints the keys. |
| `configure-voicebox.ps1` | With the app running, points capture settings at the cloud endpoints through the local API (`PUT /settings/captures`), using the keys above, and prints the readiness check. |
| `spike_latencia.py` | Records from the mic (or reads a `.wav`) and times OpenRouter transcription + Claude Haiku refinement outside the app. Needs `anthropic`, `httpx`, `sounddevice`. |

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\cloud-dictation\set-keys.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\cloud-dictation\configure-voicebox.ps1
```
