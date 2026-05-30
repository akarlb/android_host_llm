# LiteRT-LM Phone LAN Server Android MVP

This repository contains a minimal Android/Kotlin app that loads a local Google LiteRT-LM `.litertlm` model and serves a small JSON HTTP API from the phone. The MVP is designed for practical on-device testing: install the APK, download a compatible LiteRT Community model inside the app, load it, then expose the model over either localhost or the phone's Wi-Fi/LAN address.

It is intentionally not a polished chat product. There is no streaming, no accounts, no billing, no Firebase/cloud service, and only one active model is managed at a time.

## Build the APK from GitHub Actions

The repository includes `.github/workflows/build-apk.yml`. It runs on manual dispatch, pushes to `main`, and pull requests targeting `main`. The workflow installs Java 17, Android SDK API 35/build-tools 35.0.0, Gradle 8.9, prints dependency diagnostics, and runs:

```sh
./gradlew clean assembleDebug --stacktrace --info
```

The workflow uploads the debug APK artifact named:

```text
android-host-llm-debug-apk
```

Artifact path:

```text
app/build/outputs/apk/debug/*.apk
```

This repository intentionally does not commit generated APKs, model files, `.so`, `.bin`, `.gguf`, `.litertlm`, or other binary/generated artifacts.

## Build locally

Install a command-line Android SDK with API 35, use JDK 17, and ensure `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at the SDK. The checked-in `./gradlew` script delegates to a `gradle` executable on your `PATH` or `GRADLE_CMD` if set.

```sh
./gradlew clean assembleDebug --stacktrace --info
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install the APK

Download the `android-host-llm-debug-apk` artifact from GitHub Actions, unzip it if needed, then install with adb:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also transfer the APK to the phone and sideload it using Android's package installer.

## Permissions

The app declares only the small set of permissions needed for this MVP:

- `INTERNET` is required for Hugging Face model downloads and for the local HTTP server socket.
- `ACCESS_NETWORK_STATE` is used to show whether the active network appears to be Wi-Fi, mobile, none, or unknown.
- `POST_NOTIFICATIONS` is declared for Android 13+ so the app can show a persistent foreground-service notification while the server is running. The app does **not** request notification permission on launch; tap **Enable Notifications / Persistent Server** when you want that reliability.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` are used by the local server foreground service so Android 14+ has an explicit non-camera/microphone/location foreground-service type.

No broad storage permission is requested. The app does not request `MANAGE_EXTERNAL_STORAGE` and does not use `/sdcard/Download` as the model location.

If notification permission is denied, the app can still run while open, but background/persistent reliability is reduced. For long sessions, keep the app open or manually adjust battery optimization in Android settings.

## Download Gemma 4 E2B in the app

Default model preset:

- Display name: `Gemma 4 E2B IT LiteRT-LM`
- Hugging Face repo: `litert-community/gemma-4-E2B-it-litert-lm`
- File: `gemma-4-E2B-it.litertlm`
- Size warning: about **2.6 GB**
- Recommended free storage: at least **4 GB** available before starting
- Direct URL used by the app:

```text
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true
```

The larger E4B preset is intentionally omitted for now because its exact LiteRT-LM filename was not verified during this implementation pass.

Download flow:

1. Install and open **LiteRT-LM Local Server**.
2. Check **Permissions / readiness** for free storage, active network, model directory, and LAN IP.
3. Leave the default preset as **Gemma 4 E2B IT LiteRT-LM**.
4. Optionally enter a Hugging Face token; tap **Save Hugging Face Token** if you want to persist it before downloading.
5. Tap **Download Gemma 4 E2B**. The token is also saved automatically when starting a download.
6. Watch downloaded MB / total MB, percent, speed, and status.
7. If the active network is mobile data, the app blocks the download until you explicitly check **Allow mobile data for large model download**.
8. Tap **Load Model** after the download completes.
9. Tap **Start Server**.

The downloader writes to a `.part` file first and renames it to `.litertlm` only after success. If a `.part` file exists, the next download attempt tries to resume using an HTTP `Range` request; if the server does not support resume, the app restarts the partial download cleanly.

## Model storage behavior

Downloaded models are stored in the app-specific external files directory:

```text
<applicationContext.getExternalFilesDir(null)>/models/
```

For the default E2B preset, the target path is:

```text
<externalFilesDir>/models/gemma-4-E2B-it.litertlm
```

On many devices this resembles:

```text
/sdcard/Android/data/com.example.androidhostllm/files/models/gemma-4-E2B-it.litertlm
```

The exact path is displayed in the app and is authoritative. If `getExternalFilesDir(null)` returns null, the app falls back to:

```text
<internal filesDir>/models/
```

The UI shows a warning in that fallback case because internal app data is deleted with app data and can be harder to push manually.

Manual model paths are still supported. You can edit the model path field and load a `.litertlm` file that is already present on-device. The last model path is persisted in app preferences and restored on the next launch.

## Hugging Face token handling

The default LiteRT Community model may be public, so a token is usually optional. If Hugging Face returns HTTP 401 or 403, the app shows that the model may require a Hugging Face token or accepted terms. For gated models, sign in on Hugging Face and accept the model terms first, then paste a token into the app.

The token is sent only to Hugging Face model download requests as:

```text
Authorization: Bearer <token>
```

The token is not exposed through the local HTTP API and should not appear in server responses. Hugging Face tokens are persistent: the app stores the saved token in app-private `SharedPreferences`, restores it on the next launch, and includes a **Clear Hugging Face Token** button. The local server API key is also persistent and separate from the Hugging Face token.

## LAN serving

The server supports two bind modes:

- **LAN / Wi-Fi** (default): binds the HTTP server to `0.0.0.0` and displays URLs using detected non-loopback IPv4 LAN addresses. MVP no-auth mode is the default, so browser extensions and OpenAI-compatible clients can connect without a required API key.
- **Localhost only**: binds to `127.0.0.1` for same-phone clients such as Termux.

The phone and client must be on the same network. Some routers enable client isolation or firewall rules that block device-to-device traffic; if so, LAN requests may fail even when the app is working.

The app enumerates network interfaces, excludes loopback/IPv6 for display, prefers Wi-Fi-like interface names such as `wlan`, `wifi`, or `ap`, and displays all candidate LAN URLs. If no LAN IPv4 is detected, it shows **No LAN IPv4 detected; check Wi-Fi.**

When the server runs, a foreground service shows a persistent notification with server mode, URL, and model-loaded status, plus a stop action. There is no boot receiver and the app does not auto-start in the background.

## Local server API key

The app can generate and store a random local server API key using `SecureRandom`, but required API-key authentication is disabled by default for this MVP. This is separate from any Hugging Face token.

No API key is required for `/health`, `/v1/models`, or `/v1/chat/completions` while no-auth mode is active. The local HTTP API also sends CORS and Chrome Private Network Access headers so browser-based clients can make cross-origin LAN requests.

Health responses return only basic status such as:

```json
{"status":"ok","modelLoaded":true,"serverMode":"lan"}
```

If API-key enforcement is re-enabled in a future build, generation requests can authenticate with either `Authorization: Bearer <LOCAL_SERVER_KEY>` or `X-API-Key: <LOCAL_SERVER_KEY>`.

## Page Assist / OpenAI-compatible clients

Use these settings for Page Assist or another OpenAI-compatible client:

- **Base URL:** `http://<PHONE_IP>:8080/v1`
- **Model:** `local-litert-lm`
- **API key:** leave blank, or put any placeholder if the UI requires one, because MVP no-auth mode is the default
- **Chat endpoint used by clients:** `POST http://<PHONE_IP>:8080/v1/chat/completions`
- **Models endpoint:** `GET http://<PHONE_IP>:8080/v1/models`

If Page Assist says “Failed to fetch”, open the Chrome extension service worker console and look for CORS, Private Network Access, `404 /v1/models`, or streaming parse errors.

## Curl examples from another device on the same Wi-Fi/LAN

Health:

```sh
curl -i http://<PHONE_IP>:8080/health
```

Models:

```sh
curl -i http://<PHONE_IP>:8080/v1/models
```

Prompt request:

```sh
curl -i -X POST http://<PHONE_IP>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Hello. Confirm you are running on the phone."}'
```

Non-streaming chat:

```sh
curl -i -X POST "http://<PHONE_IP>:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-litert-lm","messages":[{"role":"user","content":"Say hello."}],"stream":false}'
```

Streaming compatibility:

```sh
curl -N -X POST "http://<PHONE_IP>:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"model":"local-litert-lm","messages":[{"role":"user","content":"Say hello."}],"stream":true}'
```

Browser CORS/PNA test: open a browser console and run:

```js
fetch("http://<PHONE_IP>:8080/v1/models").then(r => r.text()).then(console.log).catch(console.error)
```

Responses include both a simple field:

```json
{"response":"..."}
```

and an OpenAI-compatible shape at `choices[0].message.content`.

## Curl example from Termux on the same phone

```sh
curl http://127.0.0.1:8080/health
```

If you started in localhost-only mode, call `http://127.0.0.1:8080/v1/chat/completions` from the same phone.

## Known limitations

- Streaming responses are compatibility SSE responses: the app generates the full response first, then sends OpenAI-style SSE chunks ending with `[DONE]`.
- One active model/conversation at a time.
- Model download is large and can take a long time.
- Device battery management may kill the app unless the foreground service/notification is active or the app stays open.
- LAN access depends on same-network connectivity, router/client isolation, and firewall behavior.
- GPU support depends on the device, drivers, and model. The app tries GPU first and falls back to CPU.
- This is an MVP control screen, not a polished product UI.
