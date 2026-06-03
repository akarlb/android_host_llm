/goal
Run the final full regression, hardening, and handoff pass for the phone-hosted AI web app MVP after auth, chat API, Markdown context, normal user web UI, and minimal admin web UI have been implemented.

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-final-regression-handoff
```

## Scope

This is the final integration and quality pass before handing the MVP back to the user.

In scope:

- Full regression testing.
- Fixing integration bugs across backend/frontend surfaces.
- Ensuring normal user web UI works end-to-end.
- Ensuring admin web UI works end-to-end.
- Ensuring Markdown context works end-to-end.
- Ensuring existing OpenAI-compatible endpoints still work.
- Updating README and test scripts.
- Producing a final handoff report.

Out of scope:

- New major features.
- PDF/DOCX/OCR.
- Embeddings/vector DB.
- Caregiver/check-in features.
- Public internet deployment.
- NPU.
- Major visual redesign.

## Required full-flow validation

Validate these end-to-end flows:

### Flow 1: Admin bootstrap

1. Install/run APK.
2. Start server.
3. Open `/register`.
4. Register first user.
5. Confirm first user role is `ADMIN`.
6. Open `/admin`.
7. Confirm model/server status is visible.
8. Confirm Coding and Conversation URLs are visible.

### Flow 2: Normal user registration and chat

1. Register a second user.
2. Confirm second user role is `USER`.
3. Login as normal user.
4. Open `/chat`.
5. Create new chat.
6. Send message.
7. Confirm streaming response appears progressively.
8. Refresh page.
9. Confirm chat history persists.

### Flow 3: Markdown context

1. Login as normal user.
2. Upload `.md` file.
3. Confirm file appears in file list.
4. Select file for context.
5. Ask question about file.
6. Confirm response is generated using file context.
7. Confirm original user message and assistant response are saved.
8. Delete file.
9. Confirm file disappears.

### Flow 4: Role isolation

1. Normal user cannot access `/admin`.
2. Normal user cannot call `/api/admin/status`.
3. User A cannot access User B chats.
4. User A cannot access User B files.
5. Admin can access admin status/users/files.

### Flow 5: Existing external clients

Confirm these still work:

```text
GET /health
GET /v1/models
POST /v1/chat/completions stream=false
POST /v1/chat/completions stream=true
GET /coding/v1/models
POST /coding/v1/chat/completions stream=true
GET /conversation/v1/models
POST /conversation/v1/chat/completions stream=true
OPTIONS /v1/chat/completions with CORS/PNA
GET /debug/perf
POST /debug/benchmark
```

Page Assist/coding client compatibility must not regress.

## Required test scripts

Create or update a final script:

```text
test_mvp_full_stack.sh
```

Requirements:

- Accept phone IP and port arguments.
- Print live output.
- Write Markdown results, e.g. `results_mvp_full_stack.md`.
- Use Python-based millisecond timing only. Do not use `date +%s%3N`.
- Create unique test usernames to avoid collisions.
- Test auth, chat, files, admin, streaming, CORS/PNA, and external `/v1` routes.
- Mark PASS/FAIL clearly.
- Include a final checklist.

The script should not require manual browser use, but include a manual browser checklist at the end.

## Documentation updates

Update README or docs with:

1. How to install/run APK.
2. How to start server.
3. How to open the normal web UI.
4. How to register first admin user.
5. How to register/login normal user.
6. How to upload Markdown files.
7. How file context works in MVP.
8. How to access admin UI.
9. How to use external OpenAI-compatible URLs:
   - `/v1`
   - `/coding/v1`
   - `/conversation/v1`
10. Security warning: trusted LAN only, do not expose to public internet.
11. Current limitations:
   - `.md` only.
   - No embeddings.
   - No PDF/DOCX.
   - No caregiver/check-in functionality yet.
   - No public internet hardening.
   - No NPU.

## Hardening checks

Verify:

- Passwords are not plaintext.
- Session tokens are not stored plaintext.
- User data is isolated.
- File paths are sanitized.
- Unsupported uploads fail clearly.
- Oversized uploads fail clearly.
- Debug endpoints are not shown in normal user UI.
- Admin UI does not leak secrets.
- No Hugging Face token exposure.
- No local server API key exposure in normal UI.
- No APK/model/binary files are committed.

## Loop

Work in a loop until complete:

1. Run full build:

```bash
./gradlew clean assembleDebug --stacktrace --info
```

2. Run `test_mvp_full_stack.sh`.
3. Perform code audit against PRD and this prompt.
4. Fix the first real failure.
5. Rebuild.
6. Retest.
7. Repeat until completion criteria are met.

Do not stop at partial success. Continue until all criteria below are met or a real external blocker is documented with exact failing command/log.

## Audit

Before handoff, produce a concise audit report covering:

- Backend auth status.
- Chat persistence status.
- Markdown context status.
- Normal web UI status.
- Admin web UI status.
- Role isolation status.
- External API compatibility status.
- Security caveats.
- Known limitations.
- Exact tests run.
- Build result.

Save the audit report under:

```text
docs/audits/current/mvp_full_stack_handoff_audit.md
```

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-final-regression-handoff` exists.
2. APK builds successfully.
3. Full-stack test script exists.
4. Full-stack test script passes or only documented non-blocking limitations remain.
5. Admin bootstrap works.
6. Normal user registration/login/chat works.
7. Markdown upload/context works.
8. Admin UI works and is protected.
9. Normal user cannot access admin data.
10. Existing `/v1`, `/coding/v1`, and `/conversation/v1` routes still work.
11. CORS/PNA still works.
12. README/docs are updated.
13. Final audit report exists.
14. No NPU was added.
15. No APK/model/binary files were committed.
16. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Finalize MVP full-stack regression and handoff"
git push -u origin codex/mvp-final-regression-handoff
```

If no changes remain to commit, run `git status` and report the clean state.
