# Phase 4 — Mainstream Frontend Parity

/goal

Bring the normal browser chat frontend closer to mainstream chat UI expectations while preserving the lightweight local-first architecture. Focus on usability, message actions, chat management, upload status, model status, accessibility, mobile behavior, and safe rendering.

Create a new branch before making changes:

```bash
git checkout -b codex/phase4-mainstream-frontend-parity
```

If the branch already exists, switch to it and update it from the current working branch before proceeding.


## Operating method

Use the usual agentic Codex loop:

1. Start by creating/switching to the requested branch.
2. Audit the existing codebase, PRD/docs, tests, and recent implementation before writing code.
3. Produce an internal requirements-to-evidence map.
4. Implement in small slices.
5. After each slice, run relevant tests/checks, inspect changed files, and repair gaps.
6. Do not claim completion without concrete evidence.
7. If a test is blocked by environment, record the exact command and exact blocker.
8. Update docs/handoff before final response.
9. Run `git status` and `git diff --check` before handoff.
10. Finish by updating the branch with all final changes before handing over.

Do not implement Phase 7 relay/network work unless the phase prompt explicitly says to and the hard gate is satisfied.


## Source-of-truth hierarchy

Use this order:

1. Current codebase behavior.
2. Current PRD/docs/audits in the repo.
3. Existing regression scripts.
4. Previous completed phase handoff docs, if present.
5. This prompt.
6. External documentation only when needed for current API/security/runtime conventions; record sources in the handoff.

Do not let generic external docs override the local product concept.

## Audit-first requirements

Before implementation, inspect the relevant Kotlin backend, repositories, database migrations, static web assets, tests/scripts, README, and current docs. Build a requirements-to-evidence map that identifies implemented, partial, missing, weakly tested, stale, and out-of-scope items.

## Required implementation scope

### 1. Chat list management

Add rename chat, archive/delete where supported, search/filter chats, empty states, active highlighting, and updated timestamps where available. Add minimal backend routes if needed.

### 2. Message actions

Add copy message, retry/regenerate assistant response if Phase 3 exists, edit user message if feasible, and delete message if feasible. If edit/delete is too large, implement copy + retry and document the gap.

### 3. Composer improvements

Add clear send/stop state, keyboard shortcut hint, better disabled state, paste/large input handling, optional auto-resize, clear file chips, and remove attachment controls.

### 4. File upload UX

Improve upload progress/status, file type rejection, oversized-file message, success message, attached-file visibility, and per-message context indicator if metadata exists. Do not add PDF/DOCX/OCR support unless already implemented.

### 5. Model/server status banner

Show server reachable, model loaded/unloaded, security mode if available, and active generation status. Do not expose secrets.

### 6. Safe Markdown rendering

Harden the current renderer or introduce a safe parser/sanitizer consistent with repo constraints. Block `javascript:`, `data:`, and raw HTML injection; escape code blocks; tolerate malformed Markdown and long outputs.

### 7. Mobile and accessibility polish

Improve mobile sidebar/composer/message layout, touch targets, labels, aria-live regions, button labels, focus states, keyboard reachability, and error roles.

### 8. Visual consistency

Keep the UI lightweight but product-like: consistent spacing, clear panels, useful empty states, no overlapping controls, and no raw debug dumps in normal user UI.


## Tests and verification

Validate at minimum: authenticated chat loads, chat list renders and filters, rename/archive/delete works if implemented, copy action works, retry action works if Phase 3 exists, upload status appears, invalid uploads show clear errors, model-unloaded banner appears, dangerous Markdown links are sanitized, mobile layout is usable, admin link visibility still works, and skills/thinking UI still works. Add `docs/testing/phase4_frontend_manual_test_checklist.md` if browser automation is unavailable.

## Completion criteria

This phase is complete only when:

1. The requested branch exists and contains the work.
2. The implementation matches this prompt and the current source of truth.
3. Existing MVP flows are not broken unless explicitly replaced and documented.
4. New/updated tests or manual checklists cover the implemented behavior.
5. All feasible checks have been run.
6. Blocked checks are documented with exact commands and blockers.
7. Documentation and handoff are updated.
8. `git diff --check` passes.
9. `git status` is reviewed.
10. The branch is updated with final changes before handoff.

## Handoff requirements

Create or update:

```text
docs/audits/current/phase4_mainstream_frontend_parity_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase4-mainstream-frontend-parity
```

