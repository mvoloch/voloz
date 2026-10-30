# Voloz for Android (prototype)

Wispr Flow-style dictation: a floating bubble appears over the focused text
field in any app; tap it (or hold it) and speak, and the cleaned-up text is
inserted at the cursor. Speech goes straight from the phone to Groq with the
user's own API keys (no Voloz server). The look and the dictation fixes
follow the desktop Voloz Lite 0.8.13 (see VOLOZ.md, "Lite look and desktop
fixes").

Status: first prototype. It compiles, its pure parts are unit-tested and its
Groq pipeline was run end to end from a JVM. It has run on an API 34
emulator (screens compared with the mockups, bubble states, recording path),
but **nothing has been run on a phone yet**. See "Not verified" below.

## Build

```sh
source /mnt/data/dev/android/env.sh   # JAVA_HOME (Temurin 17), ANDROID_HOME, PATH
cd android
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest           # unit tests (the Groq e2e test is skipped)

# Groq end-to-end test (real network, uses the app's own GroqClient/DictationPipeline):
GROQ_API_KEY=... VOLOZ_E2E_WAV=/path/to/16k-mono.wav VOLOZ_E2E_OUT=/tmp/e2e.json \
  ./gradlew testDebugUnitTest --tests '*GroqEndToEndTest*'
```

Toolchain: AGP 8.7.3, Gradle 8.11.1 (wrapper), Kotlin 2.0.21, Compose BOM
2024.12.01, compileSdk/targetSdk 35, minSdk 29.

## Install (sideload)

1. Copy the APK to the phone and open it; allow "install unknown apps" for the
   file manager/browser when asked.
2. Open Voloz and follow the three steps: Groq key, microphone, Accessibility.
3. Android 13+: sideloaded apps get **"Restricted setting"** when you try to
   enable their accessibility service. Fix: Settings › Apps › Voloz › ⋮ (top
   right) › **Allow restricted settings**, then enable Voloz in Accessibility
   again. The in-app step 3 explains this and has a button to App info.

## How it works

| Piece | File |
|---|---|
| Focus tracking, bubble visibility, text insertion | `service/VolozAccessibilityService.kt` |
| Bubble window, gestures, snap/remember position, hands-free tooltip | `overlay/BubbleOverlay.kt` |
| Bubble drawing (all states) and its tokens | `overlay/BubbleView.kt`, `overlay/BubbleStyle.kt` |
| State machine (tap / hold / process / insert / errors) | `dictation/DictationController.kt` |
| 16 kHz mono PCM16 capture, 10 min cap | `audio/Recorder.kt` |
| WAV header, RMS level | `audio/WavWriter.kt` |
| FLAC for the upload (pure Kotlin) | `audio/FlacEncoder.kt` |
| Microphone foreground service + notification | `service/RecordingService.kt` |
| Providers, presets, key/host check | `net/Providers.kt` |
| HTTP calls (OpenAI-compatible and Anthropic), warm-up | `net/ApiClient.kt` |
| Backup providers with 429 cooldown | `net/Fallback.kt` |
| Audio -> text over the providers | `net/DictationPipeline.kt` |
| Desktop refinement prompt (verbatim port) | `refine/RefinementPromptText.kt` (generated), `refine/RefinementPrompt.kt` |
| Insert-at-cursor merge, field eligibility | `text/TextMerge.kt`, `text/FieldPolicy.kt` |
| Settings (DataStore; saved keys per host, encrypted with Android Keystore AES-GCM; providers; theme) | `data/SettingsRepository.kt`, `data/KeyCipher.kt` |
| History (last 30, on device only) | `data/HistoryRepository.kt` |
| Screens (stateless composables) | `ui/screens/*.kt` (providers list and key dialog: `ProvidersSection.kt`); wiring/navigation in `ui/VolozRoot.kt` |
| Theme (Lite tokens light/dark, type, shapes) | `ui/theme/Theme.kt` |
| Strings (pt-BR default) | `res/values/strings.xml` |

### Dictation flow

1. The accessibility service watches focus/window events (not content changes).
   The bubble shows when an editable field that is **not** a password, PIN,
   number, phone or date field is focused **and** the keyboard window is up
   ("Só em campos de texto", default on). With that setting off the bubble is
   always on screen, except over password-like fields. During a dictation the
   bubble never disappears.
2. Tap = hands-free (tap again to stop; a "Hands Free" tooltip shows). Press and
   hold ≥ 350 ms = push-to-talk (release stops). Moving the finger before the
   hold fires drags the bubble; on release it snaps to the nearest side edge and
   the side + height are remembered; until then it sits at the right edge
   just above the focused field (just below it when there is no room above),
   clear of the status bar and the keyboard, so it never covers the field's
   own buttons. Idle bubble is 60% opaque
   (`BubbleStyle.IDLE_ALPHA`), fully opaque from touch-down and in every
   active state.
3. Audio: `AudioRecord`, `VOICE_RECOGNITION` source, 16 kHz mono PCM16, kept in
   memory, capped at 10 minutes (auto-stops). Takes under 0.5 s are discarded
   ("Curto demais").
4. Upload as 16 kHz mono FLAC (WAV if it would not shrink). Transcription
   `POST /audio/transcriptions` on the main STT provider (default Groq
   `whisper-large-v3`), `response_format=json`, `temperature=0`, language
   auto, vocabulary as `prompt` (desktop `build_stt_prompt`: whole terms,
   ≤ 400 chars); on a provider error the next provider in the list gets
   the same request (`net/Fallback.kt`; a 429 parks a provider for its
   `Retry-After`, default 60 s). The main endpoints' connections are warmed
   with `HEAD /models` when recording starts.
5. Refinement (on by default), same fallback: default Groq
   `POST /chat/completions`, `openai/gpt-oss-120b`, `reasoning_effort=low`,
   `temperature=0.2`, `max_tokens=2048` (OpenAI API: `max_completion_tokens`
   and `reasoning_effort: "none"`; Claude: Messages API with tagged output).
   System prompt = desktop `build_refinement_prompt` with all
   flags on (+ `_SPELLING`, `_VOCABULARY` when there is vocabulary) + "Write the
   output in the same language as the transcript. Never translate."; the 7
   few-shot `REFINEMENT_EXAMPLES` go as real chat turns. The desktop's
   `collapse_repetitive_artifacts` pre-pass is ported too. If refinement fails,
   the raw transcript is inserted.
6. Insertion (see below). History keeps the result.

The prompt text is generated, not hand-copied: `tools/gen_prompt.py`
extracts the strings from the desktop's `refinement.py` with `ast`, and the
unit tests compare the Kotlin builder against fixtures produced by the
desktop's own Python functions (`app/src/test/resources/refinement/`).
Regenerate after the desktop prompt changes:

```sh
python3 -I tools/gen_prompt.py ../backend/services/refinement.py   app/src/main/java/io/github/mvoloch/voloz/android/refine/RefinementPromptText.kt   app/src/test/resources/refinement
```

## Decisions

**Overlay type: `TYPE_ACCESSIBILITY_OVERLAY`** from the accessibility service.
No "Display over other apps" (SYSTEM_ALERT_WINDOW) permission is needed, and
the service already has to be enabled for insertion. The window is
`FLAG_NOT_FOCUSABLE`, so touching the bubble never steals focus from the field
being dictated into. SYSTEM_ALERT_WINDOW is not requested at all.

**Recording: in the accessibility-service process, with a microphone
foreground service alongside.** Android 11+ blocks microphone access for apps
in the background ("while-in-use" restriction; on Android 14+ a foreground
service of type `microphone` must also be started while the app is eligible).
A bound accessibility service is expected to count as user-visible/eligible
(system bindings of accessibility services carry the foreground capabilities),
so capture starts immediately in-process on tap. At the same time
`RecordingService` (type `microphone`) is started: it shows the
"Voloz está gravando" notification the design calls for and is the fallback if
the platform does mute the in-process capture. A muted mic delivers exact
digital zeros; if the first 700 ms are all zeros and the foreground service is
running, capture restarts under it. If both fail, the user sees
"O microfone só entregou silêncio…". `startForeground` failures
(`ForegroundServiceStartNotAllowedException`/`SecurityException`) are caught,
and the service is never stopped before `startForeground` ran (that would
crash the app). **Unverified on a device** — which path actually captures on
Android 14/15 is the first thing to check (logcat tag `VolozDictation`:
"silent capture; restarting under the foreground service" means the fallback
was needed).

**Insertion: `ACTION_SET_TEXT` merged at the cursor, then `ACTION_PASTE`.**
`ACTION_SET_TEXT` replaces the whole field, so the new value is the current
text with the dictation inserted at the selection (`TextMerge`, spacing fixed
at both seams; a selection is replaced like typing over it), then
`ACTION_SET_SELECTION` puts the cursor after it. That path is only taken when
the field's text and selection are known (hint text is treated as empty);
otherwise, or if `SET_TEXT` is refused, it falls back to clipboard +
`ACTION_PASTE`, which the app inserts at its own cursor. The previous clip is
restored 800 ms later when Android lets us read it (Android 10+ usually hides
the clipboard from background apps, so often it can't be restored). If both
fail (WhatsApp, Telegram and some WebViews are known to refuse), the text is
left on the clipboard, the bubble shows the warning for ~2 s and then a paste
glyph for 6 s: tapping it runs `ACTION_PASTE` again. Existing text is never
replaced except a selection the user made.

**Settings:** DataStore Preferences; every API key is kept per host and
encrypted with a non-exportable Android Keystore AES-256-GCM key (no
deprecated `security-crypto` dependency); a key whose prefix names another
provider is refused. `allowBackup=false` (Keystore keys don't migrate
anyway).

**Battery:** Home shows a "Bateria sem restrição" chip
(`PowerManager.isIgnoringBatteryOptimizations`); tapping it when amber opens
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission declared; fine for
sideload, would need justification on Play).

**Fonts:** as the desktop Lite: Bricolage Grotesque (titles), Onest (text)
and JetBrains Mono (keys, times, model names), variable TTFs in `res/font`
(OFL, licences in `fonts-licenses`). Bricolage and Onest are the desktop's
Latin WOFF2 files (`app/src/assets/fonts`) converted with fontTools
(`TTFont(src); flavor = None; save(dst)`); JetBrains Mono is google/fonts'
variable TTF (`ui/theme/Theme.kt`, `VolozFonts`).

## Not verified (needs a real phone)

Seen on the owner's phone (2026-10-30): setup through Accessibility and
real dictations into Voloz's own test field. One unexplained "Nada foi
ouvido" on a second take (see VOLOZ.md); the history now records the
take's length, peak level and Groq's reply when that happens. Still open:

- Whether in-process capture from the accessibility service works on the
  owner's Android version, or the foreground-service fallback kicks in.
- `ACTION_SET_TEXT` / `ACTION_PASTE` behaviour per app (Gmail, WhatsApp,
  Chrome, Telegram…), cursor placement after SET_TEXT.
- IME-visibility detection (`TYPE_INPUT_METHOD` window) on the owner's keyboard.
- Bubble gestures, snapping and rotation by hand (placement and the tooltip
  position were checked on the emulator).
- "Restricted setting" flow wording on the owner's Android skin.
- The screens on a real phone. They were rendered on an API 34 emulator
  (1080x2340) and compared side by side with the mockups; line breaks may
  differ a little on the phone (system Roboto vs Roboto Flex).
