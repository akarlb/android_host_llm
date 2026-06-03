/goal
Implement the first backend foundation for the phone-hosted AI web app MVP: local persistence, basic user registry, authentication/session handling, role foundation, and protected API middleware.

Read and follow:

- `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md`

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-auth-persistence-foundation
```

## Scope

This is the first vertical slice needed before the normal user web frontend and admin web frontend can be built.

Implement only:

- Local persistence foundation.
- User registry.
- Password hashing.
- Session token handling.
- Role foundation: `ADMIN` and `USER`.
- Auth endpoints.
- Auth helper/middleware for future protected APIs.
- Minimal docs/tests.

Do not implement chat history, file upload, Markdown context, normal web UI, or admin web UI in this branch. Do not change LiteRT-LM model loading, model download, GPU/MTP behavior, profile routes, streaming, CORS/PNA, benchmark endpoints, or Page Assist compatibility unless compilation requires a tiny mechanical adjustment.

## Required backend design

### Persistence

Use SQLite if practical. A simple Android SQLite helper is acceptable and preferred over a heavy framework unless Room is already easy.

Add entities/tables:

```text
users
- id TEXT PRIMARY KEY
- username TEXT NOT NULL
- username_normalized TEXT UNIQUE NOT NULL
- password_hash TEXT NOT NULL
- password_salt TEXT NOT NULL
- role TEXT NOT NULL
- created_at_ms INTEGER NOT NULL
- updated_at_ms INTEGER NOT NULL

sessions
- id TEXT PRIMARY KEY
- token_hash TEXT NOT NULL
- user_id TEXT NOT NULL
- created_at_ms INTEGER NOT NULL
- last_seen_at_ms INTEGER NOT NULL
- expires_at_ms INTEGER NULL
```

Rules:

- Username uniqueness is case-insensitive.
- Normalize username by trim + lowercase.
- First registered user becomes `ADMIN`.
- Later users become `USER`.
- Passwords are never stored in plaintext.
- Tokens are never stored plaintext.
- Do not log passwords, tokens, or hashes.

### Password hashing

Implement a small password hashing utility with Android/JVM crypto APIs.

Requirements:

- Per-user random salt using `SecureRandom`.
- PBKDF2 preferred, for example `PBKDF2WithHmacSHA256`.
- Base64 encode salt and hash.
- Constant-time hash comparison where practical.
- Reject empty username/password.

### Session handling

Generate random session token using `SecureRandom`.

- Store only token hash.
- Return raw token only once during login/register.
- Accept auth through `Authorization: Bearer <token>`.
- Cookie support is optional; if easy, also set an HTTP-only session cookie.
- Add helper to resolve current user from a request.
- Add helpers for `requireUser` and `requireAdmin`.

### Auth endpoints

Add:

```text
POST /auth/register
POST /auth/login
POST /auth/logout
GET  /auth/session
```

`POST /auth/register`:

```json
{"username":"alice","password":"password123"}
```

Success:

```json
{"status":"ok","user":{"id":"...","username":"alice","role":"ADMIN"},"token":"..."}
```

Errors:

- Duplicate username: `409`.
- Empty/invalid fields: `400`.

`POST /auth/login`:

- Valid credentials return user + token.
- Invalid credentials return `401`.

`POST /auth/logout`:

- Requires auth.
- Invalidates current session.

`GET /auth/session`:

- If authenticated, return current user.
- If unauthenticated, return a clear unauthenticated JSON response, not a crash.

## Regression requirements

These must still work exactly as before:

```text
GET /
GET /routes
GET /health
GET /v1/models
POST /v1/chat/completions
GET /debug/routes
GET /debug/config
POST /debug/config
GET /debug/perf
GET /debug/perf/history
POST /debug/benchmark
POST /v1/conversation/reset
/coding/v1 routes
/conversation/v1 routes
OPTIONS CORS/PNA preflight
```

Do not force auth onto `/v1`, `/coding/v1`, or `/conversation/v1` during this MVP.

## Test script

Add `test_auth_foundation.sh` or update an existing test script.

It must print to screen and write Markdown results.

Tests:

1. Register first user.
2. Confirm role `ADMIN`.
3. Register second user.
4. Confirm role `USER`.
5. Duplicate username returns `409`.
6. Login valid user succeeds.
7. Invalid password returns `401`.
8. `/auth/session` works with token.
9. `/auth/logout` invalidates session.
10. `/health`, `/v1/models`, and a basic streaming chat still work.

## Loop

Work in a loop until complete:

1. Implement.
2. Build:

```bash
./gradlew clean assembleDebug --stacktrace --info
```

3. Run relevant tests.
4. Audit against this prompt and the PRD.
5. Fix the first real failure.
6. Repeat.

Do not stop at partial success. Continue until the completion criteria below are met or there is a real external blocker. If blocked, document the exact blocker and the first failing command.

## Audit

Before handoff, audit and explicitly report:

- Passwords are not plaintext.
- Tokens are not plaintext in storage.
- First user becomes `ADMIN`.
- Later users become `USER`.
- Sessions resolve correctly.
- Logout revokes the session.
- Existing model-server endpoints still work.
- No NPU was added.
- No APK/model/binary files were committed.

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-auth-persistence-foundation` exists.
2. APK builds successfully.
3. Auth endpoints work.
4. First-user-admin behavior works.
5. Password hashing works.
6. Session handling works.
7. Existing model-server smoke tests pass.
8. Test script or documented curl test exists.
9. README/docs mention auth MVP behavior.
10. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Add local auth and persistence foundation"
git push -u origin codex/mvp-auth-persistence-foundation
```

If no changes remain to commit, still run `git status` and report the clean state.
