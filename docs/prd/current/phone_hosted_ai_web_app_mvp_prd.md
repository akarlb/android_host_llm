# PRD: Phone-Hosted Local AI Web App MVP

**Repository:** `akarlb/android_host_llm`  
**Path:** `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md`  
**Status:** Current implementation target  
**Version:** PRD v1.2  
**Date:** 2026-06-03  

---

## 1. Product Summary

The project is evolving from a working Android LiteRT-LM local model server into a phone-hosted local AI web application. The Android phone acts as a private LAN AI appliance: it downloads and runs a compatible LiteRT-LM model locally, exposes OpenAI-compatible endpoints for external tools, and serves web interfaces for both admin/operator control and normal user chat.

The current backend already supports the model-server foundation: local model loading, GPU execution, MTP/speculative decoding, LAN serving, OpenAI-compatible `/v1` endpoints, profile-oriented coding/conversation routing, CORS/private-network access, real SSE streaming, performance diagnostics, conversation reset, remote config, and benchmarks.

The next product milestone is not more model-server plumbing. It is the first full web-app vertical slice:

1. A normal user can register/login locally.
2. A normal user can open a ChatGPT-like web UI.
3. A normal user can upload Markdown files.
4. Uploaded Markdown files can be included in the model context window.
5. An admin/operator can monitor and manage the phone-hosted AI service.
6. The existing OpenAI-compatible API remains available for Page Assist, coding tools, and external clients.

This MVP remains local/LAN-first. It does not expose the phone to the public internet, does not require cloud services, and does not implement the future caregiver/check-in architecture yet. However, it must create the foundations for roles, users, sessions, uploaded files, and chat history so those future directions remain possible.

---

## 2. Product Direction

The product is becoming:

```text
Android phone as private LAN AI web appliance
```

not only:

```text
Android phone as local LiteRT-LM model server
```

The app should support three practical surfaces:

1. **Native Android/operator surface** — controls model download/load, server start/stop, LAN URLs, and diagnostics.
2. **Admin web UI** — browser-accessible status/control panel for the phone owner or operator.
3. **Normal user web UI** — ChatGPT-like browser UI for regular users who log in, chat, and upload Markdown files as context.

The normal user web UI is the missing product layer. It should not expose model-server internals, debug endpoints, MTP, benchmark knobs, or raw config unless the user is an admin and enters an advanced/admin area.

---

## 3. Current Proven Foundation

The latest backend validation confirms the model-server layer is functional:

- Root/help routes work.
- Health route works.
- `/v1/models` works.
- OpenAI-style chat completions work.
- Streaming with `data:` chunks and `[DONE]` works.
- CORS and Chrome Private Network Access preflight work.
- `/debug/config`, `/debug/perf`, `/debug/perf/history`, and `/debug/benchmark` work.
- Conversation reset works.
- Fresh-per-request mode no longer fails with the prior single-session `FAILED_PRECONDITION` error.
- GPU and MTP/speculative decoding are enabled and functional.
- No NPU work is required or desired.

This means the model-serving layer is ready to support frontend work. The missing layer is application-level web functionality: auth, user sessions, chat persistence, file upload, and normal user UX.

---

## 4. Personas

### 4.1 Admin / Phone Owner

The admin owns or controls the Android phone running the app.

Needs:

- Install and update APK.
- Download compatible model.
- Load model.
- Start/stop LAN server.
- See whether the system is healthy.
- Manage users.
- Manage uploaded files if needed.
- Access debug/performance tools.
- Copy URLs for coding tools or web access.

The admin may be technical during MVP, but the UI should move toward clarity and operational confidence.

### 4.2 Normal User

A normal user accesses the phone-hosted web UI from a browser on the same LAN.

Needs:

- Register/login simply.
- Start or resume chats.
- Upload Markdown files.
- Ask questions about uploaded context.
- Get streaming responses.
- Avoid seeing technical model/server controls.

The normal user should experience a familiar chat interface, not a backend dashboard.

### 4.3 External Tool User

A coding tool, Page Assist, browser extension, or IDE agent connects to OpenAI-compatible endpoints.

Needs:

- Stable base URL.
- Model ID.
- Streaming support.
- No auth friction during local MVP unless explicitly enabled later.
- Coding profile route optimized for short direct answers.

Existing endpoint behavior must not regress.

---

## 5. MVP Scope

### 5.1 In Scope

#### Backend

- Local user registry.
- Basic username/password registration.
- Login/logout/session.
- Role support: `ADMIN` and `USER`.
- First registered user becomes `ADMIN`.
- Later registered users become `USER`.
- Passwords stored using salted hashing, not plaintext.
- Chat session persistence.
- Message persistence.
- Markdown file upload.
- Per-user file ownership.
- Markdown chunking and storage.
- Simple context injection from selected Markdown files.
- Normal user app API endpoints.
- Minimal admin app API endpoints.
- Static web asset serving.
- Preservation of existing OpenAI-compatible endpoints.

#### Frontend

- Login page.
- Register page.
- Normal chat page.
- Chat list/sidebar.
- Streaming response display.
- Markdown file upload page/panel.
- File selection for chat context.
- Logout.
- Minimal admin status page.
- Basic role-aware navigation.

#### Supported File Type

- `.md` Markdown files only.
- UTF-8 text assumption.
- Size limit enforced.
- No binary parsing.

### 5.2 Out of Scope

- PDF upload.
- DOCX upload.
- OCR.
- Embeddings.
- Vector database.
- Semantic retrieval.
- Caregiver/check-in feature.
- Remote internet exposure.
- Email verification.
- OAuth.
- Password reset.
- Multi-device sync.
- Public hosting.
- Complex admin dashboard.
- NPU backend.
- Payment/billing.
- Push notifications for normal users.
- Advanced role hierarchy beyond `ADMIN` and `USER`.

---

## 6. Architecture Overview

The Android app contains:

1. Native Android operator layer.
2. Embedded HTTP server.
3. LiteRT-LM model manager.
4. OpenAI-compatible API layer.
5. Application API layer.
6. Static web frontend assets.
7. Local persistence for users, chats, messages, files, and chunks.

Conceptual structure:

```text
Android App
├── LiteRtLmManager
├── LocalHttpServer
├── OpenAI-Compatible API
│   ├── /v1
│   ├── /coding/v1
│   └── /conversation/v1
├── Web App API
│   ├── /auth/*
│   ├── /api/chats/*
│   ├── /api/files/*
│   └── /api/admin/*
├── Static Web UI
│   ├── /login
│   ├── /register
│   ├── /chat
│   ├── /files
│   └── /admin
└── Local Storage
    ├── users
    ├── sessions
    ├── chats
    ├── messages
    ├── uploaded files
    └── file chunks
```

---

## 7. Backend Requirements

### 7.1 Persistence Layer

The app needs local persistence. SQLite is preferred if implementation complexity is acceptable. JSON files are acceptable only if SQLite becomes a blocker.

Preferred: SQLite via Android SQLite APIs or Room if practical.

Minimum entities:

#### User

```text
id: String
username: String
passwordHash: String
passwordSalt: String
role: ADMIN | USER
createdAtMs: Long
updatedAtMs: Long
```

Rules:

- Username must be unique.
- Username should be case-insensitive for uniqueness.
- Password must never be stored in plaintext.
- First registered user becomes `ADMIN`.
- Later users become `USER`.

#### Session

```text
id: String
tokenHash: String
userId: String
createdAtMs: Long
lastSeenAtMs: Long
expiresAtMs: Long?
```

MVP rules:

- Session can be a cookie or bearer token.
- Prefer HTTP-only cookie for browser UI if easy.
- Token-based header is acceptable for MVP.
- Sessions must be revocable by logout.

#### Chat

```text
id: String
userId: String
title: String
profile: CODING | CONVERSATION | CUSTOM
createdAtMs: Long
updatedAtMs: Long
archived: Boolean
```

#### Message

```text
id: String
chatId: String
role: user | assistant | system
content: String
createdAtMs: Long
```

MVP rules:

- Store user and assistant messages.
- System/context wrapper messages may be stored or reconstructed.
- Do not store Hugging Face tokens or API keys in chat messages.

#### UploadedFile

```text
id: String
userId: String
originalFilename: String
safeFilename: String
mimeType: String
sizeBytes: Long
storagePath: String
createdAtMs: Long
```

#### FileChunk

```text
id: String
fileId: String
chunkIndex: Int
headingPath: String?
content: String
charCount: Int
createdAtMs: Long
```

---

### 7.2 Authentication Endpoints

#### POST `/auth/register`

Request:

```json
{
  "username": "alice",
  "password": "password123"
}
```

Response:

```json
{
  "status": "ok",
  "user": {
    "id": "...",
    "username": "alice",
    "role": "ADMIN"
  }
}
```

Rules:

- First registered user is `ADMIN`.
- Later users are `USER`.
- Duplicate username returns `409`.
- Weak/empty password returns `400`.
- No email verification.
- No password reset.

#### POST `/auth/login`

Request:

```json
{
  "username": "alice",
  "password": "password123"
}
```

Response:

```json
{
  "status": "ok",
  "user": {
    "id": "...",
    "username": "alice",
    "role": "ADMIN"
  },
  "token": "..."
}
```

Rules:

- If cookie-based session is used, token may be omitted from response.
- Invalid login returns `401`.

#### POST `/auth/logout`

Invalidates current session.

#### GET `/auth/session`

Returns current authenticated user or unauthenticated status.

---

### 7.3 Chat API Endpoints

#### GET `/api/chats`

Returns chats owned by current user.

#### POST `/api/chats`

Request:

```json
{
  "title": "New chat",
  "profile": "CONVERSATION"
}
```

Creates chat.

#### GET `/api/chats/{chatId}`

Returns chat metadata and messages.

#### POST `/api/chats/{chatId}/messages`

Request:

```json
{
  "content": "What does this file say about the project?",
  "stream": true,
  "fileIds": ["file_1", "file_2"]
}
```

Behavior:

1. Verify chat belongs to current user.
2. Save user message.
3. Assemble prompt context from selected Markdown file chunks.
4. Call LiteRT-LM using the chat's effective profile.
5. Stream assistant response if requested.
6. Save assistant message.

Response:

- If `stream=false`, return JSON with assistant message.
- If `stream=true`, return SSE chunks and `[DONE]`.

#### DELETE `/api/chats/{chatId}`

Archives or deletes chat. MVP may soft-delete.

---

### 7.4 File API Endpoints

#### GET `/api/files`

Returns files uploaded by current user.

#### POST `/api/files/upload`

Multipart upload.

MVP constraints:

- Accept `.md` only.
- Reject files larger than configurable limit, default 1 MB or 2 MB.
- Store under app-private/user-specific directory.
- Sanitize filename.
- Save metadata.
- Chunk file.
- Return file ID and chunk count.

Response:

```json
{
  "status": "ok",
  "file": {
    "id": "...",
    "filename": "notes.md",
    "sizeBytes": 12345,
    "chunkCount": 4
  }
}
```

#### GET `/api/files/{fileId}`

Returns metadata and optionally chunk previews.

#### DELETE `/api/files/{fileId}`

Deletes file metadata, chunks, and stored file.

---

### 7.5 Markdown Chunking MVP

The chunking system should be simple and deterministic.

Algorithm:

1. Read UTF-8 Markdown text.
2. Split by headings if possible (`#`, `##`, `###`).
3. Preserve heading path.
4. If a section is too large, split into fixed character chunks.
5. Target chunk size: 2,000–4,000 characters.
6. Hard cap context inclusion per request.

No embeddings. No semantic retrieval. No vector search.

Context inclusion MVP:

- If selected files are small, include all chunks up to context budget.
- If selected files exceed budget, include first chunks and warn in response metadata.
- Future retrieval can replace this later.

Prompt context wrapper:

```text
You may use the following uploaded Markdown context.

[File: notes.md]
[Chunk 1]
...
[/Chunk]
[/File]

User question:
...
```

---

### 7.6 Admin API Endpoints

#### GET `/api/admin/status`

Admin only.

Returns:

```json
{
  "modelLoaded": true,
  "backendStatus": "Loaded with GPU",
  "serverMode": "lan",
  "codingBaseUrl": "http://PHONE_IP:8080/coding/v1",
  "conversationBaseUrl": "http://PHONE_IP:8080/conversation/v1",
  "totalUsers": 3,
  "totalFiles": 12,
  "debug": {
    "perf": "/debug/perf",
    "config": "/debug/config"
  }
}
```

#### GET `/api/admin/users`

Admin only. Returns user list.

#### GET `/api/admin/files`

Admin only. Returns uploaded file overview across users.

MVP admin endpoints should be minimal. Do not build a full admin management system yet.

---

## 8. Existing OpenAI-Compatible API Preservation

The following must continue working:

- `GET /`
- `GET /routes`
- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `GET /debug/routes`
- `GET /debug/config`
- `POST /debug/config`
- `GET /debug/perf`
- `GET /debug/perf/history`
- `POST /debug/benchmark`
- `POST /v1/conversation/reset`
- `GET /coding/v1/models`
- `POST /coding/v1/chat/completions`
- `GET /conversation/v1/models`
- `POST /conversation/v1/chat/completions`

Do not break Page Assist or coding tool usage.

Recommended external tool setup:

```text
Coding Base URL: http://PHONE_IP:8080/coding/v1
Conversation Base URL: http://PHONE_IP:8080/conversation/v1
Model: local-litert-lm
```

---

## 9. Frontend Requirements

### 9.1 Frontend Delivery

The Android app should serve static web assets from the embedded HTTP server.

Possible MVP approach:

- Static HTML/CSS/JS files bundled in Android assets.
- Simple vanilla JS frontend.
- No build step if possible.
- If a frontend build step is introduced, keep it simple and documented.

Preferred MVP frontend approach:

```text
assets/web/
├── index.html
├── login.html
├── register.html
├── chat.html
├── admin.html
├── styles.css
└── app.js
```

Routes should serve pages cleanly:

- `/login`
- `/register`
- `/chat`
- `/files`
- `/admin`

If direct route serving is difficult, a single-page app fallback is acceptable.

---

### 9.2 Public Screens

#### Login Screen

Fields:

- Username.
- Password.
- Login button.
- Link to Register.

Behavior:

- On success, navigate to `/chat`.
- On failure, show inline error.

#### Register Screen

Fields:

- Username.
- Password.
- Confirm password.
- Register button.
- Link to Login.

Behavior:

- First user becomes admin.
- On success, navigate to `/chat`.
- Duplicate username shows clear error.

---

### 9.3 Normal User Chat UI

The chat UI should feel familiar and simple.

Layout:

```text
┌─────────────────────────────────────────┐
│ Header: app name, user menu, logout     │
├───────────────┬─────────────────────────┤
│ Sidebar       │ Chat messages           │
│ - New chat    │                         │
│ - Chat list   │                         │
│ - Files       │                         │
│               │ Input box + Send        │
└───────────────┴─────────────────────────┘
```

Features:

- New chat.
- Chat list.
- Message history.
- Streaming assistant response.
- Text input.
- Send button.
- Stop/cancel button if backend supports it safely.
- File context selector.
- Visible indication of selected files.

MVP behavior:

- Default profile: `CONVERSATION` for normal web chat.
- Use `/api/chats/{id}/messages`, not raw `/v1/chat/completions`.
- Backend handles context assembly and message persistence.

---

### 9.4 Files UI

Normal user file UI:

- Upload `.md` file.
- List uploaded files.
- Show filename, size, created date, chunk count.
- Delete file.
- Select file(s) for chat context.

MVP constraints:

- `.md` only.
- Show clear error for unsupported types.
- Show clear error for oversized files.

---

### 9.5 Admin UI

Admin page should be simple.

Sections:

#### System Status

- Model loaded.
- Backend: GPU/CPU.
- MTP enabled.
- Server running.
- LAN IP.
- Coding URL.
- Conversation URL.

#### Model / Server

- Show model path/status.
- Load model button if not loaded.
- Start/stop server if available from web context.
- If native-only controls remain, show instruction to use Android admin screen.

#### Users

- List users.
- Role shown.
- No complex user management required in MVP.

#### Files

- Total files.
- Total storage used.
- Optional list across users.

#### Diagnostics

- Link to `/debug/perf`.
- Link to `/debug/config`.
- Link to `/debug/benchmark/presets` if available.

Admin UI must be hidden from normal users.

---

## 10. Security Requirements

MVP is local/LAN-first, but basic hygiene is required.

Required:

- Passwords must be salted and hashed.
- No plaintext passwords.
- No Hugging Face token exposure.
- No local server API key exposure through normal user UI.
- Normal users can access only their own chats/files.
- Admin endpoints require admin role.
- File paths must be sanitized.
- Uploaded files must be stored in app-specific storage.
- Reject path traversal attempts.
- Limit upload size.
- Do not expose debug endpoints through normal UI navigation.

Not required for MVP:

- HTTPS.
- OAuth.
- Email verification.
- Password reset.
- Rate limiting.
- Public internet hardening.

Warning text should exist in admin UI:

> This MVP is intended for trusted local networks. Do not expose this server to the public internet.

---

## 11. UX Principles

### 11.1 Normal User UX

- Familiar ChatGPT-like layout.
- Minimal settings.
- No technical model/server jargon.
- Clear file upload and context selection.
- Streaming should feel responsive.
- Errors should be plain-language.

### 11.2 Admin UX

- Operational clarity over beauty.
- Status-first.
- Copyable URLs.
- Clear model/server state.
- Debug info available but not overwhelming.

### 11.3 Mode Simplicity

Do not expose low-level tuning first. Use simple mode language:

- Coding.
- Conversation.
- Custom/Advanced.

---

## 12. Non-Functional Requirements

### Performance

- First visible streaming chunk should appear as quickly as current backend permits.
- Chat UI must stream text progressively.
- Markdown context inclusion must respect a context budget.
- Large files should not freeze UI.

### Reliability

- App should not crash on invalid file upload.
- App should not crash on unauthenticated API access.
- App should not crash if model is not loaded.
- Chat endpoint should return clear errors if model unavailable.

### Storage

- All files stored in app-specific storage.
- User uploads separated by user ID.
- Deleting a file deletes chunks.
- Deleting a user in future should delete or archive their content.

### Compatibility

- Existing OpenAI-compatible endpoints must keep working.
- Browser CORS/PNA behavior must remain working.
- Page Assist should not regress.

---

## 13. Acceptance Criteria

### 13.1 Backend Acceptance

- APK builds.
- Existing server regression tests still pass.
- Register endpoint works.
- Login endpoint works.
- Session endpoint works.
- Logout endpoint works.
- First user becomes admin.
- Later users become normal users.
- Passwords are not stored plaintext.
- Authenticated user can create chat.
- Authenticated user can send message.
- Assistant response streams in web UI/API.
- Messages are persisted.
- Authenticated user can upload `.md` file.
- Uploaded file is chunked.
- Uploaded file can be selected as context.
- Chat response can use selected Markdown file context.
- User cannot access another user's files/chats.
- Admin status endpoint works.
- Normal user cannot access admin endpoint.

### 13.2 Frontend Acceptance

- `/login` loads.
- `/register` loads.
- User can register.
- User can login.
- User can logout.
- User can open `/chat`.
- User can start a new chat.
- User can send a message.
- Assistant response streams visibly.
- User can upload `.md` file.
- User can select uploaded file for context.
- User can ask question using file context.
- User can see chat history.
- Admin can open `/admin`.
- Normal user cannot access `/admin`.

### 13.3 Regression Acceptance

These must continue to work:

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions` non-streaming.
- `POST /v1/chat/completions` streaming.
- `GET /debug/perf`
- `POST /debug/benchmark`
- `/coding/v1` profile routes.
- `/conversation/v1` profile routes.
- CORS/PNA preflight.

---

## 14. Suggested Implementation Phases

### Phase 1: Persistence + Auth

- Add local database/storage layer.
- Add users table/model.
- Add session handling.
- Add register/login/logout/session endpoints.
- Add basic auth middleware.
- Add first-user-admin behavior.

### Phase 2: Chat Storage + Normal Chat API

- Add chats/messages tables.
- Add chat CRUD endpoints.
- Add message endpoint that calls LiteRT-LM.
- Add streaming support through app-level chat endpoint.
- Persist assistant messages after generation.

### Phase 3: Markdown Upload + Context Injection

- Add file upload endpoint.
- Store `.md` files.
- Add chunking.
- Add file list/delete endpoints.
- Add context assembly for selected files.
- Include context in model prompt.

### Phase 4: Web UI MVP

- Add static web file serving.
- Add login/register pages.
- Add chat page.
- Add files panel/page.
- Add streaming display.
- Add basic role-aware navigation.

### Phase 5: Minimal Admin Web UI

- Add admin status page.
- Add users list.
- Add file/storage overview.
- Link diagnostics.

### Phase 6: Regression Testing

- Update test scripts.
- Add auth/chat/file tests.
- Confirm existing OpenAI routes still work.
- Confirm Page Assist still works.

---

## 15. Test Plan

### Auth Tests

- Register first user.
- Confirm role admin.
- Register second user.
- Confirm role user.
- Login valid user.
- Login invalid password.
- Logout.
- Session check.

### Chat Tests

- Create chat.
- Send message non-streaming.
- Send message streaming.
- Confirm message saved.
- Confirm chat list includes chat.

### File Tests

- Upload valid `.md`.
- Reject non-md file.
- Reject oversized file.
- List files.
- Delete file.
- Select file in chat.
- Confirm context affects answer.

### Role Tests

- Admin can access `/api/admin/status`.
- Normal user cannot access `/api/admin/status`.
- User A cannot access User B chat/file.

### Regression Tests

- Existing `test_all.sh` or equivalent still passes.
- Profile routes still pass.
- Streaming still includes `[DONE]`.
- CORS/PNA still works.

---

## 16. Major Risks

### 16.1 Scope Creep

Risk: Attempting to build full RAG, PDF support, polished UI, advanced roles, and caregiver features at once.

Mitigation: MVP only supports Markdown upload and deterministic context injection.

### 16.2 Context Window Overload

Risk: Uploaded files are too large and degrade model speed or exceed usable context.

Mitigation: Enforce file size and context budget; show clear warnings.

### 16.3 Security Weakness

Risk: Local user auth is too weak.

Mitigation: Use salted password hashes, sessions, role checks, and LAN-only warning.

### 16.4 Breaking Existing OpenAI API

Risk: Web app changes break Page Assist/coding clients.

Mitigation: Treat `/v1`, `/coding/v1`, `/conversation/v1` as regression-protected endpoints.

### 16.5 Frontend Complexity

Risk: Frontend becomes too polished/complex before product skeleton is proven.

Mitigation: Vanilla/basic UI first; design system later.

---

## 17. Definition of Done

The MVP is done when:

1. APK compiles.
2. Existing model server tests pass.
3. Admin can register as first user.
4. Normal user can register/login.
5. Normal user can open web chat.
6. Normal user can send a message and receive streaming answer.
7. Normal user can upload `.md` file.
8. Normal user can select `.md` file as context.
9. Model response uses selected Markdown context.
10. Chat history persists.
11. Admin can access minimal admin status page.
12. Normal user cannot access admin page.
13. Page Assist/coding endpoint still works.
14. No NPU code is added.
15. No APK/model/binary files are committed.

---

## 18. Final Product Direction

This PRD establishes the next major transition:

From:

```text
Android phone as local LiteRT-LM model server
```

To:

```text
Android phone as private LAN AI web appliance
```

The immediate goal is not a polished public product. The goal is a working local vertical slice: users, chat, Markdown context, and admin oversight, all hosted from the phone and powered by the existing local LiteRT-LM backend.
