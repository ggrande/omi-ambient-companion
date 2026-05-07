# Codex Agent Notes

This repository is the standalone Omi Ambient Companion distribution repo.

## Primary Paths

- `companion/` - native Android companion app.
- `plugins/ambient-second-brain-controller/` - optional FastAPI controller plugin.
- `.github/workflows/ambient_companion_android.yml` - companion APK CI build.

## Build And Test

Android companion:

```powershell
companion\android\gradlew.bat -p companion\android :app:testDebugUnitTest :app:assembleDebug --no-build-cache
```

Plugin tests:

```powershell
python -m pytest plugins\ambient-second-brain-controller\tests
```

## Safety

- Do not add stealth recording behavior.
- Do not hide Android microphone indicators.
- Do not claim protected call recording.
- Do not commit local auth tokens, `.env`, `local.properties`, APKs, databases, or logs.
