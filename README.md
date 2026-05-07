# Omi Ambient Companion

**Omi Ambient Companion** is a standalone Android companion app for local-first ambient capture that syncs to the official Omi API after user sign-in.

The companion app is separate from the published Omi app and installs as:

```text
package: com.omi.ambientcompanion
label: Omi Ambient Companion
```

## Main Project Paths

- `companion/` - standalone native Android companion app.
- `plugins/ambient-second-brain-controller/` - optional FastAPI plugin/controller backend for policy, telemetry, distribution, and accountability workflows.
- `.github/workflows/ambient_companion_android.yml` - debug APK build workflow for the companion.

## What The Companion Does

- Runs visible Android foreground microphone capture only after explicit user consent.
- Uses local VAD, RAM pre-roll, encrypted app-private PCM spool, and direct Omi raw audio sync.
- Uploads raw PCM WAL-compatible audio to Omi `/v2/sync-local-files` using the signed-in user's Omi auth token.
- Uses local STT/caption fallback only when audio is unavailable or Omi raw audio produces no server speech.
- Filters obvious fallback-text junk before creating fallback conversations.
- Supports optional desk-only and face-down-on-desk placement gates.
- Preserves Android privacy indicators and persistent notifications.

## Build Companion APK

```powershell
companion\android\gradlew.bat -p companion\android :app:assembleDebug --no-build-cache
```

The Android SDK must be discoverable through `ANDROID_HOME` or `companion/android/local.properties`. `local.properties` is intentionally ignored and should not be committed.

APK output:

```text
companion/android/app/build/outputs/apk/debug/omi-ambient-companion-debug-v0.1.0.apk
```

Verify identity:

```powershell
& C:\Android\Sdk\build-tools\36.0.0\aapt.exe dump badging companion\android\app\build\outputs\apk\debug\omi-ambient-companion-debug-v0.1.0.apk | findstr "package application-label"
```

## Setup

1. Install the companion APK.
2. Tap `Sign in with Omi`.
3. Grant microphone, notification, accessibility, notification listener, and battery permissions from `Permissions & setup`.
4. Tap `Start`, speak, stop, then tap `Sync`.
5. Confirm conversations appear in Omi.

See `companion/README.md` and `companion/TESTING.md` for details.

## Optional Plugin

The Ambient Second Brain Controller plugin is optional. Direct Omi audio sync works without it. Use the plugin when you want:

- hosted setup/distribution,
- signed capture policy,
- telemetry,
- fallback segment backup,
- accountability/task workflows.

See `plugins/ambient-second-brain-controller/README.md`.

## Safety

- The app does not hide Android microphone indicators.
- The app does not claim protected call recording.
- Accessibility is used only for context and allowed caption/transcript fallback.
- Private mode is local and wins over plugin policy.
- The app does not auto-record after reboot.

## Upstream

This project began as an experimental branch in a fork of `BasedHardware/omi`. See `UPSTREAM_OMI.md`.
