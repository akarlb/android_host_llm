# Route Auth Matrix

Date: 2026-06-06

Security modes:

- `LOCAL_DEV`: localhost-oriented development mode. Preserves MVP compatibility for local clients.
- `TRUSTED_LAN`: trusted Wi-Fi/LAN appliance mode. App/admin routes require session auth and debug diagnostics require admin auth.

| Route | Auth/role | `LOCAL_DEV` behavior | `TRUSTED_LAN` behavior | Data exposed | Diagnostic risk |
| --- | --- | --- | --- | --- | --- |
| `GET /` `/login` `/register` `/chat` `/files` `/styles.css` `/app.js` | Public static | Served | Served | Static web assets | Low |
| `GET /admin` | Admin page | Redirect unauthenticated; `403` non-admin; serve admin shell to admin | Same | Admin HTML shell only | Medium |
| `GET /health` | Public | Tiered health booleans and mode | Same | App/database/model/storage status, no secrets | Low |
| `GET /routes` `GET /v1` | Public | Route help | Route help | Route names/base URL | Low |
| `POST /auth/register` | Public | Creates first admin then users | Same | Raw session token on success | Medium |
| `POST /auth/login` | Public | Login with failed-attempt backoff | Same | Raw session token on success | Medium |
| `POST /auth/logout` | Authenticated | Invalidates current session | Same | Status only | Low |
| `POST /auth/logout-all` | Authenticated | Invalidates all sessions for current user | Same | Status only | Low |
| `GET /auth/session` | Optional | Current session status | Same | User id/name/role if authenticated | Low |
| `GET /api/chats` | Authenticated | User-owned chat list | Same | User chat metadata | Medium |
| `POST /api/chats` | Authenticated | Create user-owned chat | Same | Chat metadata | Medium |
| `GET /api/chats/{chatId}` | Chat owner | Own chat/messages/files; other users receive `404` | Same | User chat content | High |
| `DELETE /api/chats/{chatId}` | Chat owner | Archive own chat | Same | Status only | Medium |
| `POST /api/chats/{chatId}/messages` | Chat owner | Generate model response and persist messages | Same | Prompt, selected file context metadata, model output | High |
| `GET /api/chats/{chatId}/generations` | Chat owner | List recent generation jobs | Same | Generation status and output metadata | Medium |
| `POST /api/chats/{chatId}/generation/cancel` | Chat owner | Cancel active chat generation | Same | Generation status | Medium |
| `POST /api/chats/{chatId}/generation/retry` | Chat owner | Retry latest user message | Same | Prompt/model output | High |
| `GET /api/generations/{generationId}` | Generation owner | Read generation status | Same | Generation status/error/output | Medium |
| `POST /api/generations/{generationId}/cancel` | Generation owner | Cancel generation | Same | Generation status | Medium |
| `GET/POST /api/chats/{chatId}/files` | Chat owner | List/attach own files | Same | File metadata | Medium |
| `DELETE /api/chats/{chatId}/files/{fileId}` | Chat owner | Detach own file | Same | Status only | Low |
| `GET /api/files` | Authenticated | Own file list | Same | File metadata | Medium |
| `POST /api/files/upload` | Authenticated | Upload `.md` content up to 2 MB | Same | Uploaded text stored locally | High |
| `GET /api/files/{fileId}` | File owner | Own file metadata and chunk previews | Same | User Markdown previews | High |
| `DELETE /api/files/{fileId}` | File owner | Delete own file and chunks | Same | Status only | Medium |
| `GET /api/skills` | Authenticated | Enabled skills, prompt omitted | Same | Skill metadata | Low |
| `GET /api/skills/{slug}` | Authenticated | Skill metadata, prompt omitted | Same | Skill metadata | Low |
| `GET/PUT /api/chats/{chatId}/skill` | Chat owner | Read/update chat skill state | Same | Skill state | Medium |
| `GET /api/tools` | Authenticated | Safe tool metadata | Same | Tool display metadata | Low |
| `GET /api/chats/{chatId}/tools/logs` | Chat owner | Tool logs for own chat | Same | Tool names/status/error snippets | Medium |
| `GET /api/admin/status` | Admin | Admin-only | Admin-only | Counts, URLs, generation status, no secrets | Medium |
| `GET /api/admin/users` | Admin | Admin-only | Admin-only | User ids/names/roles/counts, no password/session fields | Medium |
| `GET /api/admin/files` | Admin | Admin-only | Admin-only | File ownership metadata, no raw storage paths | Medium |
| `GET /api/admin/tools` | Admin | Admin-only | Admin-only | Tool schemas and danger metadata | Medium |
| `GET /api/admin/tools/logs` | Admin | Admin-only | Admin-only | Sanitized tool call previews | Medium |
| `GET /api/admin/skills` | Admin | Admin-only | Admin-only | Full skill prompt/schema metadata | High |
| `GET /api/admin/skills/export` | Admin | Admin-only | Admin-only | Custom skill definitions | High |
| `POST /api/admin/skills/import` | Admin | Admin-only | Admin-only | Imports custom skill definitions | High |
| `POST /api/admin/skills/test` | Admin | Admin-only | Admin-only | Test prompt and model output | High |
| `POST/PUT/DELETE /api/admin/skills` | Admin | Admin-only skill control | Admin-only skill control | Full skill prompt/schema metadata | High |
| `GET /v1/models` `/coding/v1/models` `/conversation/v1/models` `/models` | Public unless API-key enforcement enabled | Open for MVP client compatibility | Open for trusted LAN client compatibility | Model id only | Low |
| `POST /v1/chat/completions` | Public unless API-key enforcement enabled | OpenAI-compatible generation | Same; no relay/public exposure | Prompt and model output | High |
| `POST /coding/v1/chat/completions` | Public unless API-key enforcement enabled | Coding response-mode override | Same | Prompt and model output | High |
| `POST /conversation/v1/chat/completions` | Public unless API-key enforcement enabled | Conversation response-mode override | Same | Prompt and model output | High |
| `POST /v1/conversation/reset` | API key if enforcement enabled | Reset model conversation | Same | Status/error only | Medium |
| `GET /debug/routes` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Public diagnostics | Admin required | Route list/capabilities | Medium |
| `GET /debug/perf` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Public diagnostics | Admin required | Backend/generation performance | Medium |
| `GET /debug/perf/history` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Public diagnostics | Admin required | Recent performance history | Medium |
| `GET /debug/config` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Public runtime config | Admin required | Non-secret generation config | Medium |
| `POST /debug/config` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Update local generation config | Admin required | Changes generation settings | High |
| `POST /debug/benchmark` | Public in `LOCAL_DEV`; admin in `TRUSTED_LAN` | Run benchmark if model loaded | Admin required | Prompt/result/performance data | High |

## Structured Errors And Request IDs

JSON errors include:

- Top-level `error` for existing UI/script compatibility.
- Top-level `requestId`.
- `errorDetails.code`, `errorDetails.message`, and `errorDetails.requestId`.
- `X-Request-Id` response header.

## Secret Handling

Routes must not expose:

- Password hashes or salts.
- Raw session tokens except register/login success.
- Session token hashes.
- Local server API key.
- Hugging Face token.
- Raw per-user file storage paths.

## Known Diagnostic Tradeoff

OpenAI-compatible model routes remain open by default for LAN/local client compatibility. They must not be exposed to the public internet. API-key enforcement remains available through native server configuration, but relay/cloud/cellular architecture is intentionally out of scope for Phase 1.
