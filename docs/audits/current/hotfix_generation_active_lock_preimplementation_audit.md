# Hotfix Generation Active Lock Preimplementation Audit

Date: 2026-06-07

Branch: `codex/hotfix-generation-active-lock`

Base branch: `codex/orchestration-phases-1-7`

## Files Inspected

- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/LiteRtLmManager.kt`
- `app/src/main/java/com/example/androidhostllm/AppDatabase.kt`
- `app/src/main/java/com/example/androidhostllm/ChatRepository.kt`
- `app/src/main/java/com/example/androidhostllm/GenerationJobs.kt`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/chat.html`
- `app/src/main/assets/web/admin.html`
- `app/src/main/assets/web/styles.css`
- `test_mvp_full_stack.sh`
- `test_skills_tools_thinking.sh`
- `test_local_ops.sh`
- `diagnose_generation_active.sh`
- `diagnostics/generation_diag_20260606T193249Z/`
- `diagnostics/generation_diag_20260606T194519Z/`
- `diagnostics/generation_diag_20260606T194551Z/`
- `docs/audits/current/`
- `docs/agentic_orchestration/orchestration_state.md`
- `docs/agentic_orchestration/orchestration_log.md`

## Findings

1. `Another generation is already active` is returned from two app-chat guards in `LocalHttpServer.kt`: retry generation and message creation. Both guards reject when either `generationJobs.activeAny()` is non-null or `liteRtLmManager.performanceSnapshot().activeGeneration` is true.
2. `GenerationJobStore` is a standalone implementation in `app/src/main/java/com/example/androidhostllm/GenerationJobs.kt`, not embedded inside `LocalHttpServer.kt`.
3. Job active states are `QUEUED`, `RUNNING`, and `STREAMING`. Terminal states are `COMPLETED`, `CANCELLED`, `FAILED`, and `TIMED_OUT`.
4. `GenerationJobStore.activeAny()` and `activeForChat()` only check status. They do not expire old active jobs by age.
5. `recentForChat()` returns records without cleanup, so stale jobs remain visible as active indefinitely.
6. There is no global generation listing in the job store or admin API. Existing generation routes are chat-local or per-ID under app-user auth.
7. App-chat SSE catches `Throwable` around stream writing and generation handling, then silently exits. If the catch fires while a job is still `QUEUED`, `RUNNING`, or `STREAMING`, the job can stay active forever.
8. The OpenAI-compatible SSE path also catches `Throwable` silently, but it does not create `GenerationJobStore` records. The LiteRT manager normally clears its active flag in `finishGeneration()` or error handling inside `generateStreaming()`.
9. `LiteRtLmManager` already exposes `cancelCurrentGeneration()`. It cancels the current coroutine job when `activeGeneration` is true and returns a failure if no generation is active.
10. `/health` currently exposes model/storage/database/security state but no generation summary.
11. `/api/admin/status` currently exposes `activeGeneration` from `LiteRtLmManager` only. It does not expose whether the job store is active or how many active jobs exist.
12. The admin UI has no global generation diagnostics or recovery controls.
13. The chat UI has Stop/Retry controls and chat-local cancel, but it does not load `/api/chats/{chatId}/generations` on chat open and does not explain that `generation_active` may come from another chat or the LiteRT manager.
14. `diagnose_generation_active.sh` defaults `BASE_URL` with a trailing slash and does not normalize it, so probes can produce double-slash URLs. It probes chat-local generations but not the required global admin generation routes.
15. `AppDatabase.kt` and `ChatRepository.kt` do not persist generation jobs. Current generation lifecycle is intentionally in-memory.

## Likely Root Cause

The reported behavior is consistent with a process-global active-generation source that is invisible from the current chat:

- A stale `GenerationJobStore` record from another chat can make `activeAny()` block new messages while chat-local cancel returns `no_active_generation`.
- A stream disconnect or pipe write failure can exit `streamingAppMessageResponse()` without marking the current job terminal.
- Less likely but still possible, `LiteRtLmManager.activeGeneration` can remain true after cancellation or a failure path; existing diagnostics do not distinguish that from job-store activity.

## Implementation Plan

1. Extend `GenerationJobStore` with a central active timeout, stale active-job expiry, active summaries, global recent listing, and cancel-all support.
2. Apply stale cleanup in `activeAny()`, `activeForChat()`, `recentForChat()`, global admin listing, message guards, retry guards, and cancel/list paths.
3. Update app-chat SSE cleanup so unexpected stream closure marks the job terminal if it is still active. Use `CANCELLED` for likely client disconnect/write failures and avoid overwriting completed jobs.
4. Add a generation summary helper in `LocalHttpServer.kt` that reports `jobStoreActive`, `liteRtActive`, source, active count, and stale expiry count.
5. Add admin routes:
   - `GET /api/admin/generations`
   - `POST /api/admin/generations/cancel-all-active`
   - `POST /api/admin/generations/{generationId}/cancel`
6. Include generation summaries in `/health` and `/api/admin/status`.
7. Add a compact admin Generations section with refresh, cancel-all, and per-job cancel actions.
8. Add chat-load active-generation status and improve `generation_active` error guidance.
9. Normalize `BASE_URL` in `diagnose_generation_active.sh` and add admin/global generation probes, with cancellation only when `CANCEL_ACTIVE=1`.
10. Add focused local JVM tests for `GenerationJobStore` if Gradle test support can be added without broad project changes.

## Test Plan

- `git diff --check`
- `bash -n test_mvp_full_stack.sh`
- `bash -n test_skills_tools_thinking.sh`
- `bash -n test_local_ops.sh`
- `bash -n diagnose_generation_active.sh`
- `./gradlew clean assembleDebug`
- `./gradlew test`
- `./gradlew lint`
- `./gradlew check`

Live phone/model diagnostics will be skipped in this Codex environment unless a reachable running Android server is available.

## Non-Goals

- No Phase 7 relay or network architecture.
- No cloud relay or remote access.
- No model rewrite.
- No persistent generation-job database migration.
- No frontend redesign beyond recovery controls.
- No merge to `main`, `master`, `develop`, or any production/default branch.
