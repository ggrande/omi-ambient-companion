# Omi Ambient Companion

Personal Android companion app for local-first ambient capture. The companion app owns Android permissions, foreground microphone capture, VAD, encrypted spool, accessibility/caption fallback, notification triggers, and direct sync to the official Omi API after user sign-in.

This is not an Omi plugin and does not modify the official Omi app. The Ambient Second Brain Controller plugin is optional for configuration, fallback segment storage, distribution, and accountability workflows.

## What It Does

- Shows a visible armed-status notification while idle without opening the microphone.
- Starts a visible microphone foreground service only after explicit microphone watch consent.
- Uses `AudioRecord` PCM16 mono 16 kHz.
- Runs lightweight RMS/VAD first with a RAM pre-roll buffer.
- Writes speech-triggered audio to encrypted app-private spool files.
- Uses AccessibilityService for foreground app and allowlisted caption/transcript fallback.
- Uses NotificationListenerService for meeting/call/Sound Notifications/Live Transcribe context triggers.
- Can associate with a real Bluetooth companion device through Android's Companion Device Manager for better background compatibility when an actual companion device is present.
- Detects communication mode, mic silencing, low signal, network buffering, private mode, and storage limits.
- Signs in with Omi and uploads decrypted length-prefixed PCM spools directly to Omi's existing `/v2/sync-local-files` audio pipeline.
- Uploads degraded fallback-only transcript segments directly to Omi's developer conversation-from-segments endpoint when no raw audio is available and no plugin controller is configured.
- Optionally registers with `plugins/ambient-second-brain-controller` and pins its policy key.
- Optionally uploads telemetry, fallback segments, and backup audio spools to the controller backend.
- Tracks capture sessions, storage status, and local delete pending/synced/all-audio controls.
- Runs best-effort Android on-device speech recognition over finalized spools on Android 13+ when supported by the device.
- Local speech recognition is enabled by default and can be disabled from `Advanced settings`; it uses Android's on-device recognizer only.
- Supports explicit, user-approved MediaProjection audio capture for apps/audio usages Android allows.
- Uses context triggers such as meeting/call notifications, Live Transcribe/Sound Notifications, wired headset, Bluetooth audio, and SCO route changes. By default these keep the app armed and idle; automatic mic start from those triggers requires explicit continuous mic watch consent.
- Filters obvious fallback-text junk locally before creating Omi fallback conversations, including self-notifications, short UI fragments, and likely TV/movie/music captions.
- Keeps a rolling context window from captions, transcript surfaces, and relevant notifications so the app can show what happened before full mic capture started.
- Can check whether the signed-in Omi account has a trained speech profile so raw Omi uploads can benefit from Omi's server-side speaker attribution when available.
- Optional placement gates can keep capture armed but blocked unless the phone appears stationary/off-body on a desk, with a stricter face-down-on-desk mode.
- Shows a structured diagnostics snapshot in the app UI for field testing.

## Build

Download from GitHub:

- The `Ambient Companion Android` workflow uploads a verified debug APK on pull requests, pushes to `main`, and manual runs.
- Version tags such as `v0.1.1` publish the same verified APK to a GitHub Release.
- Download `omi-ambient-companion-debug-...apk`, not an APK from the official Omi app.

Local build:

```powershell
companion\android\gradlew.bat -p companion\android :app:assembleDebug --no-build-cache
```

The Android SDK must be discoverable through `ANDROID_HOME` or `companion/android/local.properties`. `local.properties` is intentionally ignored and should not be committed.

APK:

```text
companion/android/app/build/outputs/apk/debug/omi-ambient-companion-debug-v0.1.0.apk
```

The standalone companion APK must identify as:

```text
package: com.omi.ambientcompanion
label: Omi Ambient Companion
```

It installs next to the official/published Omi app and does not replace or modify it.

## Personal Setup

1. Install the APK on the Pixel.
2. Open `Omi Ambient Companion`.
3. Tap `Sign in with Omi` and complete Google/Apple auth.
4. Tap `Permissions & setup`, grant microphone and notifications.
5. Enable Omi Ambient Companion in Accessibility settings.
6. Enable Omi Ambient Companion in Notification Listener settings.
7. Allow unrestricted/background battery operation.
8. Optionally pair a real companion device from `Permissions & setup`.
9. Accept microphone watch consent if you want to start mic capture.
10. Tap `Start`.
11. Speak for 30-60 seconds, tap `Stop`, then tap `Sync`.
12. For junk reduction, open `Advanced settings` and leave `Junk filter` on. Optionally enable `Desk gate` or `Face-down gate` if you only want capture while the phone appears to be resting on a desk.
13. Leave `Minimum raw audio upload` at `4s` unless you are seeing many tiny accidental clips. Increase it from `Advanced settings` for stricter upload reduction.
14. Leave `Local speech recognition` on unless Android's on-device recognizer is causing failures or you do not want local fallback transcripts.
15. For battery testing, tap `Low power context` in `Advanced settings` and inspect the rolling context diagnostics before starting full mic capture.

Optional plugin setup:

1. Tap `Advanced settings`.
2. Enter the Ambient Second Brain Controller base URL.
3. Confirm the Omi user id.
4. Tap `Register plugin`.

The plugin URL, plugin device token, and pinned key are not required for direct Omi audio sync.

For the full field-test checklist, see `companion/TESTING.md`.

The app does not auto-record after reboot. Boot handling only resets stale recovery state.

## Safety

- Persistent notification is always visible while the mic service is running.
- The idle armed notification is not a microphone foreground service and does not show Android's microphone privacy indicator.
- Android's microphone privacy indicator is shown whenever `AudioRecord` capture is running. The app does not hide or suppress it.
- Private Mode stops active capture/upload locally.
- The app does not use `VoiceInteractionService`, SoundTrigger HAL, hidden recording, arbitrary screen scraping, or silent media sessions.
- Companion Device Manager support requires a real user-approved device association; the app does not fake companion status.
- Call/meeting capture is degraded when Android blocks audio. Captions/transcripts are labeled as fallback sources.
- Desk/face-down gates are local safety gates. They use Android sensors and are best-effort; they do not prove consent or context.
- Short raw-audio sessions and obvious caption/system junk are filtered locally when the junk filter is enabled. This is a noise-reduction control, not proof of speaker identity or consent.

## Known Limits

- Local STT uses Android's on-device recognizer when available. It is not a bundled Whisper/Vosk model and may be unavailable or limited by the system recognizer.
- Android's true low-power DSP hotword stack is not used by this app. The `Low power context` profile uses short visible sampled VAD checks plus non-mic context signals.
- MediaProjection captures only audio Android and the source app permit. It does not bypass protected meeting/call audio.
- Direct audio upload targets Omi `/v2/sync-local-files` using the user's Omi auth token. The uploaded filename intentionally matches the official Omi WAL shape: `audio_phone_pcm16_16000_1_fs160_<timestamp>.bin`.
- Omi's trained-speaker identification and fair-use/media discard logic run on Omi's server pipeline after raw audio upload/transcription. The companion can check speech-profile availability and can reduce obvious local fallback junk, but it does not have a public pre-upload endpoint for matching the trained voice locally.
- Fallback caption/local-STT text is preserved locally. When uploaded directly to Omi it is explicitly prefixed with fallback source/health labels. When the optional plugin is configured, the plugin receives structured source labels and degraded metadata.
