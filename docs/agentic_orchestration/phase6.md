# Phase 6 — Local Deployment, Backup, and Operational Readiness

/goal

Make the phone-hosted local system durable, recoverable, maintainable, and testable for real local/trusted use before any relay/network-agnostic work begins.

Create a new branch before making changes:

```bash
git checkout -b codex/phase6-local-ops-readiness
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

### 1. Backup/export

Add admin-only export for chats, messages, uploaded file metadata/content or safe references, skills, skill state, tool settings, safe app settings, schema version, and export timestamp. Exclude password hashes, salts, sessions, token hashes, secrets, and raw storage paths unless explicitly safe. Prefer JSON bundle; use zip-like bundle only if feasible.

### 2. Import/restore

Implement import/restore if feasible. Validate schema version, required fields, ownership/user mapping, skill slugs, file type/size, and avoid importing password/session state. If full restore is too large, implement skill import/export and write a full restore design.

### 3. DB migration confidence

Document schema versions, test fresh DB creation, test upgrade from known previous versions if feasible, and ensure `onUpgrade` creates required tables/columns.

### 4. Storage scan/cleanup

Add admin maintenance to detect orphaned uploaded files, chunks, chat-file attachments, and tool logs. Cleanup must require explicit confirmation and must not silently delete user data.

### 5. Diagnostics bundle

Add sanitized admin-only diagnostics export with app/build info if available, mode, health, model loaded, DB schema version, counts, recent sanitized errors, and route matrix version. Exclude secrets and full user content unless explicitly requested in an extended export.

### 6. Admin maintenance UI

Add admin controls for backup export, import/restore if implemented, diagnostics download, storage scan, cleanup confirmation, and DB/storage status.

### 7. Local setup/testing docs

Update README/docs for fresh install, first admin bootstrap, normal user creation, model loading, LAN server start, trusted local warning, smoke tests, backup/export, restore/import if implemented, and diagnostics.

### 8. Smoke test packaging

Add/update `test_local_ops.sh` or equivalent to validate health, auth bootstrap, admin status, backup/export, diagnostics, storage scan, and existing chat/file smoke where server/model are available.


## Tests and verification

Validate at minimum: fresh DB creation, migration path if feasible, export excludes secrets, diagnostics excludes secrets, storage scan works, cleanup requires confirmation, admin-only access is enforced, normal users are denied maintenance endpoints, README commands are current, and existing chat/file/admin smoke tests are not broken.

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
docs/audits/current/phase6_local_ops_readiness_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase6-local-ops-readiness
```

