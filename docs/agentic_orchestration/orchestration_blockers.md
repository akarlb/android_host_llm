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
