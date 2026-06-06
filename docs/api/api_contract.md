# API Contract

Date: 2026-06-06

This contract documents the local phone-hosted HTTP API. The product is local-first and supports two security modes:

- `LOCAL_DEV`: localhost-oriented development mode. Model-compatible and debug routes stay open for local compatibility.
- `TRUSTED_LAN`: LAN appliance mode. App/admin routes require local user sessions, admin routes require `ADMIN`, and debug diagnostics require an admin session.

All JSON error responses keep the legacy top-level `error` string and add:

```json
{
  "error": "Human-readable message",
  "requestId": "req-...",
  "errorDetails": {
    "code": "machine_code",
    "message": "Human-readable message",
    "requestId": "req-..."
  }
}
```

Responses include `X-Request-Id`.

## Authentication

App routes use `Authorization: Bearer <SESSION_TOKEN>` or the HTTP-only `session` cookie set by register/login.

Session policy:

- Absolute expiry: 12 hours.
- Idle timeout: 2 hours.
- Logout removes the current session.
- Logout-all removes all sessions for the current user.
- Login backoff: after 5 failed attempts for a normalized username within 15 minutes, login returns `429` with retry metadata.

## Health

### `GET /health`

Auth: public in both modes.

Response:

```json
{
  "status": "ok",
  "appAlive": true,
  "databaseAvailable": true,
  "modelLoaded": false,
  "storageWritable": true,
  "securityMode": "LOCAL_DEV",
  "serverMode": "LOCAL_DEV",
  "diagnostics": "local-dev-open"
}
```

Errors: normally none; availability booleans report degraded subsystems without exposing secrets.

## Static Web Routes

Routes:

- `GET /`
- `GET /login`
- `GET /register`
- `GET /chat`
- `GET /files`
- `GET /admin`
- `GET /styles.css`
- `GET /app.js`

Auth:

- Login/register/chat files are public static assets, with frontend session checks for app data.
- `/admin` redirects unauthenticated users to `/login`; normal users receive `403`; admins receive the admin shell.

## Auth Routes

### `POST /auth/register`

Auth: public. First registered user becomes `ADMIN`; later users become `USER`.

Request:

```json
{"username":"alice","password":"password123"}
```

Success `200`:

```json
{"status":"ok","user":{"id":"...","username":"alice","role":"ADMIN"},"token":"..."}
```

Errors:

- `400`: missing username/password or malformed JSON.
- `409`: normalized username already exists.

### `POST /auth/login`

Auth: public.

Request:

```json
{"username":"alice","password":"password123"}
```

Success `200`: same shape as register.

Errors:

- `400`: missing fields or malformed JSON.
- `401`: invalid credentials.
- `429`: failed-login backoff active; `errorDetails.details.retryAfterSeconds` is included.

### `POST /auth/logout`

Auth: authenticated.

Success `200`:

```json
{"status":"ok","message":"Logged out"}
```

Errors:

- `401`: missing, expired, idle-expired, or invalid session.

### `POST /auth/logout-all`

Auth: authenticated.

Success `200`:

```json
{"status":"ok","message":"Logged out all sessions for current user"}
```

Errors:

- `401`: missing, expired, idle-expired, or invalid session.

### `GET /auth/session`

Auth: optional.

Success authenticated:

```json
{"status":"ok","authenticated":true,"user":{"id":"...","username":"alice","role":"ADMIN"}}
```

Success unauthenticated:

```json
{"status":"unauthenticated","authenticated":false}
```

## App Chat Routes

Auth: authenticated `ADMIN` or `USER`. Users can only access their own chats and receive `404` for other users' resources.

### `GET /api/chats`

Response: `{"chats":[{"id":"...","title":"New chat","profile":"CONVERSATION","createdAtMs":0,"updatedAtMs":0}]}`

### `POST /api/chats`

Request:

```json
{"title":"Optional title","profile":"CONVERSATION"}
```

Valid profiles: `CODING`, `CONVERSATION`, `CUSTOM`.

Response: `{"chat":{...}}`

Errors: `400` invalid JSON/profile; `401` unauthenticated.

### `GET /api/chats/{chatId}`

Response:

```json
{"chat":{...},"messages":[...],"files":[...]}
```

Errors: `401`, `404`.

### `DELETE /api/chats/{chatId}`

Archives a chat.

Response: `{"status":"ok"}`

Errors: `401`, `404`.

### `POST /api/chats/{chatId}/messages`

Request:

```json
{"content":"Hello","stream":true,"fileIds":[],"skillSlug":"general","thinkingEnabled":false,"showThinking":false}
```

Streaming success: `text/event-stream` `data:` events ending with `data: [DONE]`.

Non-streaming success:

```json
{"message":{...},"userMessage":{...},"context":{...},"skill":{...},"thinking":{"present":false,"visible":false}}
```

Errors: `400`, `401`, `404`, `503` if model is not loaded, `500` generation failure.

## File Routes

Auth: authenticated. Files are per-user.

### `GET /api/files`

Response: `{"files":[{"id":"...","filename":"notes.md","originalFilename":"notes.md","mimeType":"text/markdown","sizeBytes":123,"chunkCount":1,"createdAtMs":0}]}`

### `POST /api/files/upload`

Request:

```json
{"filename":"notes.md","mimeType":"text/markdown","content":"# Notes\n..."}
```

Response: `{"status":"ok","file":{...}}`

Errors:

- `400`: invalid filename/type/encoding or malformed JSON.
- `401`: unauthenticated.
- `413`: file exceeds 2 MB.

### `GET /api/files/{fileId}`

Response: `{"file":{...},"chunks":[{"chunkIndex":0,"headingPath":null,"charCount":100,"preview":"..."}]}`

Errors: `401`, `404`.

### `DELETE /api/files/{fileId}`

Response: `{"status":"ok"}`

Errors: `401`, `404`.

## Skills Routes

### `GET /api/skills`

Auth: authenticated.

Response: `{"skills":[{...}]}`. Public skill records omit system prompts.

### `GET /api/skills/{slug}`

Auth: authenticated.

Response: `{"skill":{...}}`

### `GET /api/chats/{chatId}/skill`

Auth: authenticated chat owner.

Response: `{"skill":{...},"state":{"chatId":"...","skillSlug":"general","thinkingEnabled":false,"showThinking":false,"updatedAtMs":0}}`

### `PUT /api/chats/{chatId}/skill`

Auth: authenticated chat owner.

Request:

```json
{"skillSlug":"general","thinkingEnabled":true,"showThinking":false}
```

Response: same as get.

## Tools Routes

### `GET /api/tools`

Auth: authenticated.

Response: safe tool metadata only.

### `GET /api/chats/{chatId}/tools/logs`

Auth: authenticated chat owner.

Response: `{"logs":[{"id":"...","toolName":"...","status":"SUCCESS","errorMessage":null,"createdAtMs":0}]}`

## Admin Routes

Auth: `ADMIN`.

### `GET /api/admin/status`

Response includes model/server status, base URLs, counts, generation settings, and debug route links. It omits secrets.

### `GET /api/admin/users`

Response: `{"users":[{"id":"...","username":"alice","role":"ADMIN","createdAtMs":0,"chatCount":0,"fileCount":0}]}`

### `GET /api/admin/files`

Response: `{"files":[{"id":"...","username":"alice","filename":"notes.md","sizeBytes":1,"chunkCount":1,"createdAtMs":0}]}`

### `GET /api/admin/tools`

Response includes admin tool metadata, danger levels, allowed skills, and schemas.

### `GET /api/admin/tools/logs`

Returns recent tool calls across chats with chat/message references, status, error, and sanitized request/result previews.

### `POST /api/admin/skills`

Request: custom skill JSON with slug/display/prompt/tool settings.

Response: `{"skill":{...}}` including prompt/schema metadata.

### `PUT /api/admin/skills/{slug}`

Updates a custom skill.

### `DELETE /api/admin/skills/{slug}`

Disables or deletes a skill.

### `GET /api/admin/skills`

Returns all skills, including disabled skills and built-in/custom metadata. Admin responses include system prompts and output schemas.

### `GET /api/admin/skills/export`

Exports custom skills as `{"skills":[...]}`. Built-in skills are excluded.

### `POST /api/admin/skills/import`

Imports custom skills from `{"skills":[...]}`. Built-in overwrite is rejected.

### `POST /api/admin/skills/test`

Request:

```json
{"skillSlug":"default","prompt":"Test prompt"}
```

Runs an admin-only one-off model test without creating a normal user chat. Returns `503` if the model is not loaded.

Admin errors:

- `401`: no valid session.
- `403`: authenticated non-admin.
- `404`: missing skill/resource.

## OpenAI-Compatible Routes

Compatibility routes preserve MVP no-auth behavior unless API-key enforcement is explicitly enabled by the native server launcher.

### `GET /v1/models`

Aliases:

- `GET /coding/v1/models`
- `GET /conversation/v1/models`
- `GET /models`

Response:

```json
{"object":"list","data":[{"id":"local-litert-lm","object":"model","created":0,"owned_by":"local-device"}]}
```

### `POST /v1/chat/completions`

Aliases:

- `POST /coding/v1/chat/completions`
- `POST /conversation/v1/chat/completions`

Request supports OpenAI-style `messages`, optional `prompt`, and `stream`.

Streaming success: SSE chunks with OpenAI-compatible delta payloads and `[DONE]`.

Non-streaming success: OpenAI-compatible chat completion JSON.

Errors:

- `400`: malformed JSON or missing prompt/messages.
- `401`: API key required but missing/invalid.
- `503`: model not loaded.
- `500`: generation failure.

### `POST /v1/conversation/reset`

Auth: API key only when API-key enforcement is enabled.

Response: `{"status":"ok","message":"Conversation reset"}`

## Debug Routes

Routes:

- `GET /debug/routes`
- `GET /debug/perf`
- `GET /debug/perf/history`
- `GET /debug/config`
- `POST /debug/config`
- `POST /debug/benchmark`

Auth:

- `LOCAL_DEV`: public diagnostics for local development compatibility.
- `TRUSTED_LAN`: admin session required.

Debug responses expose operational diagnostics but must not expose local API keys, Hugging Face tokens, password hashes, session tokens, token hashes, or raw user file storage paths.
