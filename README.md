# LiteRT-LM Phone LAN Server Android MVP

This repository contains a minimal Android/Kotlin app that loads a local Google LiteRT-LM `.litertlm` model and serves a small JSON HTTP API from the phone. The MVP is designed for practical on-device testing: install the APK, download a compatible LiteRT Community model inside the app, load it, then expose the model over either localhost or the phone's Wi-Fi/LAN address.

It is intentionally not a polished chat product. It has no billing, no Firebase/cloud service, and only one active model is managed at a time.

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

Health responses return tiered local status without secrets:

```json
{"status":"ok","appAlive":true,"databaseAvailable":true,"modelLoaded":true,"storageWritable":true,"securityMode":"TRUSTED_LAN","serverMode":"TRUSTED_LAN"}
```

If API-key enforcement is re-enabled in a future build, generation requests can authenticate with either `Authorization: Bearer <LOCAL_SERVER_KEY>` or `X-API-Key: <LOCAL_SERVER_KEY>`.

## Security modes and request IDs

The embedded server now reports an explicit security mode:

- `LOCAL_DEV` for localhost-oriented development.
- `TRUSTED_LAN` for LAN / Wi-Fi serving.

`LOCAL_DEV` preserves MVP compatibility for local debugging. `TRUSTED_LAN` keeps app/admin APIs session-protected and requires an admin session for `/debug/*` diagnostics. OpenAI-compatible model routes remain open by default unless local API-key enforcement is enabled, so do not expose the server to the public internet.

JSON error responses preserve the legacy top-level `error` string and add `requestId` plus structured `errorDetails`. Responses include `X-Request-Id` for correlation.

Sessions have a 12-hour absolute lifetime and a 2-hour idle timeout. `POST /auth/logout` invalidates the current session, and `POST /auth/logout-all` invalidates all sessions for the current user. Login attempts are throttled after repeated failures for the same normalized username.

Detailed references:

- API contract: `docs/api/api_contract.md`
- Route/auth matrix: `docs/security/route_auth_matrix.md`

## Local auth MVP

The server includes a local-only auth foundation for the future phone-hosted web app. It stores users and sessions in app-private SQLite tables, hashes passwords with per-user PBKDF2 salts, and stores only SHA-256 session-token hashes. Raw session tokens are returned only by register/login responses.

Auth routes:

- `POST /auth/register` with `{"username":"alice","password":"password123"}`
- `POST /auth/login`
- `POST /auth/logout`
- `POST /auth/logout-all`
- `GET /auth/session`

The first registered local user is assigned `ADMIN`; later users are assigned `USER`. Username uniqueness is case-insensitive after trimming. Authenticated app routes should use `Authorization: Bearer <SESSION_TOKEN>`. An HTTP-only `session` cookie is also set on successful register/login for browser clients.

This auth layer is not applied to the OpenAI-compatible model routes during the MVP. `/health`, `/v1/models`, `/v1/chat/completions`, `/coding/v1`, `/conversation/v1`, and debug/model-server routes keep their existing behavior.

To smoke-test the auth foundation against a running phone server:

```sh
BASE_URL=http://<PHONE_IP>:8080 ./test_auth_foundation.sh
```

The script prints results and writes Markdown to `results_auth_foundation.md`. Set `RUN_CHAT=0` if the model is not loaded and you only want auth plus lightweight endpoint checks.

## MVP web app quick start

This MVP serves both a normal chat web UI and a minimal admin/operator web UI from the phone. It is intended only for trusted local networks. Do not expose this server to the public internet.

End-to-end setup:

1. Build or download the debug APK.
2. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or sideload the APK on the phone.
3. Open **LiteRT-LM Local Server** on the phone.
4. Download or select a compatible `.litertlm` model.
5. Tap **Load Model**.
6. Tap **Start Server**.
7. Note the displayed LAN URL, usually `http://<PHONE_IP>:8080`.

Open the normal web UI from another device on the same LAN:

```text
http://<PHONE_IP>:8080/chat
```

First-user bootstrap:

1. Open `http://<PHONE_IP>:8080/register`.
2. Register the first local account. The first account becomes `ADMIN`.
3. The app stores the session in the browser and redirects to chat.
4. Admin users see an **Admin** link in the chat top bar.

Normal-user flow:

1. Register or log in at `/register` or `/login`.
2. Open `/chat`.
3. Create or select a chat.
4. Send messages; responses stream as Server-Sent Events and are saved with the chat.
5. Refresh `/chat`; saved chats and messages remain available for that authenticated user.

Markdown context:

1. In `/chat`, use the file upload control in the sidebar.
2. Upload a UTF-8 `.md` file. `text/markdown` and `text/plain` MIME types are accepted when the filename ends in `.md`.
3. Select one or more uploaded files before sending a message.
4. The server chunks the Markdown deterministically and injects selected chunks into the generation prompt.
5. The original user message is saved without expanded file context.
6. Delete a file from the sidebar when it should no longer be available.

Admin web UI:

```text
http://<PHONE_IP>:8080/admin
```

Only `ADMIN` users can access admin APIs. Unauthenticated admin API calls return `401`; authenticated non-admin calls return `403`. The admin page shows model/server status, user and file summaries, diagnostics links, and copyable base URLs:

- Normal web app: `http://<PHONE_IP>:8080/chat`
- OpenAI-compatible base URL: `http://<PHONE_IP>:8080/v1`
- Coding client base URL: `http://<PHONE_IP>:8080/coding/v1`
- Conversation client base URL: `http://<PHONE_IP>:8080/conversation/v1`

External OpenAI-compatible clients:

- Use `/v1` for general compatibility.
- Use `/coding/v1` for concise coding-oriented responses.
- Use `/conversation/v1` for balanced conversation-oriented responses.
- Model ID: `local-litert-lm`.
- MVP no-auth mode is the default for model-server routes, so API key can be blank or a placeholder if a client requires one.

Final full-stack regression against a running phone server with a loaded model:

```sh
./test_mvp_full_stack.sh <PHONE_IP> 8080
```

The script prints live PASS/FAIL output and writes `results_mvp_full_stack.md`. Run it against a fresh app data store when validating first-user admin bootstrap.

## App chat storage API

Authenticated web-app chat routes persist chats and messages in app-private SQLite storage. They use local session auth with `Authorization: Bearer <SESSION_TOKEN>` or the `session` cookie. Users can only access their own chats; archived chats are hidden and return `404`.

Routes:

- `GET /api/chats`
- `POST /api/chats` with optional `{"title":"New chat","profile":"CONVERSATION"}`
- `GET /api/chats/{chatId}`
- `POST /api/chats/{chatId}/messages` with `{"content":"Hello","stream":true,"fileIds":[]}`
- `DELETE /api/chats/{chatId}`

Valid chat profiles are `CODING`, `CONVERSATION`, and `CUSTOM`. The normal-user default is `CONVERSATION`. Streaming app-chat responses are SSE `data:` events ending with `data: [DONE]`; the assistant message is persisted after generation completes.

To smoke-test the chat API against a running phone server with the model loaded:

```sh
BASE_URL=http://<PHONE_IP>:8080 ./test_chat_api.sh
```

The script prints results and writes Markdown to `results_chat_api.md`.

## Markdown upload and chat context

Authenticated app users can upload Markdown notes for deterministic context injection into normal-user chat requests. Files are stored in app-private storage, tracked in SQLite, owned by exactly one user, and deleted with their stored chunks.

Routes:

- `GET /api/files`
- `POST /api/files/upload` with JSON `{"filename":"notes.md","mimeType":"text/markdown","content":"# Notes\n..."}`
- `GET /api/files/{fileId}`
- `DELETE /api/files/{fileId}`

The MVP upload route intentionally uses the documented JSON fallback instead of multipart. Only `.md` files are accepted. `text/markdown` is preferred, and `text/plain` with a `.md` filename is accepted. Uploads over 2 MB are rejected with `413`.

`POST /api/chats/{chatId}/messages` accepts selected Markdown files:

```json
{"content":"What does this file say?","stream":true,"fileIds":["..."]}
```

The original user message is persisted unchanged. Selected file chunks are loaded in deterministic file/chunk order, capped to a conservative context budget, and injected only into the prompt sent to LiteRT-LM. Non-streaming responses include `context` metadata with `includedChunks`, `includedChars`, and `truncated`; streaming responses emit the same metadata as an SSE event when file context is used.

To smoke-test Markdown upload and context against a running phone server with the model loaded:

```sh
BASE_URL=http://<PHONE_IP>:8080 ./test_markdown_context.sh
```

The script prints results and writes Markdown to `results_markdown_context.md`.

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

Performance:

```sh
curl http://<PHONE_IP>:8080/debug/perf
```

Performance history:

```sh
curl http://<PHONE_IP>:8080/debug/perf/history
```

Get runtime config:

```sh
curl http://<PHONE_IP>:8080/debug/config
```

Set concise fresh-per-request mode:

```sh
curl -X POST http://<PHONE_IP>:8080/debug/config \
  -H "Content-Type: application/json" \
  -d '{"conversationMode":"FRESH_PER_REQUEST","responseMode":"CODING_CONCISE","generationTimeoutSeconds":180}'
```

Run benchmark:

```sh
curl -X POST http://<PHONE_IP>:8080/debug/benchmark \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Explain Kotlin unresolved reference in 3 bullets.","iterations":3,"stream":true,"resetBeforeEach":true,"conversationMode":"FRESH_PER_REQUEST","responseMode":"CODING_CONCISE"}'
```

Reset conversation:

```sh
curl -X POST http://<PHONE_IP>:8080/v1/conversation/reset
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

Streaming chat:

```sh
curl -N -X POST "http://<PHONE_IP>:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"model":"local-litert-lm","messages":[{"role":"user","content":"Say hello."}],"stream":true}'
```

Concise coding streaming test:

```sh
curl -N -X POST "http://<PHONE_IP>:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-litert-lm","messages":[{"role":"user","content":"Explain this Kotlin compile error in 3 bullet points."}],"stream":true}'
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

- Streaming uses LiteRT-LM `sendMessageAsync()` and OpenAI-style SSE chunks ending with `[DONE]`; the client and network stack must keep the HTTP connection open to display chunks incrementally.
- One active model/conversation at a time.
- Uploaded context files are `.md` only.
- There are no embeddings or vector database; Markdown context is direct deterministic prompt injection.
- PDF, DOCX, OCR, and other binary document formats are not supported.
- Caregiver/check-in functionality is not implemented yet.
- There is no public internet hardening; use only on trusted local networks.
- No NPU backend is implemented.
- Model download is large and can take a long time.
- Device battery management may kill the app unless the foreground service/notification is active or the app stays open.
- LAN access depends on same-network connectivity, router/client isolation, and firewall behavior.
- GPU support depends on the device, drivers, and model. The app tries GPU first and falls back to CPU.
- This is an MVP control screen, not a polished product UI.

## Performance and streaming

This build enables LiteRT-LM Multi-Token Prediction / speculative decoding before GPU engine initialization when the pinned LiteRT-LM SDK exposes the experimental API. The app does not enable speculative decoding for CPU fallback. The UI reports the current backend and whether MTP/speculative decoding is enabled.

MTP/speculative decoding is requested by default for GPU loads. The **Enable GPU MTP / speculative decoding** checkbox controls the next model load; changing it requires tapping **Load Model** again. If the pinned SDK does not expose the experimental flag, the build should keep passing and the app reports MTP as unavailable.

For `stream=true`, the server now uses LiteRT-LM `sendMessageAsync()` and returns OpenAI-compatible Server-Sent Events incrementally as model chunks arrive. Streaming does not change the final non-streaming JSON shape; requests without `stream` or with `stream:false` still return `object: chat.completion`, `choices[0].message.content`, and the simple `response` field.

Test real streaming from another device on the same Wi-Fi/LAN:

```sh
curl -N -X POST "http://<PHONE_IP>:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-litert-lm","messages":[{"role":"user","content":"Write three short sentences."}],"stream":true}'
```

Check performance metrics:

```sh
curl http://<PHONE_IP>:8080/debug/perf
```

The `/debug/perf` response includes backend status, model-loaded status, speculative-decoding requested/enabled/available status, last load duration, last generation start time, first-chunk latency, generation duration, output character count, approximate characters per second, chunk count, streaming/non-streaming mode, request/error totals, active-generation status, and the last short error message. It does not expose prompt text, Hugging Face tokens, or API keys.

The `/debug/perf/history` endpoint returns the last 20 generation metric snapshots. It stores only timing/count/status data, not prompts or responses.

## Local operations, backup, and diagnostics

Admin-only local operations are available from `/admin` and via API:

- `GET /api/admin/ops/export` downloads a JSON backup bundle with safe users, chats, messages, Markdown file metadata/content, file chunks, chat-file state, skills, chat skill state, tool metadata, safe app settings, schema version, and export timestamp.
- `GET /api/admin/ops/diagnostics` downloads sanitized diagnostics with health, mode, model loaded status, DB schema version, counts, storage scan, recent sanitized errors, and route matrix reference.
- `GET /api/admin/ops/storage/scan` reports orphaned chunks, attachments, context state rows, tool logs, missing stored files, and orphan disk files.
- `POST /api/admin/ops/storage/cleanup` requires `{"confirm":"cleanup-orphans"}` and deletes only orphaned maintenance rows/files.

Backups intentionally exclude password hashes, salts, sessions, token hashes, Hugging Face token values, and raw storage paths. Full chat/file restore is not implemented yet; custom skill import/export remains available in the Skills panel. Keep downloaded backup files private because they include chat and uploaded Markdown content.

Fresh local readiness flow:

1. Install the APK and open the app.
2. Create the first account; it becomes `ADMIN`.
3. Create normal users from `/register` as needed.
4. Load a local `.litertlm` model or download a preset.
5. Start the LAN server only on a trusted network.
6. Visit `/health`, then `/admin`.
7. Run `bash test_local_ops.sh` against the phone URL with `BASE_URL=http://<PHONE_IP>:8080`.
8. Download a backup and diagnostics bundle before risky maintenance or upgrades.

Remote config:

```sh
curl http://<PHONE_IP>:8080/debug/config
```

```sh
curl -X POST http://<PHONE_IP>:8080/debug/config \
  -H "Content-Type: application/json" \
  -d '{"conversationMode":"FRESH_PER_REQUEST","responseMode":"CODING_CONCISE","generationTimeoutSeconds":180}'
```

`POST /debug/config` accepts optional `conversationMode`, `responseMode`, `generationTimeoutSeconds`, and `speculativeDecodingRequested` fields. Conversation and response mode changes apply immediately and are persisted. MTP setting changes are persisted but require a model reload to affect engine initialization.

Benchmark:

```sh
curl -X POST http://<PHONE_IP>:8080/debug/benchmark \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Explain Kotlin unresolved reference in 3 bullets.","iterations":3,"stream":true,"resetBeforeEach":true,"conversationMode":"FRESH_PER_REQUEST","responseMode":"CODING_CONCISE"}'
```

`POST /debug/benchmark` runs up to 5 sequential iterations using the existing generation path. For `stream:true`, chunks are consumed internally and the endpoint returns a normal JSON summary with per-iteration metrics and averages. Optional benchmark conversation/response modes are temporary for the benchmark request and do not overwrite saved config.

Reset the persistent conversation without reloading the model:

```sh
curl -X POST http://<PHONE_IP>:8080/v1/conversation/reset
```

Successful reset response:

```json
{"status":"ok","message":"Conversation reset"}
```

Resetting conversation can improve stability and speed during long coding sessions, but it clears conversational context. Reset is rejected while a generation is active.

Conversation modes:

- **Persistent conversation** keeps current behavior and retains chat memory inside the loaded LiteRT-LM conversation.
- **Fresh conversation per request** creates and closes a temporary LiteRT-LM conversation for each request without reloading the model. This may improve isolation and stability for coding clients, but it loses chat memory between requests.

For Page Assist and coding clients, test both conversation modes and compare `/debug/perf` output.

Response modes:

- **Coding concise** is the default. It prepends a short coding-focused instruction that asks for direct, actionable answers and exact patches or commands when code is needed.
- **Balanced** prepends a simple direct-answer instruction.
- **Detailed** asks for thorough answers when useful.

The app intentionally does not rely on unsupported max-token settings; shorter responses are encouraged through lightweight prompt instructions.

Timeout and cancellation:

- Generation timeout defaults to 180 seconds and can be set from 10 to 600 seconds.
- Non-streaming and streaming generation paths are wrapped with the timeout.
- Timeout errors are reported as `Generation timed out after X seconds`.
- **Cancel Current Generation** requests coroutine cancellation for the active generation. Native LiteRT-LM calls may only observe cancellation at safe suspension points, so timeout remains the main stability guard.

Performance notes:

- Streaming improves perceived speed because clients can display partial output as soon as chunks arrive instead of waiting for the full answer.
- MTP/speculative decoding may improve raw decode speed on GPU-capable devices.
- Shorter responses are faster because the model generates fewer tokens.
- Long local LLM sessions heat the phone.
- Thermal throttling may reduce speed.
- Keep the phone plugged in for long coding sessions.
- Use shorter responses for faster iteration.
- Reset conversation occasionally.
- Avoid running multiple clients at once.

Coding-client recommendations:

- Use `stream=true`.
- Use **Coding concise** response mode.
- Reset conversation when responses slow down or become unstable.
- Use `/debug/perf` to compare MTP on/off and persistent versus fresh-per-request conversation mode.
