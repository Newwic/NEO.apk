# NEO Android V2.1 — Local + Auto Build

NEO is a local-first Android assistant prototype for Android. The app is designed to use a llama.cpp-compatible local brain on the phone, local Room/SQLite memory, Thai speech/TTS, Android app launching, a lightweight PC Worker, and live configuration.

## Auto Build
Every push to `main` triggers GitHub Actions and builds `NEO.apk`. No Android Studio is required on your PC.

Go to **Actions → Build NEO APK → latest successful run → Artifacts → NEO-APK** to download it.

The workflow builds a debug APK and caches the same Android debug signing key between builds so later APKs can normally install over the previous NEO build without deleting app data.

## Local brain
The default brain endpoint is `http://127.0.0.1:8080` and expects an OpenAI-compatible `/v1/chat/completions` endpoint. This repository does not bundle a multi-GB model or llama.cpp binary yet.

Recommended starting profiles for a phone with 12 GB physical RAM:
- Fast: 3B Q4
- Smart: 7B Q4

## Live config
Run `pc-worker/start-worker.bat`, set the worker URL inside NEO, then edit `config/neo-config.json`. Increase `revision` to make the phone notice the new configuration. Prompt/model/router settings can update without rebuilding the APK.

## Safety
The PC Worker does not execute arbitrary model-produced shell commands. Coding/build tools will be added through explicit whitelists.
