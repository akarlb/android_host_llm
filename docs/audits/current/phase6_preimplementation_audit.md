# Phase 6 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase6-local-ops-readiness`

## Current Behavior

- Admin status, users, files, skills, tools, and tool logs exist.
- Custom skill import/export exists.
- No full backup export exists.
- No diagnostics bundle exists.
- No storage orphan scan or cleanup endpoint exists.
- Health reports app/database/model/storage/mode state.
- DB migrations are centralized in `AppDatabase`, currently schema version 7.
- Admin UI has diagnostics links but no maintenance controls.

## Relevant Files Inspected

- `docs/agentic_orchestration/phase6.md`
- `docs/audits/current/phase5_skills_tools_hardening_handoff.md`
- `app/src/main/java/com/example/androidhostllm/AppDatabase.kt`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/AuthRepository.kt`
- `app/src/main/java/com/example/androidhostllm/ChatRepository.kt`
- `app/src/main/java/com/example/androidhostllm/FileRepository.kt`
- `app/src/main/java/com/example/androidhostllm/SkillRepository.kt`
- `app/src/main/assets/web/admin.html`
- `app/src/main/assets/web/app.js`
- `README.md`
- `docs/api/api_contract.md`
- `docs/security/route_auth_matrix.md`
- existing `test_*.sh` scripts

## Requirements-To-Evidence Map

| Requirement | Status before implementation |
| --- | --- |
| Admin backup/export | Missing except custom skills |
| Import/restore | Custom skills only; full restore missing |
| DB migration confidence | Partial; migrations compile, no explicit schema docs |
| Storage scan/cleanup | Missing |
| Diagnostics bundle | Missing |
| Admin maintenance UI | Missing |
| Local setup/testing docs | Partial README coverage |
| Local ops smoke test | Missing |

## Implementation Slices

1. Add local ops repository for backup, diagnostics, scan, and cleanup.
2. Add admin-only routes.
3. Add admin maintenance UI controls.
4. Add smoke script and docs.
5. Run Gradle gates and APK compile.

## Non-Goals

- No Phase 7 relay/network work.
- No password/session restore.
- No full destructive restore in this phase.
