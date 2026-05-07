# Voice Infrastructure Research

Last checked: 2026-05-07

## Krisp VIVA 2.0

Krisp announced VIVA 2.0 on 2026-05-06 as a real-time voice infrastructure layer for voice agents, IVRs, and conversational AI. The current developer positioning is server-side audio processing before STT, with lightweight CPU models that operate on audio directly rather than transcripts.

Relevant capabilities for Omi Ambient Companion:

- Voice Isolation v3: isolate the primary speaker and reduce background voices/noise before VAD or STT.
- Turn Prediction v3: audio-only end-of-turn prediction, intended to reduce awkward pauses and premature responses.
- Interrupt Prediction v1: audio-only start-of-turn/intent-to-interrupt classification, meant to distinguish real interruptions from backchannels.
- VAD and signal detectors: VIVA 2.0 lists VAD plus TTS, accent, and gender detectors.
- Deployment: Krisp docs describe VIVA as server-side for voice AI agents, with bindings including Python, Node.js, Go, Rust, C++, and C. Krisp's broader SDK page also lists Android/iOS/Web/desktop availability, but the VIVA production examples are server-side.

## Fit For This Repo

Best fit is the optional controller/backend, not the standalone Android app's default path.

Reasons:

- The companion records user-owned raw audio and syncs directly to Omi. Adding a proprietary third-party audio SDK into the default Android path would complicate privacy, licensing, binary distribution, and tester trust.
- VIVA is strongest when it sits before an interactive voice agent's STT/VAD/turn-taking loop. Omi Ambient Companion is mostly capture and sync, not a live agent response loop.
- The optional FastAPI controller is already the right policy/config boundary for experimental or licensed audio infrastructure.

Recommended experiment:

1. Add a disabled-by-default controller feature flag such as `voice_infra_provider=krisp_viva`.
2. Keep Android capture unchanged and clearly visible.
3. On the controller, process fallback/backup audio only when the user has explicitly opted in and a Krisp SDK license/model path is configured server-side.
4. Use VIVA results only as diagnostics and gating metadata at first: voice isolation confidence, VAD result, turn/end-of-speech confidence, interruption classification.
5. Do not use accent/gender detectors for product behavior unless there is a concrete, consented, non-sensitive use case.
6. If results are useful, feed improved VAD/turn metadata into fallback segment handling and sync diagnostics before considering any upload-path changes.

## Current Decision

Do not vendor Krisp binaries or add Krisp runtime code to the Android companion yet. Track VIVA 2.0 as a controller-side investigation item and keep the public companion build free of proprietary voice infrastructure until licensing, privacy copy, opt-in UX, and measurable benefit are clear.

## Sources

- Krisp VIVA 2.0 announcement, Business Wire via FinancialContent: https://markets.financialcontent.com/stocks/article/bizwire-2026-5-6-krisp-launches-viva-20-introducing-voice-infrastructure-for-voice-ai-agents
- Krisp developer SDK overview: https://krisp.ai/developers/
- Krisp SDK getting started: https://sdk-docs.krisp.ai/docs/getting-started
- Krisp VIVA server getting started: https://sdk-docs.krisp.ai/docs/getting-started-server
- Krisp voice isolation model docs: https://sdk-docs.krisp.ai/docs/models-for-conversational-ai
