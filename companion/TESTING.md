# Omi Ambient Companion Test Guide

This guide is for personal Pixel testing of the standalone `Omi Ambient Companion` app.

## Build Or Download

Local debug APK:

```powershell
companion\android\gradlew.bat -p companion\android :app:assembleDebug --no-build-cache
```

The Android SDK must be discoverable through `ANDROID_HOME` or `companion/android/local.properties`. `local.properties` is intentionally ignored and should not be committed.

APK path:

```text
companion/android/app/build/outputs/apk/debug/omi-ambient-companion-debug-v0.1.0.apk
```

GitHub Actions also uploads the debug APK from the `Ambient Companion Android` workflow. Download the artifact named
`omi-ambient-companion-standalone-debug-apk-...`, not the regular Omi app APK. Tagged builds such as `v0.1.1`
also attach the verified debug APK and `apk-badging.txt` to the GitHub Release.

Before installing, verify the standalone identity:

```powershell
& C:\Android\Sdk\build-tools\36.0.0\aapt.exe dump badging companion\android\app\build\outputs\apk\debug\omi-ambient-companion-debug-v0.1.0.apk | findstr "package application-label"
```

Expected:

```text
package: name='com.omi.ambientcompanion'
application-label:'Omi Ambient Companion'
```

## Omi Sign-In Setup

The current recommended test path is direct Omi sync:

1. Open `Omi Ambient Companion`.
2. Tap `Sign in with Omi`.
3. Complete Google/Apple auth.
4. Confirm `Preflight` shows `OK - Omi user id` and `OK - Omi auth token`.

The optional controller plugin is no longer required for raw audio import into Omi. Keep it only if you want remote policy/configuration, plugin distribution, fallback segment storage, telemetry, or accountability workflows.

## Optional Controller Setup

The companion expects the `Ambient Second Brain Controller` plugin backend to be reachable from the phone.

Local controller:

```powershell
cd plugins\ambient-second-brain-controller
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install fastapi uvicorn python-dotenv requests cryptography pytest
Copy-Item .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

Expose it to the phone with your preferred tunnel. Set `WEBHOOK_BASE_URL` in the controller environment to the same public base URL.

Without the optional controller, the companion still uses direct Omi auth for audio sync.

## First Install

1. Install `omi-ambient-companion-debug-...apk`.
2. Open `Omi Ambient Companion`.
3. Tap `Sign in with Omi` and complete auth.
4. Tap `Permissions & setup` and grant microphone, notifications, and Bluetooth route permission if prompted.
5. Enable `Omi Ambient Companion` in Accessibility settings.
6. Enable notification listener access for `Omi Ambient Companion`.
7. Allow unrestricted or exempt background operation.
8. Return to the app and check `Preflight`.
9. Optional: open `Advanced settings`, tap `Check voice profile`, and enable `Desk gate` or `Face-down gate` if you want placement-gated capture.
10. Confirm `Local speech recognition` is on unless you are intentionally testing without Android on-device STT fallback.

The `Preflight` section should show `OK` for Omi user id, Omi auth token, microphone, notifications, accessibility, notification listener, and battery. Plugin rows are optional and may show `SKIPPED`.

## Privacy And Consent Checks

Before collecting test audio:

- Confirm Android shows the normal microphone privacy indicator whenever capture is running.
- Confirm the persistent `Omi Ambient Companion` microphone notification is visible during active mic capture.
- Confirm the user intentionally tapped `Start` and accepted microphone watch consent.
- Do not test protected call recording claims. Android may block or degrade call/meeting audio, and the app must report that honestly.
- Treat fallback caption/local-STT text as degraded fallback material. It is labeled before upload and should not be counted as normal raw-audio transcript.

## Smoke Tests

### Manual Mic Capture

1. Tap `Start`.
2. Confirm a persistent `Omi Ambient Companion` microphone notification appears.
3. Speak for 30-60 seconds.
4. Stop speaking and wait for the silence timeout.
5. Tap `Refresh Diagnostics`.
6. Confirm storage pending count or audit entries show a spool session and upload/local STT attempts.

### Offline Buffering

1. Tap `Start`.
2. Disable network.
3. Speak for 1-2 minutes.
4. Re-enable network.
5. Tap `Sync`.
6. Confirm audit log shows `spool_audio_uploaded` or sync backoff if the controller is unreachable.

### Junk Filter And Placement Gates

1. Open `Advanced settings`.
2. Confirm `Junk filter` is on.
3. Confirm `Minimum raw audio upload` is set to `4s`, or raise it to `8s`/`12s` if you are intentionally testing stricter accidental-clip reduction.
4. Optional: tap `Desk gate`, place the phone flat/stationary, and confirm `Placement gate` becomes allowed.
5. Optional: tap `Face-down gate`, place the phone face down on a desk, and confirm capture is allowed only in that placement.
6. Send a short self-notification or system notification and confirm the log shows `fallback_segments_junk_removed` rather than a new fallback conversation.
7. Capture a very short noise burst under the minimum raw-audio threshold and confirm diagnostics show `filtered short` instead of a raw upload.

### Accessibility Caption Fallback

1. Enable Live Transcribe or open a meeting app with captions.
2. Confirm the notification/accessibility triggers show in the audit log.
3. Confirm fallback entries use `accessibility_caption` or `live_caption`.

### Local Speech Recognition Toggle

1. Open `Advanced settings`.
2. Confirm `Local speech recognition` is on by default.
3. Capture and close a short speech session.
4. Confirm diagnostics show local speech recognition enabled and a local STT status such as `completed`, `failed`, or `unavailable`.
5. Turn `Local speech recognition` off, capture another session, and confirm local STT does not run while raw audio sync still works.

### Communication Awareness

1. Start a phone call or meeting call.
2. Confirm diagnostics show communication/degraded mode rather than normal audio confidence.
3. Confirm the app does not claim protected call recording.

### Screen Audio

1. Tap `Screen Audio`.
2. Approve Android's screen/audio capture prompt.
3. Play permitted media or a meeting source that allows playback capture.
4. Tap `Stop Screen Audio`.
5. Confirm a spool session is created.

## What To Send Back When Something Fails

Please send:

- Phone model and Android version.
- Whether the APK installed cleanly.
- The controller base URL shape you used, without secrets.
- A screenshot or copied text from `Preflight`.
- The text from `Share Diagnostics`.
- The last 30-50 audit log lines.
- Whether the persistent mic notification was visible.
- Whether Omi conversations appeared, or only controller/plugin storage updated.
- Whether `Omi trace` showed conversation IDs, `discarded=true`, or a short-audio filter message.

Do not send:

- Omi auth tokens, Firebase tokens, `.env` files, `local.properties`, APK signing keys, raw databases, or unrelated private logs.

Useful optional adb commands:

```powershell
adb shell dumpsys package com.omi.ambientcompanion | findstr granted
adb shell dumpsys notification --noredact | findstr ambientcompanion
adb logcat -d -s OmiAmbient AndroidRuntime ActivityTaskManager
```

## Expected Limitations

- Local STT depends on Android's on-device recognizer and may not accept injected PCM on every build.
- MediaProjection captures only audio Android and the source app allow.
- Accessibility fallback is restricted to allowlisted meeting/caption surfaces.
- The app never auto-starts recording after reboot.
