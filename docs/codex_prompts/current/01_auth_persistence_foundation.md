/goal
Implement the first backend foundation for the phone-hosted AI web app MVP: local persistence, basic user registry, authentication/session handling, role foundation, and protected API middleware. This is the first vertical slice needed before the normal user web frontend and admin web frontend can be built.

Read and follow:
- `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md`

Create a new implementation branch before making code changes:

```bash
git checkout main
git pull
git checkout -b feature/web-mvp-auth-persistence
```

If the branch already exists locally, switch to it and update it from `main` carefully.

## Context

The existing project already has a working LiteRT-LM Android local model server with:

- model download/load
- GPU/MTP execution
- LAN server
- OpenAI-compatible `/v1`, `/coding/v1`, and `/conversation/v1` routes
- real SSE streaming
- CORS and Private Network Access support
- `/debug/config`, `/debug/perf`, `/debug/benchmark`

Do not break those. This prompt adds the application-layer identity and persistence foundation required for the normal user ChatGPT-like web UI and the admin web UI.

## Non-negotiables

- Do not add NPU.
- Do not change LiteRT-LM dependency/version unless required by compilation.
- Do not remove or break existing OpenAI-compatible endpoints.
- Do not re-enable mandatory auth for `/v1`, `/coding/v1`, or `/conversation/v1` during this MVP.
- Do not commit APKs, model files, binaries, or generated artifacts.
- Do not implement PDF/DOCX/OCR/embeddings/vector DB.
- Do not implement caregiver/check-in features yet.
- Passwords must not be stored in plaintext.

## Required implementation

### 1. Local persistence layer

Add a simple local persistence layer for app data.

Preferred: SQLite using Android SQLite APIs or Room if already practical. If Room adds too much build complexity, use Android SQLite directly.

Create data support for:

```text
User
Session
Chat
Message
UploadedFile
FileChunk
```

For this prompt, implement only `User` and `Session` fully. Create schema placeholders or migration-ready structure for the others if useful, but do not implement chat/file APIs yet.

### 2. User model

Fields:

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

- Username required.
- Username unique case-insensitively.
- Password required.
- First registered user becomes `ADMIN`.
- Later registered users become `USER`.
- Password stored with salt + hash, not plaintext.
- Use a reasonable built-in Java/Android crypto approach such as PBKDF2WithHmacSHA256 if available.
- Do not log passwords or password hashes.

### 3. Session model

Fields:

```text
id: String
tokenHash: String
userId: String
createdAtMs: Long
lastSeenAtMs: Long
expiresAtMs: Long?
```

Rules:

- Generate cryptographically random session tokens.
- Store only token hash.
- Return token to client on login/register.
- Also support `Authorization: Bearer <token>` for API access.
- If cookie handling is easy, also set a cookie. If not, bearer token is acceptable for MVP.
- Logout invalidates the current session.

### 4. Auth endpoints

Add endpoints to the embedded HTTP server:

```text
POST /auth/register
POST /auth/login
POST /auth/logout
GET  /auth/session
```

#### POST /auth/register

Request:

```json
{"username":"alice","password":"password123"}
```

Response:

```json
{
  "status":"ok",
  "user":{"id":"...","username":"alice","role":"ADMIN"},
  "token":"..."
}
```

Behavior:

- First user is `ADMIN`.
- Later users are `USER`.
- Duplicate username returns 409.
- Missing/weak fields return 400.

#### POST /auth/login

Request:

```json
{"username":"alice","password":"password123"}
```

Response:

```json
{
  "status":"ok",
  "user":{"id":"...","username":"alice","role":"ADMIN"},
  "token":"..."
}
```

Invalid login returns 401.

#### POST /auth/logout

Requires auth. Invalidates current session.

#### GET /auth/session

If authenticated:

```json
{"authenticated":true,"user":{"id":"...","username":"alice","role":"ADMIN"}}
```

If not authenticated:

```json
{"authenticated":false}
```

### 5. Auth middleware/helper

Create reusable helpers for protected app endpoints:

- `requireUser(session)`
- `requireAdmin(session)`
- `currentUserOrNull(session)`

Do not apply this middleware to current public model-server endpoints:

- `/health`
- `/v1/*`
- `/coding/v1/*`
- `/conversation/v1/*`
- `/debug/*`

For now, auth protects only new `/api/*` and `/auth/logout` endpoints.

### 6. Minimal admin status endpoint

Add:

```text
GET /api/admin/status
```

Admin only.

Response should include:

```json
{
  "status":"ok",
  "modelLoaded":true,
  "backendStatus":"Loaded with GPU",
  "serverMode":"lan",
  "codingBaseUrl":"/coding/v1",
  "conversationBaseUrl":"/conversation/v1",
  "totalUsers":1,
  "debug":{"perf":"/debug/perf","config":"/debug/config"}
}
```

Normal users should receive 403.
Unauthenticated users should receive 401.

### 7. Static auth placeholder pages

Only if straightforward, add minimal static placeholder pages:

```text
/login
/register
```

They can be basic HTML placeholders saying the API is ready. Full web UI comes in a later prompt.

## Loop requirements

Work in this loop until all completion criteria pass:

1. Implement one small set of changes.
2. Build with:
   ```bash
   ./gradlew clean assembleDebug --stacktrace --info
   ```
3. Fix the first real compiler/build error.
4. Run local/manual endpoint tests where possible.
5. Audit the diff against this prompt and the PRD.
6. Fix missing criteria.
7. Repeat until complete.

Do not stop at a partial compile if acceptance criteria are still missing. Continue until successful completion or a true external blocker exists.

## Minimum manual tests

Add curl examples to README or a new test doc. Verify endpoints with commands equivalent to:

```bash
curl -i -X POST http://PHONE_IP:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'

curl -i -X POST http://PHONE_IP:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'

curl -i http://PHONE_IP:8080/auth/session \
  -H "Authorization: Bearer TOKEN"

curl -i http://PHONE_IP:8080/api/admin/status \
  -H "Authorization: Bearer TOKEN"
```

## Completion criteria

This prompt is complete only when:

- New branch `feature/web-mvp-auth-persistence` exists and contains the implementation.
- APK builds successfully.
- User persistence exists.
- Session persistence exists.
- Passwords are salted and hashed.
- First registered user becomes ADMIN.
- Later registered users become USER.
- `/auth/register` works.
- `/auth/login` works.
- `/auth/logout` works.
- `/auth/session` works.
- `/api/admin/status` works for admin.
- `/api/admin/status` returns 401/403 appropriately.
- Existing `/health`, `/v1/models`, `/v1/chat/completions`, `/coding/v1`, `/conversation/v1`, `/debug/perf`, and streaming behavior still work.
- README or docs include auth test commands.
- No APK/model/binary files are committed.

## Final audit before handoff

Before handing back to the user:

1. Run `git status`.
2. Confirm only intended files changed.
3. Confirm build success.
4. Confirm acceptance criteria in a short written summary.
5. Commit changes with a clear message.
6. Push/update the branch:

```bash
git push -u origin feature/web-mvp-auth-persistence
```

If push is unavailable in the environment, leave the branch committed locally and clearly state the exact git command the user should run.
