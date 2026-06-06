# Orchestration Blockers

## 2026-06-06T17:42:51+02:00

Phase: Phase 1

Branch: codex/orchestration/phase1-api-security-foundation

Blocking condition:
- The required APK compile hard gate cannot run because Gradle is not installed on PATH and no alternate Gradle executable was found.

Commands run:
- ./gradlew clean assembleDebug
- command -v gradle || true
- command -v java || true
- java -version
- ls -d /opt/gradle* /usr/local/gradle* 2>/dev/null || true
- find /home/akb -maxdepth 5 -type f -name gradle -perm -111 2>/dev/null
- ls -la ~/.gradle/wrapper/dists 2>/dev/null || true
- find ~/.gradle -maxdepth 4 -type f -name 'gradle-*.zip' 2>/dev/null

Exact output or summarized failure:
- `./gradlew clean assembleDebug` exited 127.
- The wrapper printed: `ERROR: Gradle is required but was not found on PATH.`
- Java is present: OpenJDK 17.0.19 at `/usr/bin/java`.
- No `gradle` executable was found on PATH or in searched local locations.

Why continuation is unsafe:
- The orchestration executor defines `./gradlew clean assembleDebug` as the hard gate for moving through a phase.
- Continuing to implement or merging Phase 1 without an APK compile would violate the phase gate and could carry uncompiled Kotlin changes forward.

Required fix:
- Install Gradle 8.9+ on PATH, or set `GRADLE_CMD` to a valid Gradle executable.
- From `codex/orchestration/phase1-api-security-foundation`, rerun `./gradlew clean assembleDebug`.
- Continue Phase 1 only after APK compilation is available and passes.

Resolution:
- Resolved on 2026-06-06T17:55:09+02:00.
- Found Gradle at `/tmp/gradle-8.9/bin/gradle`.
- Found Android SDK at `/tmp/android-sdk`.
- `ANDROID_HOME=/tmp/android-sdk ANDROID_SDK_ROOT=/tmp/android-sdk GRADLE_CMD=/tmp/gradle-8.9/bin/gradle ./gradlew clean assembleDebug` passed.

## 2026-06-06T19:08:59+02:00

Phase: Phase 7

Branch: codex/orchestration-phases-1-7

Blocking condition:
- Phase 7 is explicitly marked as a future prompt and says: do not run it now.
- It requires explicit authorization before relay/network-agnostic work.

Evidence checked:
- API contract exists: `docs/api/api_contract.md`.
- Route/auth matrix exists: `docs/security/route_auth_matrix.md`.
- Admin skills/tools UI exists from Phase 2.
- Generation reliability exists from Phase 3.
- Frontend baseline is stable from Phase 4.
- Skills/tools hardening exists from Phase 5.
- Local backup/diagnostics/readiness exists from Phase 6.
- Local smoke tests have syntax checks and documented live-server blockers.

Commands run:
- sed -n '1,220p' docs/agentic_orchestration/phase7.md
- git status --short --branch
- git log --oneline --decorate -8

Why continuation is unsafe:
- Implementing relay/network-agnostic work without explicit authorization would violate the Phase 7 prompt.
- Relay work changes exposure and threat model, so proceeding without authorization would be unsafe.

Required fix:
- User must explicitly authorize Phase 7 relay/network-agnostic architecture work.
- After authorization, create `codex/orchestration/phase7-relay-network-architecture` and start with the required design documents before code.
