# LiteRT-LM Local Server Android MVP

This repository contains a minimal Android/Kotlin app that loads one `.litertlm` model with Google's LiteRT-LM Android SDK and exposes a localhost HTTP API for Termux or another client on the same Android device.

The app is intentionally small: it has a simple screen for loading `/sdcard/Download/model.litertlm`, starting a loopback-only server on port `8080`, and reporting status.

## Build from the command line

No Android Studio workflow is required. Install a command-line Android SDK with API 35, use JDK 17, and ensure `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at the SDK. This project uses Android Gradle Plugin 8.7.x and should be built with Gradle 8.9 or newer.

This repository intentionally does not commit `gradle-wrapper.jar` because the PR pipeline rejects binary files. The checked-in `./gradlew` script is text-only and delegates to a `gradle` executable on your `PATH` or to `GRADLE_CMD` if set.

From the repository root, run:

```sh
./gradlew clean assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install with adb

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Model location

Place a LiteRT-LM model at the default path before tapping **Load Model**:

```text
/sdcard/Download/model.litertlm
```

For example, you can push a local model with:

```sh
adb push model.litertlm /sdcard/Download/model.litertlm
```

The app checks that the file exists and is readable. On Android 13 and newer, direct file access policies can vary by device; if loading fails, the UI shows the error returned by the SDK or file system.

## Run the app

1. Open **LiteRT-LM Local Server** on the Android device.
2. Confirm the model path or edit it.
3. Tap **Load Model** and wait for the status to become `Loaded`.
4. Tap **Start Server**.
5. From Termux or another local client on the same device, call `http://127.0.0.1:8080`.

## Test from Termux

Health test:

```sh
curl http://127.0.0.1:8080/health
```

Prompt test:

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" -d '{"prompt":"Hello. Confirm you are running locally."}'
```

The chat endpoint accepts either:

```json
{"prompt":"Hello"}
```

or a minimal OpenAI-style messages array:

```json
{"messages":[{"role":"user","content":"Hello"}]}
```

The response includes a simple `response` field and a minimal `choices[0].message.content` convenience field.

## Known limitations

- The LiteRT-LM dependency is intentionally declared as `latest.release` as requested for the MVP. If Google changes the API shape in a future release, pin `com.google.ai.edge.litertlm:litertlm-android` to the last verified version and update the imports/calls accordingly.
- This is an MVP bridge, not a polished chat app.
- The server binds to `127.0.0.1` only and has no authentication.
- Only non-streaming generation is implemented.
- Only one model and one conversation are managed.
- Generation requests are serialized with a mutex.
- The app tries GPU first, then falls back to CPU if GPU initialization fails. CPU success is the primary target; GPU support depends on the device and model.
- There is no boot persistence or foreground service; keep the app process alive while using the server.
