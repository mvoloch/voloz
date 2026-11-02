# Voloz for Android: research

Date: 2026-10-29. Read-only web research plus AOSP source reads. Tags: **[confirmed]** = read in an
official doc or AOSP source (cited); **[reported]** = third-party article; **[inferred]** = my
reasoning, not verified; **[unconfirmed]** = looked for it, could not verify.

## 1. How Wispr Flow for Android behaves

Launched 2026-02-23 (early access, free/unlimited for a while). It is **not a keyboard** on Android
(unlike iOS): it runs next to Gboard/Samsung Keyboard as a floating "Flow Bubble". Requires
Android 13+. [confirmed: Wispr docs "Android System Requirements"; reported: 9to5Google, HotHardware]

**When the bubble appears** [confirmed: Wispr troubleshooting + first-dictation articles]
- Only when you focus a normal text field in another app *and the on-screen keyboard is open*
  (also with an external keyboard). Not on the home screen, not in Flow's own app.
- Sits at a screen edge; default is the **right edge**. Can be dragged; dragging it onto a
  bottom target snoozes it for 10 min (shake twice to un-snooze).
- Hidden in: password / PIN / numeric / phone-number fields, credit-card fields, password
  managers, and selected banking/payment apps (136 per a search snippet [reported]). Browser address bars and notification
  replies are allowed exceptions. Gray bubble = offline.

**Gestures and states** [confirmed: "Starting Your First Dictation on Android"]
- **Tap mode**: tap once to start, speak, tap the **checkmark (Done)** to stop and transcribe.
  An **X (Cancel)** is available while recording and while transcribing.
- **Hold mode** (push-to-talk): press and hold, release to stop and insert. No Done button;
  Cancel appears ~3 s into transcription. You can drag the bubble while holding.
- Both gestures appear to be available at once (no mode switch documented) [inferred].
- Session cap ~5 min, with a warning 1 min before; auto-submits at the cap.
- Status icons: warning triangle = accessibility revoked; retry icon = service not responding;
  spinner = reconnecting. Error states on the bubble: **Paste** (insertion failed, text is on the
  clipboard), **Retry** (transcription failed, audio saved).
- Live waveform while recording [reported: search snippet only].

**Text insertion** [confirmed: Wispr "Reliable text insertion in messaging apps"]
- Through the Accessibility service ("needs Accessibility permission to insert text into other
  apps"). Mechanism (SET_TEXT vs PASTE) is not documented [unconfirmed].
- Does **not** replace existing text; inserts at the cursor, or appends at the end when the
  cursor position cannot be determined.
- Fallback: copies the transcript to the clipboard (marked sensitive so Android hides the preview),
  shows **Paste**/Copy on the bubble for ~5 s, posts a notification, keeps it in history.
- Admitted failure modes: some chat apps (WhatsApp, Telegram, ...) do not report field text back,
  so Flow assumes success and no fallback appears; some apps report failure although text landed
  (risk of duplicate paste); keyboard still animating; field not focused. "Insertion reliability
  differs by app."

**Onboarding order** [confirmed: "Android Download & Installation Guide"]
1. Microphone (granted during a practice dictation in the tutorial).
2. **Display over other apps** (Settings page, "Allow Wispr Flow to appear over other apps").
3. **Accessibility** ("Use Wispr Flow" service ON; the separate accessibility *shortcut* must be OFF).
4. Privacy choice (share data for training or not).
5. **Battery optimization exemption** ("Unrestricted"), skippable. On Xiaomi/Redmi/POCO: Autostart
   first, then Battery "No restrictions". Realme/Oppo/Vivo/Huawei/OnePlus/Transsion: two-screen
   background + battery flow. Samsung: accessibility entry is under "Installed apps".
6. Notifications (optional, status alerts only).
No keyboard/IME step. Overlay and Accessibility cannot be skipped.

**Settings** [confirmed]: bubble size (0.7x-1.15x, default 0.85x), shrink when idle (off by
default), shrink to a dot, shrink in search fields, opacity, bubble on/off; privacy, history, retry.

**Known complaints / limits**
- Battery managers kill the accessibility service; the bubble silently disappears. Some OEMs drop
  the service after force-stop/restart even while Settings shows it ON (fix: toggle it off/on).
  [confirmed: Wispr troubleshooting]
- Google Play periodically nags users to review apps holding accessibility access. [confirmed]
- Work profiles may block accessibility; 6 GB+ RAM recommended; internet required. Recordings use
  ~1.9 MB/min [confirmed], which is exactly 16 kHz 16-bit mono WAV [format inferred].
- Reviewer: the bubble sometimes lingers after it should hide. [reported]
- Reddit could not be fetched (blocked for this tool). YouTube descriptions and the Play Store
  listing (description, permissions, screenshots) did not load. [unconfirmed]

**Screenshot URLs** (9to5Google article):
- https://9to5google.com/wp-content/uploads/sites/4/2026/02/wispr-flow-android-1.png
- https://9to5google.com/wp-content/uploads/sites/4/2026/02/wispr-flow-android-2.png
- https://9to5google.com/wp-content/uploads/sites/4/2026/02/wispr-flow-android-3.png

## 2. Technical options (Android 14/15/16)

### 2.1 Overlay bubble

- `TYPE_APPLICATION_OVERLAY`: any app, needs `SYSTEM_ALERT_WINDOW` ("Display over other apps"),
  which is itself a restricted setting for sideloaded apps on Android 15+ (CDD 9.8).
- `TYPE_ACCESSIBILITY_OVERLAY`: only from a connected AccessibilityService (its window token).

- AOSP `PhoneWindowManager.checkAddPermission()` (main branch): `TYPE_ACCESSIBILITY_OVERLAY` is
  **not** a system-alert-window type; it returns `ADD_OKAY` and lets WindowManager validate the
  token (newer builds add an app-op `OP_CREATE_ACCESSIBILITY_OVERLAY` behind a flag). So no
  SYSTEM_ALERT_WINDOW is needed [confirmed: AOSP source]; open-source apps do this [reported].
- **Recommendation: accessibility overlay.** We need the accessibility service anyway for
  insertion, so it saves one permission screen and one more restricted setting. Wispr also asks for
  "Display over other apps"; why is not documented [unconfirmed]. Plausible reasons: the
  background-FGS-start exemption for apps with a visible overlay (Android 15+ requires the
  overlay to be visible), or keeping the bubble when accessibility is temporarily down [inferred].

### 2.2 Detecting the focused field and inserting text (AccessibilityService)

- Show the bubble when `findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` returns a node with
  `isEditable && !isPassword`, **and** `getWindows()` contains a window of
  `AccessibilityWindowInfo.TYPE_INPUT_METHOD` (keyboard visible). That matches Wispr's rule.
  [API names: standard; the exact rule is my design, inferred]
- Insert, in order of preference:
  1. **Merge + `ACTION_SET_TEXT`**: read `node.text`, `textSelectionStart/End`, splice the
     transcript in, set the whole text, then `ACTION_SET_SELECTION` to put the caret after the
     insertion. Docs: SET_TEXT replaces the whole text and moves the cursor to the end.
     [confirmed: AccessibilityNodeInfo docs]. Pitfalls: when the field is empty some apps report the
     *hint* as text (check `isShowingHintText`, API 26+) [inferred]; it also bypasses the app's
     IME/InputConnection, so chat apps that watch keystrokes may not register the change (Wispr's
     WhatsApp/Telegram warnings are consistent with this) [inferred].
  2. **Clipboard + `ACTION_PASTE`**: `setPrimaryClip` (with `ClipDescription.EXTRA_IS_SENSITIVE`,
     API 33+), then `ACTION_PASTE` on the focused node; pastes at the current selection [confirmed:
     docs]. Usually better for WebViews/Chrome/rich editors [inferred, needs device test]. Cost: it
     overwrites the user's clipboard, and a background app cannot read the clipboard on Android 10+
     to restore it [inferred].
  3. **Fallback**: leave text on the clipboard + show "Paste" on the bubble (Wispr's design).
- Verify after insert: re-read the node text; if it is unreadable (some chat apps), do not
  re-paste (duplicate risk).
- Compose `TextField` SetText/Paste support [inferred]; Chrome/WebView SET_TEXT [unconfirmed]:
  test both. Custom input surfaces (terminals, games) fail [reported: phone-whisper README].

### 2.3 Recording audio from the overlay

- [confirmed: FGS docs] A `microphone` FGS needs `FOREGROUND_SERVICE_MICROPHONE` + granted
  `RECORD_AUDIO` and **cannot be created while the app is in the background**, even with a
  background-start exemption (e.g. SYSTEM_ALERT_WINDOW). While-in-use exemptions cover
  notifications, widgets, system-started services, VoiceInteractionService; **not** overlays. So
  an overlay-only app cannot rely on a bubble tap to open the mic [inferred from the docs].
- The accessibility service path: AOSP `AccessibilityServiceConnection.bindLocked()` binds every
  accessibility service with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE |
  BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES` [confirmed: AOSP main source].
  `BIND_INCLUDE_CAPABILITIES` passes the binder's (system_server's) while-in-use capabilities to the
  bound process, so **recording with `AudioRecord` directly inside the AccessibilityService, with no
  FGS, should get real (non-silent) audio** [inferred from source; spike #1 must prove it on
  Samsung + Pixel]. The status-bar mic privacy indicator will show while recording (expected).
- Fallback if spike #1 fails: start a `microphone` FGS from the tap handler (the a11y process is
  bound "foreground while awake", so this may count as foreground [unconfirmed]), or keep a mic FGS
  started while our activity is visible (works, but costs battery and needs a persistent notification).
- Recorder: **`AudioRecord` 16 kHz mono PCM16 + WAV header** (also gives RMS for a waveform and
  silence detection). Groq: downsamples to 16 kHz mono anyway, WAV for lowest latency, accepts
  wav/m4a/mp3/flac/ogg/webm, 25 MB free-tier limit (~13 min of 16 kHz WAV), 10 s billing minimum
  [confirmed: Groq docs]. AAC/m4a via `MediaRecorder` is smaller for slow data; defer [inferred].

### 2.4 Alternative: custom keyboard (InputMethodService) with a mic key

- Pros: `InputConnection.commitText()` is the normal typing path, so it lands at the cursor in
  practically every field (WebView, Compose, chat apps) [inferred]. No Accessibility: no restricted
  setting (IMEs are not on the CDD 9.8 list), no Play declaration, no Advanced Protection risk. Mic
  works while visible: AOSP binds a visible IME with `BIND_INCLUDE_CAPABILITIES` [confirmed:
  `InputMethodBindingController`]; the current IME is also an FGS-start exemption [confirmed].
- Cons: the user must switch keyboards: either a full keyboard (big job; DictateKeyboard forked
  FlorisBoard) or a voice-only IME reached via the globe key that returns with
  `switchToPreviousInputMethod()` [inferred pattern]. Not Wispr's bubble-over-any-keyboard UX,
  and users distrust third-party keyboards (they see every keystroke).
- Prior art [READMEs only, code not reviewed]: IME: DictateKeyboard (FlorisBoard fork, BYO Groq
  key). Bubble + a11y: zackify/flo, kafkasl/phone-whisper, AfzalH/voice-android (WAV, a11y insert
  + clipboard fallback, foreground mic service).

### 2.5 Policy and sideloading

- **Google Play** [confirmed: Play Console Help 10964491]: `isAccessibilityTool="true"` is only for
  apps whose primary purpose is helping people with disabilities; a general dictation app does
  not qualify (voice input for motor impairment can qualify only if that is the primary purpose).
  Non-tools need an in-app **prominent disclosure + affirmative consent** and the **Permissions
  Declaration Form** with a video. Wispr ships on Play this way. Irrelevant for sideload.
- **Restricted settings** (Android 13+, widened by Android 15 CDD 9.8) [confirmed: CDD + Google
  help]: for apps installed from a local file or a non-store app, Accessibility is greyed out (on
  15+ also display-over-other-apps, notification listener, device admin, usage access, SMS).
  Unlock: try to enable the service once -> Settings > Apps > Voloz > ⋮ > **Allow restricted
  settings** -> confirm -> enable Accessibility. Samsung One UI 6+ may show it directly on App info
  [reported]. On 13/14 the check keyed off the install API (session installers exempt) [reported];
  Android 15's Enhanced Confirmation Mode uses an allowlist [reported, final behavior unconfirmed].
  Onboarding must walk the user through these steps.
- **Android 17 Advanced Protection** (opt-in high-security mode): revokes accessibility from apps
  without `isAccessibilityTool` [reported: Android 17 Beta 2 coverage]. Affects only users who enable
  that mode; show a clear error.
- **Android developer verification** [confirmed: developer.android.com/developer-verification]: from
  2026-09-30 in **Brazil**, Indonesia, Singapore, Thailand, for installs "from participating stores"
  on certified devices; global expansion in 2027. A "limited distribution" account (students/
  hobbyists, up to 20 devices, no ID/fee) and a power-user "advanced flow" for unverified apps
  exist. Whether a plain APK file install or `adb install` is already gated in Brazil today is
  [unconfirmed]; plan to register a limited-distribution account before wider sharing.

## 3. Recommendation: first prototype (sideloaded APK, recent Samsung + Pixel)

**Architecture: one app = one Activity (setup + settings) + one AccessibilityService that does
everything else.** Kotlin, minSdk 29 (or 33 like Wispr), targetSdk 35/36.

1. `VolozAccessibilityService`
   - Watches focus/window events; shows a `TYPE_ACCESSIBILITY_OVERLAY` bubble (right edge,
     draggable, `FLAG_NOT_FOCUSABLE`) only when an editable, non-password field is focused and
     the IME window is present.
   - **Tap to start, tap the check to stop, X to cancel** (Wispr's tap mode). States: idle ->
     recording (pulsing ring / RMS bars) -> transcribing (spinner) -> done (brief check) or error
     (Paste / Retry).
   - Records with `AudioRecord` 16 kHz mono PCM16 inside the service (no FGS); 5-min cap; WAV in
     memory or cache file.
   - POSTs to Groq `audio/transcriptions` (`whisper-large-v3-turbo`, the desktop default per
     VOLOZ.md; `whisper-large-v3` as an option), 16 kHz like the desktop app since `f13529f`;
     optional LLM cleanup with the Windows app's prompt; OkHttp; key in Android Keystore.
   - Inserts: merge + SET_TEXT + SET_SELECTION -> PASTE -> clipboard + "Paste" on the bubble.
2. `MainActivity`: Groq key entry + test call, mic permission, a guided "Allow restricted settings"
   -> Accessibility screen (deep link `Settings.ACTION_ACCESSIBILITY_SETTINGS`), a short disclosure,
   battery exemption button (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), Samsung "Never sleeping
   apps" hint.
3. Permissions in manifest: `RECORD_AUDIO`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
   `BIND_ACCESSIBILITY_SERVICE` (service). **Not** needed for v1: `SYSTEM_ALERT_WINDOW`,
   `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`.

**Spikes before building the UI (half a day each, read-only answers):**
1. Mic: tap the a11y overlay while WhatsApp is in front; does `AudioRecord` in the service return
   non-zero samples on Pixel (Android 15/16) and Samsung (One UI 7/8)? If not, switch to fallback
   in 2.3.
2. Overlay: `TYPE_ACCESSIBILITY_OVERLAY` shows above the keyboard and receives taps on both phones.
3. Insertion matrix: WhatsApp, Telegram, Gmail, Chrome (textarea + Google search), Samsung Notes,
   Google Messages (Compose), Keep, Slack. Record which of SET_TEXT / PASTE works in each.

**Defer:** Play Store release (disclosure video + declaration), hold-to-talk, waveform polish,
size/opacity/shrink settings, snooze, history + retry from saved audio, bank-app blocklist (keep
only the password/number field exclusion), OEM-specific battery flows (Xiaomi autostart etc.),
streaming/real-time transcription, m4a/FLAC compression, custom IME fallback, offline model,
developer-verification registration (needed before distributing beyond a few devices).

## Sources

- https://docs.wisprflow.ai/articles/5906198129-starting-your-first-dictation-on-android
- https://docs.wisprflow.ai/articles/5002934560-why-is-the-wispr-bar-is-not-appearing-or-disappearing
- https://docs.wisprflow.ai/articles/2809924024-android-download-installation-guide
- https://docs.wisprflow.ai/articles/6344532666-android-system-requirements
- https://docs.wisprflow.ai/articles/1842649381-why-does-google-play-keep-asking-about-wispr-flow-permissions
- https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android
- https://docs.wisprflow.ai/articles/2354103297-reliable-text-insertion-in-messaging-apps-on-android-whatsapp-telegram-messenger-signal-slack
- https://docs.wisprflow.ai/articles/4048537120-what-to-expect-from-flow-accuracy-and-known-limitations
- https://docs.wisprflow.ai/articles/7669452251-accessibility-permission-on-android (search snippet only; page 404'd on fetch)
- https://9to5google.com/2026/02/23/flow-dramatically-improves-android-voice-typing-without-replacing-gboard/
- https://hothardware.com/news/wispr-flow-ai-dictation-app-android
- https://play.google.com/store/apps/details?id=com.wispr.flowapp (did not load)
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/inputmethod/InputMethodBindingController.java
- https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/67063026/how-does-accessibilityservice-draw-on-top-of-other-apps
- https://deepwiki.com/ven-coder/assists/4-window-management
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://support.google.com/android/answer/12623953
- https://source.android.com/docs/compatibility/15/android-15-cdd (section 9.8)
- https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/
- https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860
- https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html
- https://developer.android.com/developer-verification
- https://www.whistleout.com/CellPhones/Guides/how-to-sideload-apps-on-android
- https://console.groq.com/docs/speech-to-text
- https://github.com/zackify/flo
- https://github.com/kafkasl/phone-whisper
- https://github.com/AfzalH/voice-android
- https://github.com/DevEmperor/DictateKeyboard/wiki
