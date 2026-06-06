# Phase 2 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase2-admin-skills-tools-control-center`

## Current Behavior

- `/admin` showed system status, URLs, users, files/storage, and diagnostics.
- Backend had public authenticated skills endpoints, admin create/update/delete skill endpoints, admin tools metadata, and per-chat tool logs.
- There was no admin endpoint to list all skills including disabled/built-in/custom with prompts.
- There was no global admin tool-log endpoint.
- There was no skill test endpoint, custom skill export/import endpoint, or admin UI for skill/tool management.
- Tool definitions are hardcoded in `ToolRegistry`; arbitrary executable tool creation is not supported and remains out of scope.

## Relevant Files Inspected

- `docs/agentic_orchestration/phase2.md`
- `docs/audits/current/phase1_api_security_foundation_handoff.md`
- `docs/api/api_contract.md`
- `docs/security/route_auth_matrix.md`
- `app/src/main/assets/web/admin.html`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/styles.css`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/SkillRepository.kt`
- `app/src/main/java/com/example/androidhostllm/SkillModels.kt`
- `app/src/main/java/com/example/androidhostllm/ToolRegistry.kt`
- `test_admin_ui.sh`
- `test_skills_tools_thinking.sh`

## Requirements-To-Evidence Map

| Requirement | Existing evidence | Status before implementation |
| --- | --- | --- |
| Admin control center sections | Admin page has status/users/files/diagnostics only | Partial |
| Skill manager UI | No admin skill form/table | Missing |
| Tool catalog UI | Admin tools endpoint exists; no UI | Partial |
| Tool permission matrix | Skill allowedTools and tool allowedForSkills exist; no UI | Partial |
| Tool logs viewer | Per-chat logs exist; no global admin logs | Partial |
| Skill test console | No dedicated endpoint/UI | Missing |
| Import/export custom skills | No endpoint/UI | Missing |
| No arbitrary executable tool creation | Tools are hardcoded | Implemented/non-goal preserved |

## Risks

- Built-in skill editing could corrupt seeded defaults. Limit built-in admin changes to enabled state.
- Tool logs can contain request/result JSON. Sanitize previews and avoid raw storage paths/secrets.
- Skill test generation requires a loaded model; the UI and endpoint must show `503` clearly when unloaded.
- Admin UI should remain compatible with Phase 1 `TRUSTED_LAN` debug gating.

## Implementation Slices

1. Add admin backend endpoints for all-skills list, tool logs, skill test, export, and import.
2. Add admin UI sections and forms for skills/tools/logs/matrix/test/import-export.
3. Update styling for dense admin controls.
4. Add or update tests/docs/handoff.
5. Run script syntax checks, Gradle checks, and APK compile gate.

## Test Plan

- `git diff --check`.
- `bash -n` for root shell scripts.
- `./gradlew clean assembleDebug` with explicit local Gradle/SDK paths.
- `./gradlew test`, `./gradlew lint`, and `./gradlew check`.
- Live admin/skills tests are skipped unless a phone server with fresh app data and loaded model is running.

## Non-Goals

- No arbitrary executable tools, shell commands, plugins, network-calling tools, relay, cloud, caregiver UI, or external device pairing.
