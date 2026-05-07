# Low-Power Conversation Detection Experiments

Last checked: 2026-05-07

## What Android Gives Us

Android's true low-power always-on microphone path is the Sound Trigger / hotword stack. It is designed for hotword or acoustic-event models backed by device HAL/DSP support, usually through the selected assistant or OEM/system integrations. It is not a general third-party ambient microphone API for this companion app.

For this repo, the practical low-power path is:

- Keep full mic capture off by default.
- Use existing low-power-ish context signals first: accessibility captions, notification captions, foreground app, audio route changes, placement sensors, and optional companion-device association.
- Use short sampled `AudioRecord` VAD windows only when the user has accepted mic watch consent and Android shows the normal mic indicator.
- Use the rolling context window to preserve recent caption/notification context before full mic capture begins.

## Current Experimental Profile

The app now has an `Advanced settings` button named `Low power context`.

It sets:

- Sampled VAD on.
- Sample window: 700 ms.
- Sample interval: 60 s.
- Continuous mic auto-start off.
- Minimum raw audio upload: 8 s.
- Rolling context window: 5 minutes.

This is not DSP hotword mode. It is a low-duty-cycle visible microphone profile plus non-mic context capture.

## Rolling Context Window

The rolling context store keeps recent context-only items from:

- Accessibility captions/transcripts.
- Live Caption / Live Transcribe style notifications.
- Meeting/call/huddle notifications.

The context window is not raw audio and is not automatically treated as a normal Omi transcript. It is used to:

- show what happened before full mic capture started,
- attach a short `pre_mic_context` summary to local capture session metadata,
- improve field diagnostics when Omi produces no conversation or marks one discarded.

## Suggested Self-Test Matrix

### Baseline

1. Use the default profile.
2. Leave `Junk filter` and `Local speech recognition` on.
3. Start a short real conversation near the phone.
4. Confirm raw audio sync creates or traces an Omi conversation.

### Low Power Context

1. Tap `Advanced settings` > `Low power context`.
2. Turn on Live Transcribe or a meeting app with captions.
3. Keep the companion mic idle.
4. Speak for 1-2 minutes.
5. Confirm diagnostics show rolling context items.
6. Press `Start` and speak again.
7. Confirm the current session includes `pre_mic_context` in diagnostics.

### Caption-Only Prior Context

1. Keep full mic off.
2. Run Live Transcribe or meeting captions for 2-5 minutes.
3. Confirm fallback queue and rolling context both grow only with plausible conversation text.
4. Confirm obvious system/media fragments are filtered or marked non-candidates.

### Battery Probe

For each profile, capture:

- battery percent before/after 30 minutes,
- whether Android reports microphone use,
- number of rolling context items,
- number of raw audio spools,
- Omi trace result including `discarded=true/false`.

## Next Detection Improvements

The next likely improvements are:

- adaptive sampled VAD interval: 60 s while quiet, 5-10 s after captions/meeting notifications,
- conversation score that combines captions, foreground app, route, placement, RMS, and Omi discard feedback,
- delayed local STT so Android recognizer only runs when raw Omi sync returns no server speech,
- controller-side experiments with Krisp VIVA 2.0 or another voice infrastructure provider for turn/VAD metadata.

## Sources

- Android Sound Trigger: https://source.android.com/docs/core/audio/sound-trigger
- Android concurrent DSP/AP capture: https://source.android.com/docs/core/audio/concurrent
- Android VoiceInteractionService: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- Android Live Transcribe help: https://support.google.com/accessibility/android/answer/9158064
