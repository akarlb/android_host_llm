# Phase 4 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase4-mainstream-frontend-parity`

## Current Behavior

- Normal chat UI supports auth, chat list, messages, Markdown upload, skills, thinking toggle, streaming, Stop/Retry from Phase 3, and admin link visibility.
- Chat list lacks search, rename, archive controls, empty states, and updated timestamps.
- Message UI lacks copy actions.
- Composer has basic Send/Stop/Retry but no shortcut hint or upload status.
- `/health` exposes model/security/storage status but the normal chat UI does not show it.
- Markdown renderer already escapes raw text/code and blocks dangerous `javascript:`, `data:`, and `vbscript:` links.

## Relevant Files Inspected

- `docs/agentic_orchestration/phase4.md`
- `docs/audits/current/phase3_generation_reliability_handoff.md`
- `app/src/main/assets/web/chat.html`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/styles.css`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/ChatRepository.kt`

## Requirements-To-Evidence Map

| Requirement | Status before implementation |
| --- | --- |
| Chat list search/filter | Missing |
| Rename/archive/delete controls | Archive backend exists; rename missing; UI missing |
| Message copy/retry | Retry exists from Phase 3; copy missing |
| Composer state/hints/upload UX | Partial |
| Model/server status banner | Missing |
| Safe Markdown rendering | Mostly implemented; duplicate JS declaration inspected and absent in current file |
| Mobile/accessibility polish | Partial |

## Implementation Slices

1. Add backend rename route.
2. Add chat search, empty states, rename/archive actions, and updated timestamps.
3. Add message copy action, upload status, shortcut hint, and model status banner.
4. Add styling for new UI elements and mobile behavior.
5. Add manual checklist and handoff docs.

## Non-Goals

- No PDF/DOCX/OCR, embeddings, heavy frontend framework, or backend message edit/delete migration.
