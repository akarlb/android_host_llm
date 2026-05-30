#!/usr/bin/env sh
# Lightweight text-only Gradle launcher.
#
# The normal Gradle wrapper requires committing gradle-wrapper.jar, but this
# repository's PR pipeline rejects binary files. This script preserves the
# required ./gradlew command by delegating to a Gradle executable installed on
# PATH, or to GRADLE_CMD when set.

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$APP_HOME"

GRADLE_BIN=${GRADLE_CMD:-gradle}

if ! command -v "$GRADLE_BIN" >/dev/null 2>&1; then
  cat >&2 <<'MSG'
ERROR: Gradle is required but was not found on PATH.

Install Gradle 8.9+ and JDK 17, then rerun:
  ./gradlew clean assembleDebug

Alternatively set GRADLE_CMD to an explicit Gradle executable path, for example:
  GRADLE_CMD=/opt/gradle/bin/gradle ./gradlew clean assembleDebug
MSG
  exit 127
fi

exec "$GRADLE_BIN" "$@"
