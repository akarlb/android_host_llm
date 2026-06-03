/goal
Implement persistent chat storage and the normal-user chat API for the phone-hosted AI web app MVP, following `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md` and building on the auth/persistence foundation from `01_auth_persistence_foundation.md`.

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-chat-storage-api
```

## Scope

Implement only the chat/session API layer needed by the normal web frontend.

In scope:

- Chat persistence.
- Message persistence.
- Authenticated chat CRUD endpoints.
- Authenticated message endpoint.
- Streaming and non-streaming responses from the app-level chat API.
- Message persistence after model response.
- User isolation: users can only access their own chats.
- Minimal tests/docs.

Out of scope for this branch:

- Markdown upload/context.
- Normal web frontend.
- Admin web frontend.
- Advanced RAG.
- PDF/DOCX/OCR.
- NPU.

Do not break existing OpenAI-compatible endpoints.

## Assumptions

The previous auth/persistence slice should provide:

- User table.
- Session table.
- Auth helpers.
- `ADMIN` and `USER` roles.
- Current user resolution from request.

If those helpers are missing or incomplete, add the minimum needed without rewriting the entire auth layer.

## Data model

Add persistent tables/entities:

```text
chats
- id TEXT PRIMARY KEY
- user_id TEXT NOT NULL
- title TEXT NOT NULL
- profile TEXT NOT NULL
- created_at_ms INTEGER NOT NULL
- updated_at_ms INTEGER NOT NULL
- archived INTEGER NOT NULL DEFAULT 0

messages
- id TEXT PRIMARY KEY
- chat_id TEXT NOT NULL
- role TEXT NOT NULL
- content TEXT NOT NULL
- created_at_ms INTEGER NOT NULL
```

Valid chat profiles:

```text
CODING
CONVERSATION
CUSTOM
```

MVP default for normal user web chat:

```text
CONVERSATION
```

Rules:

- A chat belongs to one user.
- A message belongs to one chat.
- Users cannot read/write/delete another user's chats or messages.
- Deleting a chat may soft-delete/archive it.
- Keep implementation simple.

## Endpoints

Add authenticated app-level API endpoints:

```text
GET    /api/chats
POST   /api/chats
GET    /api/chats/{chatId}
POST   /api/chats/{chatId}/messages
DELETE /api/chats/{chatId}
```

### GET /api/chats

Requires auth.

Returns current user's non-archived chats:

```json
{
  "chats": [
    {
      "id": "...",
      "title": "New chat",
      "profile": "CONVERSATION",
      "createdAtMs": 123,
      "updatedAtMs": 456
    }
  ]
}
```

### POST /api/chats

Requires auth.

Request:

```json
{
  "title": "New chat",
  "profile": "CONVERSATION"
}
```

Rules:

- `title` optional; default `New chat`.
- `profile` optional; default `CONVERSATION`.
- Invalid profile returns `400`.

### GET /api/chats/{chatId}

Requires auth.

Returns chat metadata and messages:

```json
{
  "chat": {
    "id": "...",
    "title": "New chat",
    "profile": "CONVERSATION",
    "createdAtMs": 123,
    "updatedAtMs": 456
  },
  "messages": [
    {"id":"...","role":"user","content":"Hello","createdAtMs":123},
    {"id":"...","role":"assistant","content":"Hi","createdAtMs":124}
  ]
}
```

If chat does not belong to current user, return `404` or `403`. Prefer `404` to avoid leaking existence.

### POST /api/chats/{chatId}/messages

Requires auth.

Request:

```json
{
  "content": "Explain this error.",
  "stream": true,
  "fileIds": []
}
```

For this branch, `fileIds` may be accepted but ignored or validated as empty. Markdown context is implemented in the next prompt.

Behavior:

1. Verify chat belongs to current user.
2. Save the user message.
3. Send the message to LiteRT-LM using the chat's effective profile.
4. If `stream=false`, return assistant message JSON.
5. If `stream=true`, stream assistant response as SSE.
6. Persist the assistant message after generation completes.
7. Update chat `updated_at_ms`.

Streaming format:

- Use existing SSE style where possible.
- Include `data:` chunks.
- End with `data: [DONE]`.

If generation fails:

- Store user message.
- Do not store a fake assistant success message.
- Return clear error or SSE error event.

### DELETE /api/chats/{chatId}

Requires auth.

Soft-delete/archive chat.

Response:

```json
{"status":"ok"}
```

## Model generation behavior

Use existing LiteRT-LM manager and existing profile system.

For normal `/api/chats` requests:

- Default profile should be `CONVERSATION`.
- Chat profile can override if set to `CODING` or `CUSTOM`.
- Do not force auth onto `/v1`, `/coding/v1`, or `/conversation/v1`.

## Regression requirements

Existing endpoints must still work:

```text
/auth/*
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

Add `test_chat_api.sh` or extend the previous auth test script.

Tests:

1. Register/login a user.
2. Create chat.
3. List chats.
4. Get chat.
5. Send non-streaming message.
6. Confirm user and assistant messages are persisted.
7. Send streaming message.
8. Confirm `[DONE]` appears.
9. Confirm assistant streaming response is persisted.
10. Delete/archive chat.
11. Confirm deleted chat no longer appears.
12. Confirm unauthenticated access returns `401`.
13. Confirm User A cannot access User B chat.
14. Confirm existing `/v1` smoke test still works.

The script must print live output and write Markdown results.

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

Do not stop at partial success. Continue until the completion criteria below are met or a real external blocker is found.

## Audit

Before handoff, audit and report:

- Auth is required for `/api/chats`.
- Users can only access own chats.
- User and assistant messages are persisted.
- Streaming API saves final assistant message.
- Existing model-server endpoints still work.
- No Markdown/file upload was added in this branch unless required.
- No NPU was added.
- No APK/model/binary files were committed.

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-chat-storage-api` exists.
2. APK builds successfully.
3. Chat tables/entities exist.
4. Chat CRUD endpoints work.
5. Message endpoint works non-streaming.
6. Message endpoint works streaming with `[DONE]`.
7. Messages persist.
8. User isolation is enforced.
9. Existing `/v1` and profile routes still pass smoke tests.
10. Tests/docs are added or updated.
11. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Add persistent chat API for web MVP"
git push -u origin codex/mvp-chat-storage-api
```

If no changes remain to commit, run `git status` and report the clean state.
