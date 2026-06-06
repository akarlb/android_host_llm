# Phase 2 — Admin Skills/Tools Control Center

/goal

Add a real admin control surface for skills, tools, tool permissions, tool diagnostics, and skill testing, using the existing backend concepts without introducing arbitrary executable tool creation.

Create a new branch before making changes:

```bash
git checkout -b codex/phase2-admin-skills-tools-control-center
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

### 1. Admin navigation

Upgrade `/admin` into a control center with sections for System Status, Users, Files/Storage, Skills, Tools, Tool Logs, Skill Test Console, and Diagnostics.

### 2. Skill Manager UI

Add admin UI to list all skills, distinguish built-in/custom, show enabled state, create custom skills, edit custom skills, disable/enable skills where supported, delete custom skills, confirm destructive actions, edit system prompt, description, response mode, thinking defaults, strict output, output schema JSON, tool-use mode, and allowed tools. Validate JSON before submission and show backend errors clearly.

### 3. Tool Catalog UI

Add admin UI to list tools, show name, display name, description, input schema, output schema, danger level, enabled state, and allowed skills. This is a catalog/permission interface, not arbitrary tool creation.

### 4. Tool permission matrix

Expose a Skill × Tool matrix. If editing is supported, persist through skill `allowedTools`. If tools are still hardcoded, show the matrix read-only and document the limitation.

### 5. Tool logs viewer

Show recent tool calls with timestamp, chat/message references if available, tool name, status, error, and sanitized request/result preview where available. Do not leak secrets or raw storage paths.

### 6. Skill Test Console

Add an admin-only test console to select a skill, enter a prompt, run a test safely, and show model-loaded errors clearly. Prefer a dedicated admin test chat or endpoint that does not pollute normal user chats.

### 7. Import/export skills

Implement skill export for custom skills. Implement import if straightforward with validation for slug, required fields, allowed tools, JSON schema, and built-in overwrite protection.

### Explicit non-goals

Do not allow admins to create arbitrary executable tools, shell commands, Kotlin/JS plugins, arbitrary network-calling tools, relay features, external device pairing, cloud deployment, or caregiver UI.


## Tests and verification

Validate at minimum: admin can list/create/edit/disable/delete custom skills; built-in skill behavior is safe; normal users cannot access admin skill/tool endpoints; admin can list tools; allowed-tools assignment works or is clearly read-only; invalid schema JSON is handled; admin page renders Skills, Tools, Tool Logs, and Skill Test Console sections; normal chat skill selector still works.

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
docs/audits/current/phase2_admin_skills_tools_control_center_handoff.md
```

The handoff must include: summary, files changed, routes/APIs changed, frontend changes if any, tests run, blocked tests, known limitations, and recommendation for the next phase.

Finish by reporting the branch name:

```text
codex/phase2-admin-skills-tools-control-center
```

