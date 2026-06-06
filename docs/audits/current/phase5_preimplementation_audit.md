# Phase 5 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase5-skills-tools-hardening`

## Current Behavior

- Tool calls are available through built-in tools in `ToolRegistry`.
- Skills persist prompt/tool metadata and chat skill state in SQLite.
- Tool logs exist, but only capture chat/message/tool/request/result/status/error/timestamp.
- Tool-call parsing accepts only exact JSON and silently returns null for many failure modes.
- Tool argument validation checks required/allowed fields but not primitive types, ranges, or executable-looking string values.
- Tool execution checks skill allowed tools, but does not record request ID, skill version, duration, taxonomy, or raw model preview.
- Strict output mode minifies JSON or falls back for the GDPR skill, but does not validate schema or run one repair attempt.

## Relevant Files Inspected

- `docs/agentic_orchestration/phase5.md`
- `docs/audits/current/phase4_mainstream_frontend_parity_handoff.md`
- `app/src/main/java/com/example/androidhostllm/ToolRegistry.kt`
- `app/src/main/java/com/example/androidhostllm/SkillRepository.kt`
- `app/src/main/java/com/example/androidhostllm/SkillModels.kt`
- `app/src/main/java/com/example/androidhostllm/AppDatabase.kt`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/admin.html`
- `test_skills_tools_thinking.sh`
- `docs/api/api_contract.md`
- `docs/security/route_auth_matrix.md`

## Requirements-To-Evidence Map

| Requirement | Status before implementation |
| --- | --- |
| Exact/fenced/prose JSON tool-call parsing | Exact JSON only |
| Reject multiple calls, unknown tools, oversized payloads, invalid args | Partial |
| One-step tool-call repair | Missing |
| Stronger schema validation | Missing |
| Expanded tool execution tracing | Partial |
| Failure taxonomy | Missing; old enum only `PENDING/SUCCESS/FAILED/REJECTED` |
| Permission enforcement | Partial |
| Skill versioning/snapshotting | Missing; skill timestamps exist but not recorded on logs |
| Strict output schema validation and repair | Partial |
| Future plugin/sandbox design doc | Missing |

## Implementation Slices

1. Add small JSON object schema validator.
2. Harden tool parser and tool execution statuses.
3. Expand tool-call log schema and admin log display.
4. Add one-step tool-call and strict-output repair in generation.
5. Update docs, architecture design, and manual checklist.

## Non-Goals

- No arbitrary executable browser-created tools.
- No plugin runtime or sandbox implementation in this phase.
- No relay/network exposure changes.
