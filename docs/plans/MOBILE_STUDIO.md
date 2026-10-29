# Voloz Studio on Android and iOS — design

**Status:** proposal, nothing built. **Written:** 2026-10-28 on `docs/mobile-studio`
(cut from `wisprclone` at `d5b0621`). **Scope:** the Studio edition (voice
generation and cloning) on phones. Mobile dictation (floating bubble +
Accessibility Service) is a separate track; section 3 says how the two meet.

Notation: **[C]** = confirmed, with the file:line, command or URL that shows it.
**[I]** = my inference, with what would confirm it. Web facts were checked on
2026-10-28; URLs are in the Sources section.

## Recommendation in short

Build the MVP as a **Tauri v2 Android app that calls Fish Audio directly with
the user's own key**, reusing `app/`'s data layer and components behind a
phone layout and a small TypeScript stand-in for the server that keeps
profiles and history on the phone. Next, add **pairing with the user's
desktop Voloz** for local engines and the desktop library, which first needs
real authentication on the server. On-device cloning waits for a phone
measurement; a hosted Voloz backend is not planned.

## 0. What already exists (prior art worth reusing)

- **Upstream half-built a phone app.** `upstream/feat/mobile-app` (one commit,
  `f4d2150`, 2026-04-25) adds `mobile/`, an **Expo / React Native** iPhone
  companion that pairs with the desktop over LAN or Tailscale, plus backend
  pairing (`routes/pairing.py`, `services/pairing.py`, `utils/auth.py`). It is
  **not merged** into `upstream/main` (`8af7efe`, 2026-10-07) [C, `git
  merge-base --is-ancestor`]. Its V1 excluded what Studio is about: sample
  recording, profile creation, stories, effects [C, `mobile/PLAN.md`].
- **`docs/plans/CLOUD_ROADMAP.md`** (upstream) sketches hosted inference on
  Modal + Cloudflare with consent attestation and TTL for cached profiles [C].
  It is the reference for option (c).
- **The shell is already abstracted.** `app/src/platform/types.ts:73-79`
  defines `Platform = { filesystem, updater, audio, lifecycle, metadata }`,
  implemented in `tauri/src/platform/` and `web/src/platform/` [C]. Mobile is
  a third implementation.
- **Profile export is a portable zip** (`manifest.json` + `samples.json` +
  audio; `backend/services/export_import.py:45,123`) [C]: the MVP's
  desktop-to-phone path without sync.

## 1. Scope: what makes sense on a phone

| Feature | Phone? | Why |
| --- | --- | --- |
| Record a sample and clone a voice | **MVP** | The phone mic is the recorder most users have. The desktop recorder is plain `getUserMedia` + `MediaRecorder` (`app/src/lib/hooks/useAudioRecording.ts:83`) [C]; it ports if the WebView grants the mic. |
| Text → speech; preset voices | **MVP** | The core action; presets carry no consent burden. |
| History: play, favourite, delete | **MVP** | Four calls. |
| Share / export audio | **MVP** | The point of generating on a phone is sending it on. Needs a native share sheet. |
| Import a desktop profile zip | **MVP** | Existing format; avoids sync. |
| Effects | **v1, presets only** | Desktop uses `pedalboard` (C++), 8 effects (`backend/utils/effects.py:25-35`) [C]. All but pitch shift map to Web Audio's `OfflineAudioContext` [I]. |
| Stories (timeline) | **No** | `StoriesTab` is 2,724 lines, a drag-and-drop track editor with 15 endpoints [C]. Wrong product for a 6-inch screen. |
| Models, GPU, MCP, server settings, output channels | **No** | Desktop administration (section 2). |
| Personality (LLM compose) | **Later** | Needs an LLM key; not core. |

**MVP:** clone from a recorded or picked sample behind a consent step,
presets, text → speech, history, share, profile-zip import. Android only.

## 2. The crux: where the engine runs

### What the screens need from the server [C, measured]

`app/src/lib/api/client.ts` has **98 async methods** plus 6 URL builders
(audio, SSE generation status, sample, version, capture audio, migration).
Mapping every `apiClient.<method>` call to the component folder that uses it,
following hooks to their callers (script run on GB4 against this checkout):

- **67** are used by Studio screens. By path: stories 15, profiles 14 (plus
  samples, avatar, import/export), channels 6, history 5 (plus export/import),
  effects 5, generate 4, settings 4, versions 2, models 1.
- **27** are used only by dictation or server administration.
- **4** are never called (`getCapture`, `retranscribeCapture`,
  `listGenerationVersions`, `deleteGenerationVersion`).

The MVP needs **19**: profile create/list/get/update/delete, presets, sample
add/list/delete, transcribe (a sample's `reference_text` is `NOT NULL`,
`backend/database/models.py:59` [C]; on the phone this goes to Groq, the
dictation provider), generate, cancel, status, audio URL, history
list/get/delete/favourite, export audio. All of them end in Python today.

### Today's server is loopback-only by default — and wide open in "remote" mode

The server binds `127.0.0.1` by default (`backend/main.py:18`,
`backend/server.py:291`) [C]. The desktop has a "remote" mode
(`serverStore.mode`, set in `ServerTab/GeneralPage.tsx:37` and
`ServerSettings/ConnectionForm.tsx:37`) that makes Tauri start the server
with `--host 0.0.0.0` (`tauri/src-tauri/src/main.rs:663,678,692,702`) [C].
**In that mode none of the 126 route handlers require authentication**
(`grep '@router.' backend/routes/*.py` = 126; the only `Depends` gate is
`require_cloud_enabled` on `/cloud`) [C]. CORS (`backend/app.py:167-185`) only
restrains browsers (the other middleware, `ClientIdMiddleware`, only tags
MCP clients); any device on the network can read voice samples, spend the
user's provider credits through capture refinement (and through `/generate`
once the cloud engine lands), or `POST /shutdown` (`routes/health.py:35`).
This is a pre-existing upstream flaw, not a base to build on. Stored keys are at least write-only in responses
(`backend/models.py:281-289`) and are dropped when an endpoint moves host
(`services/settings.py:93-106`) [C].

### The four options

| | (a) Phone → provider directly | (b) Phone → user's desktop | (c) Hosted Voloz backend | (d) On-device models |
| --- | --- | --- | --- | --- |
| Latency | One HTTPS hop; provider time only | LAN fast; tunnel adds a hop; desktop CPU engines slow (Qwen3 1.7B failed on CPU, VOLOZ.md step 6) | One extra hop vs (a) | No network; cloning models unmeasured on phones |
| Cost to user | Provider usage: Fish ≈ $0.013–0.021/min [I, below] | Free with local engines; provider cost with cloud engine | Provider cost + our margin | Free after download |
| Cost to owner | None | None | Hosting, billing, abuse handling, support | None |
| Privacy | Text + voice sample go to the provider | Stays on user's machines (local engines) | Goes to us and to the provider | Stays on phone |
| Works offline / desktop off | No / yes | Needs desktop on and reachable | No / yes | Yes / yes |
| Build effort | Medium: port ~19 endpoints' logic to TS | Medium: server auth + pairing (upstream has a draft) | High: accounts, payments, secrets, ops | High: native inference, per-model work |
| Maintenance | Track provider API changes | Keep phone and desktop versions compatible | Running a service | Model updates, device matrix |

**(a) Direct to the provider.** Fish Audio's TTS is `POST https://api.fish.audio/v1/tts`
with a bearer key; it takes either a stored voice (`reference_id`) or inline
`references` (audio + transcript, MessagePack body only) for zero-shot cloning,
and returns mp3/opus/wav [C, Fish docs]. A stored voice is `POST /model`
(multipart, `visibility` defaults to `private`) [C]. Price: $15 per million
UTF-8 bytes; Fish's own rule of thumb is 1 M bytes ≈ 12 h of speech, i.e.
$0.021/min; at 150 words/min and ~6 bytes/word it is ~$0.013/min [C for the
rate; I for the per-minute range]. ElevenLabs: Instant Voice Cloning is
`POST /v1/voices/add` (name + files → `voice_id`) [C]; price about $0.10 per
1,000 characters for Multilingual v2 and $0.05 for Flash, from secondary
sources [C-secondary], roughly 4–7× Fish per minute [I]. Port work: a
TypeScript class with the same method names as `ApiClient` for the 19 MVP
calls, backed by SQLite (`tauri-plugin-sql`, mobile-supported) and private
files, calling providers through `tauri-plugin-http` so WebView CORS does not
apply [I: provider CORS not checked]. The desktop cloud engine being added
on `feat/cloud-tts` (no commits yet when checked) is the spec to mirror —
same provider voice IDs and request shape — so a profile works on both.

**(b) Desktop over LAN/tunnel.** Reuses the `app/` data layer unchanged (it
reads `serverUrl` from a store, `client.ts:77`) and every desktop engine. It
first needs: per-device bearer tokens from a pairing flow only loopback can
start (upstream's `require_loopback` / `require_bearer_or_loopback`), TLS or
an encrypted tunnel (upstream pinned a certificate fingerprint in the QR;
Tailscale is the cheap transport), revocation in Settings, and binding beyond
loopback only while a device is paired. Weakness: the desktop must be on and
reachable, which for a laptop is the exception [I].

**(c) Hosted Voloz backend.** The owner would run accounts, payments, the
provider secret, rate limits and abuse handling, and would become the AI
*provider* under the EU AI Act [I]. Upstream's roadmap still requires SOC 2
and a pen test before cloud-cached profiles go public [C]. Wrong first step
for a one-person fork with no revenue plan.

**(d) On-device.** Preset voices are realistic; cloning is unproven.
sherpa-onnx v2.0.1 (2026-10-26) runs Piper/VITS, Matcha, Kokoro, KittenTTS,
PocketTTS and ZipVoice on Android/iOS [C]. One community benchmark (VoicePing,
2026-02-15, English) measured real-time factor on a Galaxy S10: Piper 0.08,
Matcha 0.135, Kitten Nano 0.39, Kokoro 1.13, i.e. slower than real time
[C-secondary]. Kokoro ONNX is 326 MB fp32, ~90 MB quantised [C]. Cloning
models: NeuTTS Air (Q4 GGUF 527 MB; the only phone figure is 20 tokens/s for
its language model alone on a Galaxy A25) [C]; Kyutai Pocket TTS (100 M
params, ~1× real time on a Pixel 8a GPU in a community port) [C-secondary];
Qwen3-TTS 0.6B (2.5 GB; ~630 MB Q4 GGML port) has **no phone measurement**
[C]. None of the desktop engines has a measured phone path.

### Choice

**MVP: (a)**, Fish Audio by default with ElevenLabs as the second provider
behind the same interface. It is the only option that works with the desktop
off, costs the owner nothing to run, and has a bounded port (19 calls, three
tables). **v1: add (b)** as "Connect to my Voloz" once the server has pairing
auth; then the phone also sees desktop profiles and local engines.
**Later: (d)** for offline preset voices (Kokoro or Matcha via sherpa-onnx)
once measured on the owner's phone; on-device cloning only when a model
clears real time on a mid-range phone in our own test. **(c) not planned.**

## 3. App shell

### What in `app/` is desktop-only [C, measured on this checkout]

`app/src` is 30,018 lines of TS/TSX in 179 files (i18n excluded). By folder:

| Share | Lines | What |
| --- | --- | --- |
| Studio screens | 10,446 (35%) | VoiceProfiles 2,883, StoriesTab 2,724, Generation 1,280, History 931, AudioPlayer 711, EffectsTab 638, VoicesTab 624, Effects 497, MainEditor 158 |
| Shared data + infra | 10,863 (36%) | `lib/api` 3,323 (incl. generated client), `lib/hooks` 2,981, `components/ui` 1,719, stores, utils, router, App |
| Desktop-only | 7,228 (24%) | ServerTab 3,346, ServerSettings 2,241, AudioTab 675, DictateWindow, CapturePill, ChordPicker, Accessibility/InputMonitoring gates |
| Captures (dictation history) | 1,473 (5%) | Lite-edition feature |

Direct Tauri use is confined: only **6 files** in `app/src` import
`@tauri-apps/*`, all dictation (DictateWindow, CapturesTab, two permission
gates, `useCaptureRecordingSession`, `useChordSync`) [C]. Studio screens reach
the shell only through `platform.*`. Plugins in use: dialog, fs, shell,
updater, process, single-instance; the last three are already excluded from
Android/iOS (`tauri/src-tauri/Cargo.toml:52`) [C]. The Rust crate (5,894
lines) is almost all desktop: hotkeys, clipboard and synthetic keys, focus
capture, system-audio capture, tray, server sidecar. It has no mobile entry
point, and `hotkey_monitor.rs:33` uses `keytap`, which Cargo only pulls on
desktop [C], so it would not compile for Android as is [I, not tried].

**Reuse estimate [I]:** the data layer (api, hooks, stores, ~7k lines) and
`components/ui` reuse as-is; Studio components need a phone layout, since the
UI is desktop-first (38 responsive breakpoint classes in 16 of 95 TSX files
[C]). Roughly 50% of the frontend reused unchanged, 20% adapted, the rest
left behind. The Rust side is a new, small crate.

### Options

| | Tauri v2 mobile | React Native (Expo) | Native (Kotlin / Swift) |
| --- | --- | --- | --- |
| Reuse of `app/` | Data layer + components | Types and logic only; all UI rewritten (upstream's `mobile/` is ~2,000 new lines for 3 tabs + pairing) | None |
| Platform glue | Kotlin/Swift plugins (`@TauriPlugin`) | Expo modules | Native |
| Mic | WebView `getUserMedia`: works on Android with manifest permission per a maintainer, but a 2026 community guide needed a custom `WebChromeClient.onPermissionRequest` [C-secondary]; WKWebView has known prompt quirks | `expo-audio`, solid | Solid |
| Share sheet / keystore | Community plugins only (`tauri-plugin-sharekit`, `@impierce/tauri-plugin-keystore`), or our own ~100-line plugins | `expo-sharing`, `expo-secure-store` | Native |
| Team fit | Same stack as desktop (Tauri 2.12.5, 2026-10-21 [C]) | New stack | Two new stacks |

**Choice: Tauri v2 mobile**, as a **new small Tauri crate**
(`mobile/src-tauri`) with the mobile plugins (sql, http, dialog, store,
deep-link, plus share and keystore), loading the same `app/` with a `mobile`
build flag that swaps in a phone layout — the same flag mechanism the Lite
edition needs (VOLOZ.md step 2). Do not gate the desktop crate for mobile.
**Fallback:** if the mic spike fails, switch to Expo starting from upstream's
`mobile/` (it has recording, waveform and a typed client).

### Studio and Android dictation: one app or two

Android dictation needs native Kotlin in any shell: an AccessibilityService
to insert text, an overlay for the bubble, a microphone foreground service.
Android 14+ refuses to start a microphone foreground service from the
background, and an overlay is not an exemption, so recording must start
from a user tap on a visible window or notification [C, Android docs]. Wispr
Flow on Android uses Accessibility + overlay [C, Wispr docs]. Community Tauri
plugins already ship both an AccessibilityService and a foreground service
[C], so Kotlin plugins inside a Tauri app work.

Play judges Accessibility use per app: allowed for non-accessibility apps
with a prominent disclosure, affirmative consent and a Console declaration
with a video [C]. Adding that to the app that also carries cloning risk
doubles the review surface. **Recommendation:** one repo and frontend,
**two Android packages** — *Voloz* (dictation, with Accessibility) and
*Voloz Studio* (no Accessibility service) — mirroring the desktop's "two
editions, one codebase".

## 4. Keys and data

**Keys.** The desktop keeps provider keys as plain text in SQLite
(`backend/database/models.py:228,234`) [C] — a flaw tolerable on a personal
desktop, not on a phone that gets backed up. On the phone the
Fish/ElevenLabs/Groq keys go in the **Android Keystore** (a Keystore AES key
encrypting them) and the **iOS Keychain**, never in `localStorage`,
`tauri-plugin-store` or SQLite. Tauri's official option is weak: Stronghold's
docs list mobile, the plugin repo marks mobile "?", and a community reply says
it goes away in v3 (not confirmed officially) [C]. **Recommendation:** an own
two-command plugin (`get`/`set`, ~100 lines Kotlin + Swift), or
`@impierce/tauri-plugin-keystore` after reading its code; exclude secrets from
Android auto-backup.

**Data.** SQLite with the three MVP tables mirroring the desktop columns
(`profiles`, `profile_samples`, `generations`, `models.py:26-90`) so profile
zips import without mapping; audio in app-private storage. With Fish inline
`references` the sample is sent per request and not stored by the provider;
a Fish `POST /model` or an ElevenLabs clone stores a voice at the provider
(private by default on Fish) [C]. See open decision 2.

**Sync: not in the MVP.** Zip import plus share/export cover it. Real sync
comes with (b), the desktop being the library and the phone a client.

## 5. Store and legal policy for a cloning app

| Source | Rule that matters here |
| --- | --- |
| Google Play, AI-Generated Content policy | Explicitly covers apps that make AI voice recordings of real people; bans using them for scams, non-consensual sexual deepfakes, harassment, deceptive election content [C]. Google announced in 2023 that users must be able to report offensive AI output in-app; the current policy page no longer shows that text [C-secondary; build it anyway]. |
| Google Play, Deceptive Behavior → Manipulated Media | Bans apps that create or promote misleading synthetic audio; disclaimers or watermarks, satire and creative use are exceptions [C]. No Play rule names voice-cloning consent [C]. |
| Google Play, Accessibility API + foreground services | See section 3; each foreground-service type is declared with a video [C]. |
| App Store Review Guidelines (revised 2026-10-16) | 1.2: "AI content creation apps" need filtering, reporting, blocking and contact info. 5.1.2(i) (added 2025-11-13): sharing personal data with third-party AI needs disclosure and explicit permission — a voice sample sent to Fish is that. 5.1.1(ii): the microphone purpose string must say what the recording is for [C]. |
| iOS keyboards | Keyboard extensions cannot use the microphone, even with full access; Wispr jumps to the main app to record [C]. Matters for iOS dictation, not Studio. |
| EU AI Act Art. 50 (applies from 2026-08-02) | 50(2): providers of systems generating synthetic audio must mark output as AI-generated in a machine-readable way; 50(4): whoever publishes a deepfake must disclose it [C]. |

`RESPONSIBLE_USE.md` states the rules (own voice or explicit permission;
disclose synthetic audio), but the app never shows or records an
acknowledgement: `grep -ri responsib` over `app/src` and `backend` finds
nothing [C]. **The phone MVP needs:** a consent step before each clone
("my voice" / "I have the speaker's permission", timestamped on the
profile); a report action on generations; a provider data-sharing disclosure
with opt-in on first use (Apple 5.1.2(i)); AI-generated marking on shared
files (metadata tag plus "AI voice" in the share text). Whether Fish or
ElevenLabs already watermark output was not checked. The desktop should get
the consent step too.

## 6. Milestones

| Milestone | Contents | Done when |
| --- | --- | --- |
| **M0 — spike** (read-only answer first) | Throwaway Tauri Android app: `getUserMedia` record 10 s, call Fish `/v1/tts` through `tauri-plugin-http` with inline references, play the result, open a share sheet | On the owner's Android phone: a 10 s recording becomes a cloned 20-word clip that plays and shares to WhatsApp. If the mic fails, decide Expo here. |
| **M1 — MVP (Android, sideloaded)** | `mobile/src-tauri` crate; phone layout; TS local client for the 19 calls; SQLite; keystore plugin; Fish + ElevenLabs; consent, report, disclosure; profile zip import; signed APK in a GitHub draft release | A user with only a Fish key installs the APK, records a sample, accepts consent, generates, finds it in history after an app restart, shares it; the key is not readable from an `adb backup` / app data dump; a desktop profile zip imports and generates. |
| **M2 — v1** | Server pairing auth (port upstream `utils/auth.py` + pairing, loopback-only init, revocation); "Connect to my Voloz" over LAN/Tailscale; effects presets via Web Audio; Play Store internal track | With the desktop in remote mode, an unpaired `curl` to any Studio route gets 401; the paired phone lists desktop profiles and generates with a local engine; revoking the device cuts it off within one request; Play pre-launch report clean. |
| **M3 — later** | iOS build; offline preset voices via sherpa-onnx (Kokoro or Matcha) after measuring RTF on the owner's phone; on-device cloning only if a model measures < 1.0 RTF on a mid-range phone | Each with its own measured gate. |

## Open decisions for the owner

1. **Provider default.** Fish Audio (≈$0.013–0.021/min) or ElevenLabs
   (≈4–7× the price, often rated higher). *Recommend Fish by default, ElevenLabs
   selectable.*
2. **How a cloned voice lives at the provider.** Inline reference audio on every
   request (nothing stored at the provider; ~0.5 MB extra upload per request) or
   a stored private provider voice (smaller requests; the provider keeps a
   voice model). *Recommend: whatever the desktop cloud engine does, so profiles
   move between devices; my preference is inline for privacy.*
3. **Shell.** *Recommend Tauri v2 mobile, decided by the M0 mic spike; Expo
   from upstream's `mobile/` is the fallback.*
4. **One Android app or two.** *Recommend two packages from one repo (Voloz
   dictation with Accessibility; Voloz Studio without).*
5. **Distribution.** Sideloaded APK from GitHub releases first, or Play from
   the start (developer account, policy declarations, consent/report flows
   reviewed). *Recommend APK first, Play at v1.*
6. **iOS.** Needs a Mac with Xcode to build and an Apple developer account;
   there is no Mac in the current setup (GB4 Linux, the Dell Windows) [I from
   VOLOZ.md "Where things are"]. *Recommend deferring until Android v1.*
7. **Desktop pairing (option b) and the existing "remote" toggle.** *Recommend
   pairing auth in v1, and meanwhile warn in the UI (or remove the toggle) that
   remote mode exposes every route unauthenticated.*

**Least certain here:** that Tauri's Android WebView grants the mic
reliably (M0 settles it); the Fish per-minute range (rate confirmed,
conversion mine); the "~50% unchanged" reuse share (a judgement, not a build).

## Sources (web, checked 2026-10-28)

- Fish Audio TTS: https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech (undated)
- Fish Audio create model: https://docs.fish.audio/api-reference/endpoint/model/create-model (undated)
- Fish Audio pricing: https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits (undated)
- ElevenLabs IVC: https://elevenlabs.io/docs/api-reference/voices/ivc/create (undated); prices from https://developer.puter.com/tutorials/elevenlabs-api-pricing/ (Jun 2026, secondary)
- Tauri releases: https://github.com/tauri-apps/tauri/releases ; plugin support table: https://github.com/tauri-apps/plugins-workspace ; mobile plugin development: https://v2.tauri.app/develop/plugins/develop-mobile/ (updated 2026-05-14); Stronghold: https://v2.tauri.app/plugin/stronghold/ and https://github.com/orgs/tauri-apps/discussions/7846
- Android WebView mic in Tauri: https://github.com/orgs/tauri-apps/discussions/12732
- Community plugins: https://docs.rs/crate/tauri-plugin-sharekit/0.1.4 , https://www.pkgstats.com/pkg:@impierce/tauri-plugin-keystore , https://docs.rs/crate/tauri-plugin-android-accessibility/0.2.2 , https://docs.rs/crate/tauri-plugin-background-service/0.5.3
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx/releases , https://k2-fsa.github.io/sherpa/onnx/tts/index.html ; phone RTF: https://voiceping-landingpage-prod.pages.dev/en/blog/research-offline-tts-eval/ (2026-02-15)
- Kokoro ONNX sizes: https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX ; NeuTTS: https://github.com/neuphonic/neuTTS ; Pocket TTS: https://github.com/kyutai-labs/pocket-tts ; Qwen3-TTS GGUF: https://huggingface.co/CC-TM/Qwen3-TTS-GGUF
- Play AI-generated content: https://support.google.com/googleplay/android-developer/answer/14094294 ; Deceptive behavior: https://support.google.com/googleplay/android-developer/answer/9888077 ; Accessibility API: https://support.google.com/googleplay/android-developer/answer/10964491 ; foreground services: https://support.google.com/googleplay/android-developer/answer/13392821 ; 2023 reporting rule: https://9to5google.com/2023/10/25/google-play-gen-ai-policy/
- Android FGS: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Wispr Flow Android setup: https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings
- App Store guidelines: https://developer.apple.com/app-store/review/guidelines/ ; keyboard mic: https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard
- EU AI Act Art. 50: https://artificialintelligenceact.eu/article/50/
