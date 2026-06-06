# Phase 3 — Generation Reliability and Failure Handling

/goal

Make local chat generation reliable, recoverable, cancellable, and testable. Move from one-shot message generation to explicit generation job state without breaking the existing chat UI, app-chat API, or OpenAI-compatible routes.

Create a new branch before making changes:

```bash
git checkout -b codex/phase3-generation-reliability
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

### 1. Generation job model

Introduce explicit generation states: `queued`, `running`, `streaming`, `completed`, `cancelled`, `failed`, `timed_out`. Persist or otherwise track job ID, chat ID, user ID, user message ID, assistant message ID if available, status, timestamps, error, and partial output. Prefer SQLite if feasible.

### 2. Cancel/stop generation

Add a cancel endpoint such as `POST /api/generations/{generationId}/cancel` or `POST /api/chats/{chatId}/generation/cancel`. Add a frontend Stop button while generation is active.

### 3. Timeout handling

Protect generation from hanging forever. Mark timed-out jobs, return clear structured errors, and keep the UI recoverable.

### 4. Retry/regenerate

Add retry/regenerate support for assistant messages, using context up to the related user message. Keep old failed/interrupted assistant messages visible or document replacement behavior.

### 5. Continue response

Add continue support if feasible for stopped/truncated/token-overflow responses. If not feasible, document as pending after retry/regenerate.

### 6. Partial response persistence

When streaming fails after partial output, preserve useful partial text where feasible, mark the job/message status, and allow retry.

### 7. Concurrency guard

Prevent model/session corruption by enforcing one active generation per app, user, or chat. If only one LiteRT-LM session is supported, queue or reject with a clear error.

### 8. Model-unloaded handling

Return clear service-unavailable errors when model is not loaded. Frontend should show a non-confusing model-unloaded state and never leave fake typing active.


## Tests and verification

Validate at minimum: streaming still works, non-streaming still works, model-unloaded error is clear, stop/cancel works, retry/regenerate works, timeout path can be simulated or tested, concurrent generation is queued/rejected clearly, partial failure does not corrupt chat history, and frontend recovers from failed generation.

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
docs/audits/current/phase3_generation_reliability_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase3-generation-reliability
```

