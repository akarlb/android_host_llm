# Phase 1 — API, Security, and Product Foundation

/goal

Bring the backend/API foundation of `android_host_llm` to a stable, contract-driven, security-aware product baseline before additional UI or agentic features are added. This phase is local/trusted hardening only; do not build relay/network architecture.

Create a new branch before making changes:

```bash
git checkout -b codex/phase1-api-security-foundation
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

### 1. API contract

Add `docs/api/openapi.yaml` if feasible; otherwise add `docs/api/api_contract.md`. Cover auth, chats, files, skills, tools, admin, health, debug, `/v1`, `/coding/v1`, and `/conversation/v1` routes. For every route, document method, auth/role requirement, request schema, response schema, error responses, and mode-specific behavior.

### 2. Security modes

Introduce explicit `LOCAL_DEV` and `TRUSTED_LAN` modes. Preserve MVP local compatibility in `LOCAL_DEV`, but make `TRUSTED_LAN` stricter. Do not implement relay/cloud/cellular modes.

### 3. Route/auth matrix

Create `docs/security/route_auth_matrix.md`. For each route, state public/authenticated/admin-only, LOCAL_DEV behavior, TRUSTED_LAN behavior, data exposed, and diagnostic risk. Fix code/docs mismatches.

### 4. Session lifecycle

Add absolute session expiry, idle timeout, reliable logout, and logout-all-current-user if feasible. Avoid breaking login/register/session frontend flow.

### 5. Failed-login backoff

Add minimal local failed-login throttle/backoff by normalized username and/or remote address where available. Reset after success. Document limits.

### 6. Structured errors and request IDs

Add a consistent error envelope for app/admin APIs, include a request ID in error JSON and an `X-Request-Id` response header. Preserve compatibility routes where needed.

### 7. Tiered health

Expand `/health` or add a nearby route to report app alive, database available, model loaded, storage writable, and configured mode without exposing secrets.

### 8. Documentation

Update README, API contract, route matrix, and Phase 1 handoff.


## Tests and verification

Validate at minimum: registration, first-admin bootstrap, normal login, session endpoint, session expiry behavior, admin APIs admin-only, normal users denied admin APIs, debug/admin behavior by security mode, representative structured errors, request ID headers, tiered health output, and no regression in existing smoke scripts.

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
docs/audits/current/phase1_api_security_foundation_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase1-api-security-foundation
```

