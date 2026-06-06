# Phase 1 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase1-api-security-foundation`

## Current Behavior

- `LocalHttpServer` serves static pages, auth routes, app APIs, admin APIs, skills/tools APIs, debug routes, health, and OpenAI-compatible `/v1`, `/coding/v1`, and `/conversation/v1` routes.
- Auth supports register, login, logout, and session lookup. First user becomes `ADMIN`; later users become `USER`.
- Passwords are PBKDF2 hashed with per-user salts. Session tokens are stored as SHA-256 hashes.
- Session rows have `created_at_ms`, `last_seen_at_ms`, and nullable `expires_at_ms`, but new sessions do not set expiry and idle timeout is not enforced.
- Login has no failed-attempt throttle or backoff.
- App/admin API errors usually return `{"error":"..."}` and do not include request IDs. Response headers do not include `X-Request-Id`.
- `/health` returns only `status`, `modelLoaded`, and string server mode.
- LAN/local behavior is represented by string modes `lan` and `localhost`; explicit `LOCAL_DEV` and `TRUSTED_LAN` security modes do not exist.
- Debug routes are public, and OpenAI-compatible routes are unauthenticated unless native server API-key mode is enabled.
- Skills/tools routes exist on current `main` and must be included in docs.

## Relevant Files Inspected

- `README.md`
- `docs/prd/current/phone_hosted_ai_web_app_mvp_prd.md`
- `docs/audits/current/mvp_full_stack_handoff_audit.md`
- `docs/agentic_orchestration/phase1.md`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/AuthRepository.kt`
- `app/src/main/java/com/example/androidhostllm/AuthModels.kt`
- `app/src/main/java/com/example/androidhostllm/AppDatabase.kt`
- `app/src/main/java/com/example/androidhostllm/AppPreferences.kt`
- `app/src/main/java/com/example/androidhostllm/ServerAuth.kt`
- `app/src/main/java/com/example/androidhostllm/LocalServerService.kt`
- `app/src/main/java/com/example/androidhostllm/MainActivity.kt`
- `app/src/main/java/com/example/androidhostllm/ChatRepository.kt`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/*.html`
- `test_auth_foundation.sh`
- `test_mvp_full_stack.sh`
- Other root smoke scripts were inventoried for syntax/check coverage.

## Requirements-To-Evidence Map

| Requirement | Evidence | Status |
| --- | --- | --- |
| API contract for auth, chats, files, skills, tools, admin, health, debug, `/v1`, `/coding/v1`, `/conversation/v1` | No `docs/api/openapi.yaml` or equivalent exists | Missing |
| Explicit `LOCAL_DEV` and `TRUSTED_LAN` modes | Existing UI/server uses string `localhost`/`lan`; API-key flag remains separate | Missing |
| Route/auth matrix | No `docs/security/route_auth_matrix.md` exists | Missing |
| Absolute session expiry | DB column exists but sessions store null expiry | Partial |
| Idle timeout | `last_seen_at_ms` is updated but not checked | Partial |
| Reliable logout | Current-token logout exists | Implemented |
| Logout-all-current-user | No route/repository method | Missing |
| Failed-login backoff | No attempt tracker | Missing |
| Structured app/admin API errors | Inconsistent `{"error":"..."}` responses | Partial |
| Request IDs | No request ID header or error field | Missing |
| Tiered health | Shallow `/health` only | Partial |
| Preserve MVP auth/chat/admin flows | Existing scripts expect legacy top-level `error`, `token`, and `user` fields | Must preserve |
| No relay/network architecture | No relay code present | In scope to avoid |

## Route/API/UI/Database Areas Affected

- `LocalHttpServer`: request context, security mode gating, error envelopes, request ID headers, health response, logout-all route, auth/login handling.
- `AuthRepository` and `AuthModels`: session expiry/idle timeout, logout-all-current-user, failed-login throttle/backoff result.
- `AppDatabase`: session schema already has needed columns; no migration expected unless code needs indexes.
- `MainActivity` and `LocalServerService`: map bind mode to explicit security mode without adding relay/cloud modes.
- `app.js`: should continue reading `body.error`; no required frontend change if legacy-compatible errors are preserved.
- Docs: API contract, route/auth matrix, README, handoff.

## Risks

- Changing the JSON error shape could break static frontend and scripts. Keep top-level `error` while adding structured `errorDetails`.
- Strict `TRUSTED_LAN` behavior could break MVP OpenAI-compatible local clients. Keep generation compatibility in `LOCAL_DEV`; document and limit stricter behavior to admin/debug diagnostics where possible.
- Session expiry tests are hard to live-run without a phone server. Add script coverage for response headers and route behavior, but record live checks as environment-dependent.
- APK compile may be blocked if Gradle is still unavailable in this environment.

## Implementation Slices

1. Add security/session primitives: explicit mode enum, session absolute/idle expiry, logout-all-current-user, failed-login backoff.
2. Add request context and structured error helpers with `X-Request-Id` while preserving top-level `error`.
3. Expand `/health` and route/security behavior for debug/admin by mode.
4. Add docs: API contract, route/auth matrix, README updates.
5. Add/update shell checks for request IDs, health fields, logout-all, and failed-login backoff where feasible.
6. Run `git diff --check`, script syntax checks, Gradle compile gate, completion audit, and handoff.

## Test Plan

- `bash -n` all root test scripts.
- `git diff --check`.
- Add static/script coverage for Phase 1-specific API expectations where practical.
- Run `./gradlew clean assembleDebug`.
- Prefer `./gradlew test`, `./gradlew lint`, and `./gradlew check` if available after the compile gate.
- Record live server checks as skipped if no running phone server/model-loaded environment exists.

## Non-Goals

- No relay, cloud, public internet, cellular, OAuth, password reset, email verification, or caregiver/check-in work.
- No new model-serving architecture.
- No broad frontend redesign.
