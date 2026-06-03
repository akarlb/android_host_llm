# MVP Full Stack Handoff Audit

Date: 2026-06-03

Branch: `codex/mvp-final-regression-handoff`

## Summary

The final regression pass added the required full-stack regression script, documented the MVP operating flows, and fixed an external-client compatibility gap by exposing model-list aliases at `/coding/v1/models` and `/conversation/v1/models`.

Build and live server validation are blocked in this local environment because no Gradle/JDK executable is installed and no Android server is running on `127.0.0.1:8080`. The exact commands and failures are listed below.

## Backend Auth Status

- Local registration, login, logout, and session APIs are implemented under `/auth/*`.
- First registered user becomes `ADMIN`; later users become `USER`.
- Passwords are hashed with PBKDF2 and per-user salts.
- Session lookup stores and compares SHA-256 token hashes, not raw session tokens.
- Raw session tokens are returned only from successful register/login responses and set as the browser `session` cookie.

## Chat Persistence Status

- Authenticated users can create, list, read, message, and archive chats under `/api/chats/*`.
- Messages are persisted in SQLite.
- Streaming app-chat responses use SSE events and persist the assistant response after generation completes.
- Chat reads and deletes are scoped by authenticated user ID.

## Markdown Context Status

- Authenticated users can upload `.md` files through `/api/files/upload`.
- Uploaded files are stored per user, chunked deterministically, and tracked in SQLite.
- Only `.md` files are accepted; unsupported uploads return `400`.
- Uploads over 2 MB return `413`.
- Selected file chunks are injected into generation prompts, while the original user message remains unexpanded in persisted chat history.
- File reads and deletes are scoped by authenticated user ID.

## Normal Web UI Status

- `/login`, `/register`, and `/chat` are served as static web assets.
- The normal chat UI supports login/register, logout, chat list, new chat creation, streaming messages, Markdown upload, file selection, and file deletion.
- The normal UI shows the admin link only when `/auth/session` reports an `ADMIN` role.
- Debug endpoints are not linked from the normal user UI.

## Admin Web UI Status

- `/admin` is served as a protected web page.
- Unauthenticated `/admin` requests redirect to `/login`.
- Authenticated non-admin users receive the admin page shell, where frontend session checks render `Access denied`.
- Admin APIs are available at:
  - `GET /api/admin/status`
  - `GET /api/admin/users`
  - `GET /api/admin/files`
- Admin API unauthenticated requests return `401`.
- Admin API non-admin authenticated requests return `403`.
- Admin responses omit password hashes, salts, session tokens, token hashes, local API keys, Hugging Face tokens, and raw storage paths.

## Role Isolation Status

- Normal users cannot access `/api/admin/status`, `/api/admin/users`, or `/api/admin/files`.
- Chat and file repository reads require the requesting user's ID.
- User A chat/file detail routes return `404` to User B.
- Admin overview endpoints expose only minimal operational metadata.

## External API Compatibility Status

- Existing `/v1/models` and `/v1/chat/completions` routes remain in place.
- Added required model-list aliases:
  - `GET /coding/v1/models`
  - `GET /conversation/v1/models`
- Existing profile routes remain in place:
  - `POST /coding/v1/chat/completions`
  - `POST /conversation/v1/chat/completions`
- CORS and Chrome Private Network Access preflight behavior remains centralized in `corsPreflightResponse()`.
- Debug/performance endpoints remain available.

## Security Caveats

- This MVP is trusted-LAN only and must not be exposed to the public internet.
- OpenAI-compatible model routes are intentionally unauthenticated by default for MVP local-client compatibility.
- Browser session tokens are stored in localStorage by the current static frontend and also set as an HTTP-only cookie on register/login.
- Debug endpoints are operational diagnostics and should only be used on trusted networks.

## Known Limitations

- `.md` upload only.
- No embeddings or vector database.
- No PDF/DOCX/OCR support.
- No caregiver/check-in functionality.
- No public internet hardening.
- No NPU backend.
- One active model/conversation manager per app process.
- Full end-to-end generation tests require a running phone server with the model loaded.

## Exact Tests Run

Passed:

```sh
bash -n test_mvp_full_stack.sh
git diff --check
```

Blocked build:

```sh
./gradlew clean assembleDebug --stacktrace --info
```

Result:

```text
ERROR: Gradle is required but was not found on PATH.

Install Gradle 8.9+ and JDK 17, then rerun:
  ./gradlew clean assembleDebug

Alternatively set GRADLE_CMD to an explicit Gradle executable path, for example:
  GRADLE_CMD=/opt/gradle/bin/gradle ./gradlew clean assembleDebug
```

Blocked live full-stack script:

```sh
./test_mvp_full_stack.sh 127.0.0.1 8080
```

Expected local result in this container:

```text
curl: (7) Failed to connect to 127.0.0.1 port 8080 after 0 ms: Could not connect to server
FAIL GET /health returned 000, expected 200
```

The script is intended to run against an installed APK on a phone with the server started, the model loaded, and a fresh app data store for first-user admin bootstrap.

## Build Result

Not completed in this environment because Gradle/JDK are unavailable on `PATH`. No APK, model, or generated binary artifact was created or committed.

## Final Handoff Checklist

- Branch `codex/mvp-final-regression-handoff` exists.
- Full-stack test script exists: `test_mvp_full_stack.sh`.
- README documents install, server start, normal UI, admin bootstrap, normal login, Markdown upload/context, admin UI, external URLs, trusted-LAN warning, and limitations.
- Admin bootstrap, normal chat, Markdown context, role isolation, external API, CORS/PNA, and debug benchmark checks are covered by `test_mvp_full_stack.sh`.
- `/coding/v1/models` and `/conversation/v1/models` aliases were added.
- No NPU code was added.
- No APK/model/binary files were committed.
