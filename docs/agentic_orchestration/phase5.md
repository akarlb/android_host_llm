# Phase 5 — Skills/Tools Hardening and Controlled Extensibility

/goal

Harden the skills/tools agentic layer so it is reliable, inspectable, permissioned, and prepared for controlled extensibility. Do not implement arbitrary executable browser-created tools unless a safe backend plugin/sandbox architecture is explicitly designed and gated.

Create a new branch before making changes:

```bash
git checkout -b codex/phase5-skills-tools-hardening
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

### 1. Robust tool-call parser

Support exact JSON, fenced JSON blocks, whitespace, and safe extraction of one obvious JSON object. Reject multiple calls, unknown tools, oversized payloads, invalid args, and executable code disguised as arguments.

### 2. One-step repair loop

If a model almost emits a valid tool call, ask once for tool-call-only JSON, parse again, then fail cleanly if still invalid. Avoid infinite loops.

### 3. Stronger schema validation

Validate required fields, allowed fields only, primitive types, max string lengths, numeric ranges where defined, and reject unexpected nested JSON unless allowed. Use a small validator if no full JSON Schema dependency exists.

### 4. Tool execution tracing

Expand tool logs with request ID, chat ID, message ID, skill slug/version, raw model output if safe, parsed tool name, sanitized args, sanitized result preview, status, duration ms, error code/message, and timestamp.

### 5. Failure taxonomy

Use explicit categories: `PARSE_FAILED`, `REPAIR_FAILED`, `UNKNOWN_TOOL`, `PERMISSION_DENIED`, `INVALID_ARGUMENTS`, `EXECUTION_FAILED`, `TIMEOUT`, `SUCCESS`, `REJECTED`.

### 6. Permission enforcement

Enforce globally enabled tool, enabled skill, skill allowed-tools, chat ownership, admin-only tool restrictions, and danger level rules.

### 7. Skill versioning/snapshotting

Prevent prior chats from silently changing behavior when a skill prompt changes. Add skill versions or snapshots; at minimum record skill version/timestamp on messages/tool logs.

### 8. Strict output handling

Make `strictOutput` and `outputSchemaJson` real where possible: validate final output, retry once for schema repair, and fail clearly if invalid. Document partial schema support if necessary.

### 9. Future plugin/sandbox design

Add `docs/architecture/tool_plugin_sandbox_design.md` defining future tool manifest, sandbox boundaries, permissions, timeout, network/filesystem policy, secret handling, admin approval, and tests. Do not implement arbitrary executable tool creation without explicit approval.


## Tests and verification

Validate at minimum: valid exact/fenced/prose-wrapped tool calls parse or reject safely; unknown/disallowed tools are rejected; invalid/oversized args are rejected; successful and failed tool logs include taxonomy and duration; skill versioning/snapshot behavior works; strict output validation works or is clearly bounded; admin logs display new trace fields; normal chat still works.

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
docs/audits/current/phase5_skills_tools_hardening_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase5-skills-tools-hardening
```

