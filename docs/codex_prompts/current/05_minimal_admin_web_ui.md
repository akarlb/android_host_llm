/goal
Implement the minimal admin web UI and admin API surface for the phone-hosted AI web app MVP, following `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md` and building on the auth, chat, Markdown context, and normal user web UI slices.

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-minimal-admin-web-ui
```

## Scope

This prompt implements the minimal browser-accessible admin/operator web surface.

In scope:

- `/admin` page.
- Admin-only API endpoints.
- Model/server status display.
- User overview.
- File/storage overview.
- Links to diagnostics.
- Copyable Coding and Conversation base URLs.
- Role protection: only `ADMIN` users can access admin UI/API.

Out of scope:

- Full admin management dashboard.
- Editing/deleting users beyond minimal display unless trivial.
- Public internet hardening.
- Advanced analytics.
- NPU.
- Reworking native Android admin screen.

Do not break normal user web UI or OpenAI-compatible endpoints.

## Admin API endpoints

Add or complete:

```text
GET /api/admin/status
GET /api/admin/users
GET /api/admin/files
```

All require authenticated `ADMIN` role.

If unauthenticated: return `401`.
If authenticated but not admin: return `403`.

### GET /api/admin/status

Return:

```json
{
  "modelLoaded": true,
  "backendStatus": "Loaded with GPU",
  "serverMode": "lan",
  "lanIp": "192.168.0.164",
  "codingBaseUrl": "http://PHONE_IP:8080/coding/v1",
  "conversationBaseUrl": "http://PHONE_IP:8080/conversation/v1",
  "normalWebUrl": "http://PHONE_IP:8080/chat",
  "totalUsers": 2,
  "totalFiles": 4,
  "totalChats": 6,
  "debug": {
    "perf": "/debug/perf",
    "config": "/debug/config",
    "routes": "/debug/routes"
  }
}
```

Use existing backend status/performance helpers where possible.

### GET /api/admin/users

Return minimal user list:

```json
{
  "users": [
    {
      "id": "...",
      "username": "alice",
      "role": "ADMIN",
      "createdAtMs": 123,
      "chatCount": 2,
      "fileCount": 3
    }
  ]
}
```

Do not return password hashes, salts, session tokens, or token hashes.

### GET /api/admin/files

Return uploaded file overview across users:

```json
{
  "files": [
    {
      "id": "...",
      "username": "alice",
      "filename": "notes.md",
      "sizeBytes": 12345,
      "chunkCount": 4,
      "createdAtMs": 123
    }
  ]
}
```

Do not expose raw storage paths unless specifically required and safe.

## Admin web page

Serve `/admin`.

If unauthenticated, redirect/show link to `/login`.
If authenticated non-admin, show clear `Access denied`.
If admin, show admin dashboard.

Dashboard sections:

### System status

Display:

- Model loaded yes/no.
- Backend status.
- Server mode.
- LAN IP.
- MTP/speculative decoding status if available.
- Active generation yes/no if available.

### URLs

Show copyable URLs:

- Normal web app: `http://PHONE_IP:8080/chat`
- Coding client base URL: `http://PHONE_IP:8080/coding/v1`
- Conversation client base URL: `http://PHONE_IP:8080/conversation/v1`
- Compatibility base URL: `http://PHONE_IP:8080/v1`

### Users

Show table:

- Username.
- Role.
- Created date.
- Chat count.
- File count.

### Files/storage

Show:

- Total uploaded files.
- Total approximate storage.
- Recent files list.

### Diagnostics

Show links/buttons:

- `/debug/perf`
- `/debug/perf/history`
- `/debug/config`
- `/debug/routes`
- `/health`

Do not expose raw tokens/secrets.

### Warning

Show clear warning:

```text
This MVP is intended for trusted local networks. Do not expose this server to the public internet.
```

## Navigation

Normal user UI should show admin link only if current user role is `ADMIN`.

Admin page should include link back to `/chat`.

## Regression requirements

Must still work:

```text
/auth/*
/api/chats/*
/api/files/*
/login
/register
/chat
GET /health
GET /v1/models
POST /v1/chat/completions
POST /coding/v1/chat/completions
POST /conversation/v1/chat/completions
GET /debug/perf
POST /debug/benchmark
OPTIONS CORS/PNA
```

## Test script

Add `test_admin_ui.sh` or extend a current script.

Tests:

1. Register first user, confirm admin.
2. Register second user, confirm normal user.
3. Admin can access `/api/admin/status`.
4. Normal user cannot access `/api/admin/status`.
5. Unauthenticated request cannot access `/api/admin/status`.
6. Admin can access `/api/admin/users`.
7. Admin can access `/api/admin/files`.
8. `/admin` returns HTML for admin token/session.
9. `/admin` denies normal user.
10. Existing `/chat` and `/v1` smoke tests still work.

## Loop

Work in a loop until complete:

1. Implement.
2. Build:

```bash
./gradlew clean assembleDebug --stacktrace --info
```

3. Run tests.
4. Audit against this prompt and PRD.
5. Fix the first real failure.
6. Repeat.

Do not stop at partial success. Continue until completion criteria are met or an external blocker is documented.

## Audit

Before handoff, audit and report:

- Admin endpoints require admin role.
- Normal users cannot access admin data.
- Admin dashboard does not leak passwords, hashes, tokens, HF token, or API keys.
- Normal user UI remains usable.
- Existing model-server endpoints still work.
- No NPU was added.
- No APK/model/binary files were committed.

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-minimal-admin-web-ui` exists.
2. APK builds successfully.
3. `/admin` is served.
4. Admin can view system status.
5. Admin can view users overview.
6. Admin can view file/storage overview.
7. Normal user cannot access admin UI/API.
8. Existing normal web UI still works.
9. Existing model-server routes still work.
10. Tests/docs are added or updated.
11. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Add minimal admin web UI"
git push -u origin codex/mvp-minimal-admin-web-ui
```

If no changes remain to commit, run `git status` and report the clean state.
