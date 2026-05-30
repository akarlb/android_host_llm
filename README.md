# LiteRT-LM Local Server Android MVP

This repository contains a minimal Android/Kotlin app that loads one `.litertlm` model with Google's LiteRT-LM Android SDK and exposes a localhost HTTP API for Termux or another client on the same Android device.

The app is intentionally small: it has a simple screen for loading a user-selected model path, starting a loopback-only server on port `8080`, and reporting status. By default, the model path is derived from Android's app-specific external files directory instead of shared Downloads storage.

## Build from the command line

No Android Studio workflow is required. Install a command-line Android SDK with API 35, use JDK 17, and ensure `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at the SDK. This project uses Android Gradle Plugin 8.7.x and should be built with Gradle 8.9 or newer.

This repository intentionally does not commit `gradle-wrapper.jar` because the PR pipeline rejects binary files. The checked-in `./gradlew` script is text-only and delegates to a `gradle` executable on your `PATH` or to `GRADLE_CMD` if set. GitHub Actions installs Gradle explicitly before running `./gradlew`.

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

The app no longer defaults to `/sdcard/Download/model.litertlm`. On Android 11+ and target SDK 35, shared Downloads access for a non-media `.litertlm` file is unreliable for this MVP.

Instead, the app computes its default model path at runtime with `applicationContext.getExternalFilesDir(null)` and appends `model.litertlm`. If Android cannot provide an app-specific external files directory, the app falls back to its internal `filesDir` and shows a warning in the status text.

Use this practical flow:

1. Build and install the debug APK.
2. Open **LiteRT-LM Local Server** once so Android creates the app-specific files directory.
3. Read or copy the exact model path displayed in the **Model path** field.
4. Push your model to that displayed path:

```sh
adb push model.litertlm "<DISPLAYED_PATH>"
```

On many devices the displayed path will look like:

```text
/sdcard/Android/data/com.example.androidhostllm/files/model.litertlm
```

The exact path can vary by device and Android environment, so the path shown by the app is authoritative. Do not rely on shared Downloads storage for the default MVP flow.

If you are debugging filesystem access, `run-as` may be useful on debuggable builds, but it is not required for the normal model push flow and may be unavailable on some devices or builds:

```sh
adb shell run-as com.example.androidhostllm pwd
```

## Run the app

1. Open **LiteRT-LM Local Server** on the Android device.
2. Confirm the displayed model path or edit it manually.
3. Tap **Load Model** and wait for the status to become `Loaded`.
4. Tap **Start Server**.
5. From Termux or another local client on the same device, call `http://127.0.0.1:8080`.

## Test from Termux

Health test:

```sh
curl http://127.0.0.1:8080/health
```

Expected shape:

```json
{"status":"ok","modelLoaded":true}
```

Prompt test with a simple prompt:

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Hello. Confirm you are running locally."}'
```

The chat endpoint also accepts a minimal OpenAI-style messages array:

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello. Confirm you are running locally."}]}'
```

The response includes a simple `response` field and a minimal `choices[0].message.content` convenience field.

## GitHub Actions build

The repository includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. It runs on manual dispatch, pushes to `main`, and pull requests targeting `main`; installs Java 17, Android SDK packages, and Gradle 8.9; runs:

```sh
./gradlew clean assembleDebug
```

It uploads the debug APK artifact as `android-host-llm-debug-apk` from:

```text
app/build/outputs/apk/debug/*.apk
```

## Known limitations

- The LiteRT-LM dependency is still declared as `latest.release` until a successful build can resolve and verify a concrete SDK version in CI or a fully configured Android build environment. TODO: pin `com.google.ai.edge.litertlm:litertlm-android` to the verified version after the first successful build.
- This is an MVP bridge, not a polished chat app.
- The server binds to `127.0.0.1` only and has no authentication.
- Only non-streaming generation is implemented.
- Only one model and one conversation are managed.
- Generation requests are serialized with a mutex.
- The app tries GPU first, then falls back to CPU if GPU initialization fails. CPU success is the primary target; GPU support depends on the device and model.
- There is no boot persistence or foreground service; keep the app process alive while using the server.
