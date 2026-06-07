# Hotfix Generation Active Lock Handoff

Date: 2026-06-07

Branch: `codex/hotfix-generation-active-lock`

Merge target: `codex/orchestration-phases-1-7`

## Summary

This hotfix makes global generation locks visible, recoverable, and cancellable. Stale active jobs now expire automatically, app-chat stream failures finalize active jobs, `/health` and admin status expose active-source diagnostics, and the admin UI can list and cancel active generation jobs across chats.

## Root Cause

The app-chat send/retry guard used a global check:

- `generationJobs.activeAny()`
- `liteRtLmManager.performanceSnapshot().activeGeneration`

The job store had no stale-job expiry and app-chat SSE swallowed stream exceptions, so a disconnected stream could leave a job in `QUEUED`, `RUNNING`, or `STREAMING`. Chat-local cancel could report no active job while a stale active job from another chat still blocked new generation globally.

## Files Changed

- `app/src/main/java/com/example/androidhostllm/GenerationJobs.kt`
- `app/src/main/java/com/example/androidhostllm/LiteRtLmManager.kt`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/assets/web/admin.html`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/chat.html`
- `app/src/main/assets/web/styles.css`
- `app/build.gradle.kts`
- `app/src/test/java/com/example/androidhostllm/GenerationJobStoreTest.kt`
- `diagnose_generation_active.sh`
- `docs/audits/current/hotfix_generation_active_lock_preimplementation_audit.md`
- `docs/audits/current/hotfix_generation_active_lock_handoff.md`
- `docs/agentic_orchestration/orchestration_state.md`
- `docs/agentic_orchestration/orchestration_log.md`

## Generation Lifecycle Changes

- Added `DEFAULT_GENERATION_JOB_TIMEOUT_MS = 180_000L`.
- Added `updatedAtMs`, `errorCode`, `errorMessage`, `isActive`, active summaries, global recent listing, and cancel-all support to `GenerationJobStore`.
- `activeAny()`, `activeForChat()`, `recentForChat()`, `recentAll()`, and summary reads expire stale active jobs before returning data.
- Stale active jobs become `TIMED_OUT` with `generation_timed_out`.
- App-chat stream exceptions now call `finishIfStillActive()` and mark active jobs `CANCELLED` for likely disconnects or `FAILED` for internal stream errors.
- `LiteRtLmManager.cancelCurrentGeneration(reason)` can recover the stale case where `activeGeneration=true` but no coroutine job remains.

## Admin Generation Routes

- `GET /api/admin/generations`
- `POST /api/admin/generations/cancel-all-active`
- `POST /api/admin/generations/{generationId}/cancel`

Responses include non-sensitive global summary fields: `jobStoreActive`, `liteRtActive`, `activeGenerationSource`, `activeCount`, `jobStoreActiveCount`, and `expiredStaleCount`.

## Frontend Recovery Behavior

- Chat load probes `/api/chats/{chatId}/generations`.
- Current-chat active jobs show a recovery banner with cancel and refresh.
- `generation_active` errors now explain that the active generation may be in another chat or from an interrupted stream and point admins to Admin -> Generations.
- Admin has a Generations section with active source/count, recent jobs, refresh, per-job cancel, and cancel-all controls.

## Diagnostic Script Changes

- Normalizes `BASE_URL` with `BASE_URL="${BASE_URL%/}"`.
- Probes `/health` generation summary.
- Probes `/api/admin/status` generation summary.
- Probes `GET /api/admin/generations`.
- Calls `POST /api/admin/generations/cancel-all-active` only when `CANCEL_ACTIVE=1`.
- Static source scan now checks `GenerationJobs.kt`.

## Tests Run

Passed:

```sh
bash -n diagnose_generation_active.sh
node --check app/src/main/assets/web/app.js
ANDROID_HOME=/tmp/android-sdk ANDROID_SDK_ROOT=/tmp/android-sdk GRADLE_CMD=/tmp/gradle-8.9/bin/gradle ./gradlew test --no-daemon
```

Initial `./gradlew test --no-daemon` failed before compilation because `ANDROID_HOME` was not set. It passed after setting `ANDROID_HOME=/tmp/android-sdk` and `ANDROID_SDK_ROOT=/tmp/android-sdk`.

## Compile Result

Pending final full gate:

```sh
ANDROID_HOME=/tmp/android-sdk ANDROID_SDK_ROOT=/tmp/android-sdk GRADLE_CMD=/tmp/gradle-8.9/bin/gradle ./gradlew clean assembleDebug
```

## Skipped Live Tests

Live phone/model diagnostics were not run in this Codex shell because there is no confirmed reachable running Android phone server with a loaded local model.

## Known Limitations

- Generation jobs remain in-memory and are cleared by app process restart.
- Admin global generation listing redacts user ID and does not include prompts or partial model output.
- OpenAI-compatible SSE does not create `GenerationJobStore` records; it relies on `LiteRtLmManager` lifecycle cleanup.

## Next Recommended Action

Run the final static, compile, lint, and check gates, then merge this hotfix branch only into `codex/orchestration-phases-1-7`. Phase 7 remains deferred.
