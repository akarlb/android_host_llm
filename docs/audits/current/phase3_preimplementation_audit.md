# Phase 3 Preimplementation Audit

Date: 2026-06-06

Branch: `codex/orchestration/phase3-generation-reliability`

## Current Behavior

- App chat generation is handled directly inside `POST /api/chats/{chatId}/messages`.
- Streaming responses use SSE and save the assistant message after generation completes.
- Non-streaming app chat returns a saved assistant message.
- `LiteRtLmManager` already has a process-wide active generation flag, timeout handling, and `cancelCurrentGeneration()`.
- The web UI disables Send during streaming but had no Stop or Retry control.
- No app-facing generation ID, generation status endpoint, chat-level cancel route, or retry route existed.

## Relevant Files Inspected

- `docs/agentic_orchestration/phase3.md`
- `docs/audits/current/phase2_admin_skills_tools_control_center_handoff.md`
- `app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt`
- `app/src/main/java/com/example/androidhostllm/LiteRtLmManager.kt`
- `app/src/main/java/com/example/androidhostllm/ChatRepository.kt`
- `app/src/main/java/com/example/androidhostllm/ChatModels.kt`
- `app/src/main/assets/web/chat.html`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/styles.css`
- `test_chat_api.sh`
- `test_mvp_full_stack.sh`

## Requirements-To-Evidence Map

| Requirement | Existing evidence | Status before implementation |
| --- | --- | --- |
| Generation job model | Manager has internal active state only | Missing |
| Cancel/stop generation | Native manager method exists; no app route/UI | Partial |
| Timeout handling | Manager wraps generation in timeout | Partial |
| Retry/regenerate | No app route/UI | Missing |
| Continue response | PromptBudget continuation detection exists for user prompts | Partial |
| Partial response persistence | Streaming persists final only; no job partial state | Partial |
| Concurrency guard | Manager mutex serializes; API does not reject clearly | Partial |
| Model-unloaded handling | Returns `503` in some paths | Partial |

## Risks

- Persisting generation jobs in SQLite would require broader migration and message-status changes. Use bounded in-memory job tracking for Phase 3 to minimize risk.
- Cancelling LiteRT-LM depends on coroutine cancellation timing; UI must remain recoverable even if cancellation is not instant.
- Retrying should not mutate or hide old messages. Add a new assistant message for retry output.

## Implementation Slices

1. Add bounded in-memory generation job model and JSON serialization.
2. Add app generation status/cancel/retry routes.
3. Wire job lifecycle into streaming and non-streaming app chat.
4. Add Stop and Retry controls to chat UI.
5. Update docs and handoff.
6. Run checks and APK compile gate.

## Test Plan

- `git diff --check`.
- `bash -n` scripts.
- `./gradlew clean assembleDebug`, `test`, `lint`, and `check` with explicit local Gradle/SDK paths.
- Live cancel/retry/streaming validation is skipped unless a running phone server/model-loaded environment exists.

## Non-Goals

- No relay, queue worker service, cross-process job persistence, public internet behavior, or OpenAI-compatible route redesign.
