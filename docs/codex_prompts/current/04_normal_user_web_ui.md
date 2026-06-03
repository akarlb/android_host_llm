/goal
Implement the normal user ChatGPT-like web UI for the phone-hosted AI web app MVP, following `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md` and building on auth, chat API, and Markdown context API slices.

Create a new implementation branch before changing code:

```bash
git checkout main
git pull
git checkout -b codex/mvp-normal-user-web-ui
```

## Scope

This prompt implements the first normal-user browser UI served by the Android app.

In scope:

- Static web asset serving if not already present.
- Login page.
- Register page.
- ChatGPT-like chat page.
- Chat list/sidebar.
- Streaming assistant response display.
- Markdown file upload UI.
- File list and file selection for chat context.
- Logout.
- Basic role-aware navigation.

Out of scope:

- Full admin dashboard.
- Polished design system.
- PDF/DOCX/OCR.
- Embeddings/vector DB.
- Caregiver/check-in UX.
- Public internet hardening.
- NPU.

Do not break existing model-server endpoints or external tool routes.

## Frontend delivery

Prefer simple static assets bundled with the Android app.

Suggested structure:

```text
app/src/main/assets/web/
├── index.html
├── login.html
├── register.html
├── chat.html
├── files.html       optional if files are separate
├── styles.css
└── app.js
```

If the project already has a different static asset path, follow the existing pattern.

Serve routes:

```text
/login
/register
/chat
/files
```

If direct HTML route mapping is difficult, serve one `index.html` SPA fallback and handle client-side navigation.

## UX requirements

### Public unauthenticated pages

#### Login

Fields:

- Username.
- Password.
- Login button.
- Link to Register.

Behavior:

- Calls `POST /auth/login`.
- Stores returned token in browser storage for MVP.
- On success, navigates to `/chat`.
- Shows clear inline error on failure.

#### Register

Fields:

- Username.
- Password.
- Confirm password.
- Register button.
- Link to Login.

Behavior:

- Calls `POST /auth/register`.
- Stores returned token in browser storage for MVP.
- On success, navigates to `/chat`.
- Shows duplicate username and validation errors clearly.

### Chat page

Layout should be familiar and simple:

```text
Header: app name, current user, logout
Sidebar: New chat, chat list, files button/panel
Main: messages, streaming assistant response, input box, send button
Context area: selected Markdown files
```

Minimum features:

- Load current session from `/auth/session`.
- Redirect unauthenticated users to `/login`.
- Create new chat.
- List existing chats.
- Open chat.
- Show messages.
- Send message.
- Stream assistant response progressively.
- Save/reload chat history through API.
- Upload `.md` file.
- List uploaded files.
- Select one or more files for context.
- Show selected files near prompt box.
- Logout.

### Streaming display

Use `fetch` streaming if possible.

Requirements:

- Display assistant content progressively.
- Handle `data:` SSE events.
- Stop on `data: [DONE]`.
- Show error if stream fails.
- Do not wait for full answer before displaying.

If browser streaming parsing is difficult, implement a fallback non-streaming call but mark it clearly in code/comments. First attempt must be true streaming.

### Files UI

MVP may be a panel inside chat rather than a separate polished page.

Features:

- Upload `.md` file.
- Show uploaded files.
- Show filename, size, chunk count if API provides it.
- Select/unselect file for current chat.
- Delete file.
- Clear error for unsupported file types.

### Role-aware navigation

If current user role is `ADMIN`, show link to `/admin`.

If `USER`, do not show admin link.

Do not implement full admin UI in this prompt.

## API usage

Use app-level APIs:

```text
POST /auth/register
POST /auth/login
POST /auth/logout
GET  /auth/session
GET  /api/chats
POST /api/chats
GET  /api/chats/{chatId}
POST /api/chats/{chatId}/messages
DELETE /api/chats/{chatId}
GET  /api/files
POST /api/files/upload
GET  /api/files/{fileId}
DELETE /api/files/{fileId}
```

Do not use raw `/v1/chat/completions` from the normal web UI. The normal web UI should use `/api/chats/{chatId}/messages` so history and file context are handled by the backend.

## Styling

Keep the style clean and functional.

MVP requirements:

- Responsive enough for desktop browser and phone browser.
- Chat messages visually separated.
- User and assistant roles clear.
- Streaming response visibly updates.
- Files/context selection clear.
- Errors visible.

Avoid heavy frontend frameworks unless already present. Vanilla JS is acceptable and preferred for MVP.

## Security constraints

- Do not expose debug endpoints in normal user navigation.
- Do not expose Hugging Face token.
- Do not expose local server API key.
- Do not show raw model path to normal user.
- Use authenticated API requests.
- Store token simply for MVP but isolate all future security TODOs clearly.

## Regression requirements

Must still work:

```text
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

Add `test_web_ui_smoke.sh` or update current scripts.

Tests:

1. GET `/login` returns HTML.
2. GET `/register` returns HTML.
3. GET `/chat` returns HTML or redirects appropriately.
4. Register user through API.
5. Login through API.
6. Create chat through API.
7. Upload `.md` file through API.
8. Send chat message with file context through API.
9. Confirm streaming response includes `[DONE]`.
10. Existing `/v1` smoke test still works.

Browser manual checklist:

- Register in browser.
- Login in browser.
- Create chat.
- Send message.
- See streaming response.
- Upload `.md`.
- Select file.
- Ask about file.
- Logout.

## Loop

Work in a loop until complete:

1. Implement.
2. Build:

```bash
./gradlew clean assembleDebug --stacktrace --info
```

3. Run API/web smoke tests.
4. Manually audit the frontend flow where possible.
5. Fix the first real failure.
6. Repeat.

Do not stop at partial success. Continue until completion criteria are met or a real external blocker is documented.

## Audit

Before handoff, audit and report:

- Normal user can register/login.
- Normal user can access chat UI.
- Normal user can send messages.
- Streaming renders progressively.
- `.md` upload UI works.
- File selection is sent to chat message API.
- Normal user does not see admin/debug controls.
- Existing OpenAI-compatible endpoints still work.
- No NPU was added.
- No APK/model/binary files were committed.

## Completion criteria

This prompt is complete only when:

1. Branch `codex/mvp-normal-user-web-ui` exists.
2. APK builds successfully.
3. `/login`, `/register`, and `/chat` are served.
4. Browser user can register/login.
5. Browser user can create/open chat.
6. Browser user can send message and see streaming response.
7. Browser user can upload/select `.md` file.
8. Chat with selected file context works through API/UI.
9. Normal user UI hides admin/debug controls.
10. Existing model-server routes still pass smoke tests.
11. Tests/docs are added or updated.
12. Changes are committed and pushed.

Finish by updating the branch and handing over:

```bash
git status
git add .
git commit -m "Add normal user web chat UI"
git push -u origin codex/mvp-normal-user-web-ui
```

If no changes remain to commit, run `git status` and report the clean state.
