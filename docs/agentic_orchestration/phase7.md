# Phase 7 — Relay and Network-Agnostic Architecture

/goal

Future phase. Design and implement the first safe relay/network-agnostic architecture only after explicit authorization and the hard gate is satisfied. This prompt exists for roadmap completeness; do not run it now.

Create a new branch before making changes:

```bash
git checkout -b codex/phase7-relay-network-architecture
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

## Required design-first scope

Create these documents before coding:

```text
docs/architecture/relay_network_architecture.md
docs/security/relay_threat_model.md
docs/api/relay_api_contract.md
docs/testing/relay_test_plan.md
```

Cover local LAN direct mode, local Ubuntu relay mode, future cloud relay mode, device identity, pairing flow, token/session model, route exposure policy, health/reconnect model, logging/diagnostics, failure modes, and user/admin controls.

## First safe implementation slice

Preferred first slice: Phone server ↔ local Ubuntu relay ↔ browser client. Requirements: local Ubuntu relay, controlled configured URL, pairing token/shared secret, default-deny route exposure, admin relay status, health connected/disconnected, no unauthenticated debug/admin exposure, sanitized logs.

## Device identity and pairing

Add generated device ID, display name, creation timestamp, reset/regenerate control, short-lived pairing code/token, expiry, revoke pairing, and admin-visible active pairings.

## Route policy and reconnect

Default deny. Explicitly allow only needed routes. Handle phone offline, relay offline, client disconnected, phone IP changed, model unloaded, and expired sessions.

## Explicit non-goals for first relay slice

Do not implement production GCP deployment, public multi-tenant relay, caregiver notification product, remote control of arbitrary phone functions, plugin marketplace, arbitrary executable tools over relay, bypassing Android protections, or unauthenticated remote debug/admin access.


## Tests and verification

Validate at minimum: local app still works without relay; relay disabled by default; relay enabled only with explicit config; unauthorized relay access rejected; pairing expiry works; relay health visible; allowed routes work through relay; denied routes are denied through relay; logs contain no secrets; README relay instructions match implementation. If network environment is unavailable, document exact blocked tests.

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
docs/audits/current/phase7_relay_network_architecture_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase7-relay-network-architecture
```

## Hard gate before execution

This is a future prompt. Do not run it until explicitly authorized. Before implementation, verify Phases 1–6 are complete or intentionally waived. If evidence is missing, stop and write a blocker report instead of implementing relay code.

Required evidence:

- API contract exists.
- Route/auth matrix exists.
- Admin skills/tools UI exists.
- Generation reliability exists.
- Frontend baseline is stable.
- Skills/tools hardening exists.
- Local backup/diagnostics/readiness exists.
- Local smoke tests pass or blockers are documented.

