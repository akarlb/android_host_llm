/goal
Implement Markdown file upload, chunking, file management, and deterministic context injection for the normal-user chat API, following `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md` and building on the auth and chat API prompt slices.

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-markdown-upload-context
```

## Scope

This prompt adds the MVP file/context layer.

In scope:

- Authenticated `.md` file upload.
- Per-user file ownership.
- Safe file storage in app-specific storage.
- Markdown chunking.
- File listing, preview, and delete.
- Selected file context injection into `/api/chats/{chatId}/messages`.
- Context budget limits.
- Tests/docs.

Out of scope:

- PDF, DOCX, OCR.
- Embeddings.
- Vector DB.
- Semantic retrieval.
- Full RAG.
- Frontend UI.
- Admin web UI.
- NPU.

Do not break existing auth, chat API, model-server, `/v1`, `/coding/v1`, `/conversation/v1`, streaming, CORS/PNA, or Page Assist behavior.

## Data model

Add persistent entities/tables:

```text
uploaded_files
- id TEXT PRIMARY KEY
- user_id TEXT NOT NULL
- original_filename TEXT NOT NULL
- safe_filename TEXT NOT NULL
- mime_type TEXT NOT NULL
- size_bytes INTEGER NOT NULL
- storage_path TEXT NOT NULL
- created_at_ms INTEGER NOT NULL

file_chunks
- id TEXT PRIMARY KEY
- file_id TEXT NOT NULL
- chunk_index INTEGER NOT NULL
- heading_path TEXT NULL
- content TEXT NOT NULL
- char_count INTEGER NOT NULL
- created_at_ms INTEGER NOT NULL
```

Rules:

- Files belong to exactly one user.
- Users can only list/read/delete their own files.
- Deleting a file deletes chunks and stored file.
- Filenames must be sanitized.
- Reject path traversal.
- Store files in app-specific storage, under a per-user directory if practical.

## Upload constraints

MVP accepts only Markdown:

```text
.md
text/markdown
text/plain with .md extension is acceptable
```

Reject:

- `.pdf`
- `.docx`
- images
- binary files
- unknown extensions

Size limit:

- Default max size: 2 MB.
- If there is already a config mechanism, expose max size there only if simple.
- Return clear `413` or `400` for oversized files.

Encoding:

- Assume UTF-8.
- If decoding fails, return clear error.

## Endpoints

Add authenticated endpoints:

```text
GET    /api/files
POST   /api/files/upload
GET    /api/files/{fileId}
DELETE /api/files/{fileId}
```

### GET /api/files

Returns current user's uploaded files:

```json
{
  "files": [
    {
      "id": "...",
      "filename": "notes.md",
      "sizeBytes": 12345,
      "chunkCount": 4,
      "createdAtMs": 123
    }
  ]
}
```

### POST /api/files/upload

Multipart upload preferred.

If multipart is difficult with NanoHTTPD, acceptable MVP fallback:

```json
{
  "filename": "notes.md",
  "content": "# Notes\n..."
}
```

If using JSON fallback, document it clearly and make frontend upload use it.

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

### GET /api/files/{fileId}

Returns metadata and chunk previews:

```json
{
  "file": {...},
  "chunks": [
    {"chunkIndex":0,"headingPath":"# Intro","charCount":1000,"preview":"..."}
  ]
}
```

Do not return massive full content by default if file is large. MVP may include chunks if limited.

### DELETE /api/files/{fileId}

Deletes file and chunks.

Response:

```json
{"status":"ok"}
```

## Markdown chunking

Implement deterministic chunking:

1. Read Markdown text.
2. Split by headings if possible: `#`, `##`, `###`.
3. Preserve heading path.
4. If section too large, split into fixed-size chunks.
5. Target chunk size: 2,000–4,000 characters.
6. Store chunks in order.

No embeddings. No semantic retrieval.

## Context injection

Extend:

```text
POST /api/chats/{chatId}/messages
```

Request already supports or should now support:

```json
{
  "content": "What does this file say?",
  "stream": true,
  "fileIds": ["file_1", "file_2"]
}
```

Behavior:

1. Verify chat belongs to user.
2. Verify each file belongs to user.
3. Load chunks for selected files.
4. Build deterministic context block.
5. Respect context budget.
6. Prepend context to the user prompt before sending to LiteRT-LM.
7. Save original user message, not the expanded context prompt, unless there is a separate hidden/system field.
8. Save assistant response normally.

Context budget:

- Add a conservative char budget, for example 12,000–16,000 chars.
- If selected files exceed budget, include chunks in deterministic order until budget is reached.
- Return metadata/warning in non-streaming response if context was truncated.
- For streaming, warning may be sent as metadata before or after response if easy; otherwise expose in chat/message metadata later.

Prompt wrapper:

```text
You may use the following uploaded Markdown context.

[File: notes.md]
[Chunk 1: # Heading]
...
[/Chunk]
[/File]

User question:
...
```

## Security and privacy

- Auth required for all `/api/files` routes.
- User can only access own files.
- Admin cross-user file listing is not required here.
- Do not expose raw storage paths in normal user responses.
- Sanitize filenames.
- Reject `../` and path traversal.
- Do not log uploaded file contents unless debug logging is explicitly enabled.

## Regression requirements

Must still work:

```text
/auth/*
/api/chats/*
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

Add `test_markdown_context.sh` or extend a prior script.

Tests:

1. Register/login user.
2. Upload valid `.md` file.
3. Confirm file list includes it.
4. Confirm file detail returns chunk metadata.
5. Reject non-md upload.
6. Reject oversized file if practical.
7. Create chat.
8. Send message with selected file ID non-streaming.
9. Send message with selected file ID streaming.
10. Confirm assistant response is produced.
11. Confirm messages persist.
12. Delete file.
13. Confirm deleted file is gone.
14. Confirm User A cannot access User B file.
15. Existing `/v1` smoke test still works.

Script must print live output and write Markdown results.

## Loop

Work in a loop until complete:

1. Implement.
2. Build:

```bash
./gradlew clean assembleDebug --stacktrace --info
```

3. Run tests.
4. Audit against this prompt and the PRD.
5. Fix the first real failure.
6. Repeat.

Do not stop at partial success. Continue until completion criteria are met or an external blocker is documented.

## Audit

Before handoff, audit and report:

- Only `.md` files accepted.
- Files are user-owned.
- Users cannot access other users' files.
- Chunking works.
- Context injection affects chat generation.
- Original user message is stored separately from expanded context.
- Existing model-server endpoints still work.
- No embeddings/vector DB were added.
- No NPU was added.
- No APK/model/binary files were committed.

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-markdown-upload-context` exists.
2. APK builds successfully.
3. File upload works for `.md`.
4. File list/detail/delete work.
5. Markdown chunking works.
6. Selected file context is injected into chat generation.
7. User isolation is enforced.
8. Existing chat and model-server smoke tests pass.
9. Tests/docs are added or updated.
10. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Add markdown upload and context injection"
git push -u origin codex/mvp-markdown-upload-context
```

If no changes remain to commit, run `git status` and report the clean state.
