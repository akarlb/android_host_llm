#!/usr/bin/env bash
set -uo pipefail

# diagnose_generation_active.sh
#
# Purpose:
#   Diagnose "Another generation is already active" and general post-orchestration health.
#   Writes a Markdown report and raw HTTP artifacts to disk.
#
# Run from repo root:
#   bash diagnose_generation_active.sh
#
# Common options via env:
#   BASE_URL=http://127.0.0.1:8080 bash diagnose_generation_active.sh
#   RUN_COMPILE=0 bash diagnose_generation_active.sh
#   RUN_API=0 bash diagnose_generation_active.sh
#   AUTH_TOKEN="..." bash diagnose_generation_active.sh
#   CANCEL_ACTIVE=1 bash diagnose_generation_active.sh
#   GENERATION_STUCK_RECOVERY_TEST=1 SHORT_TIMEOUT_PROBE=1 bash diagnose_generation_active.sh
#
# Defaults:
#   BASE_URL=http://127.0.0.1:8080
#   REPORT_DIR=diagnostics
#   RUN_COMPILE=1
#   RUN_STATIC=1
#   RUN_API=1
#   CANCEL_ACTIVE=0
#   GENERATION_STUCK_RECOVERY_TEST=1
#   SHORT_TIMEOUT_PROBE=1
#
# Notes:
#   - This script does not require an Android emulator.
#   - APK compilation is attempted locally with ./gradlew clean assembleDebug.
#   - Live API diagnostics require a running phone/local server.
#   - The script creates a diagnostic user/chat if AUTH_TOKEN is not supplied.
#   - It will not cancel active generations unless CANCEL_ACTIVE=1.

BASE_URL="${BASE_URL:-http://192.168.0.164:8080/}"
BASE_URL="${BASE_URL%/}"
REPORT_DIR="${REPORT_DIR:-diagnostics}"
RUN_COMPILE="${RUN_COMPILE:-1}"
RUN_STATIC="${RUN_STATIC:-1}"
RUN_API="${RUN_API:-1}"
CANCEL_ACTIVE="${CANCEL_ACTIVE:-0}"
GENERATION_STUCK_RECOVERY_TEST="${GENERATION_STUCK_RECOVERY_TEST:-1}"
SHORT_TIMEOUT_PROBE="${SHORT_TIMEOUT_PROBE:-1}"
SHORT_TIMEOUT_SECONDS="${SHORT_TIMEOUT_SECONDS:-2}"
POST_TIMEOUT_GRACE_SECONDS="${POST_TIMEOUT_GRACE_SECONDS:-8}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
DIAG_USERNAME="${DIAG_USERNAME:-diag_user_$(date +%s)}"
DIAG_PASSWORD="${DIAG_PASSWORD:-diag-password-12345}"
CHAT_ID="${CHAT_ID:-}"
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-75}"
CURL_TIMEOUT_SECONDS="${CURL_TIMEOUT_SECONDS:-20}"

STAMP="$(date -u +"%Y%m%dT%H%M%SZ")"
OUT_DIR="${REPORT_DIR}/generation_diag_${STAMP}"
RAW_DIR="${OUT_DIR}/raw"
REPORT="${OUT_DIR}/generation_diagnostics_report.md"

mkdir -p "$RAW_DIR"

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

log_report() {
  printf '%s\n' "$*" >> "$REPORT"
}

console() {
  printf '%s\n' "$*"
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  console "PASS: $*"
  printf -- "- PASS: %s\n" "$*" >> "$REPORT"
}

warn() {
  WARN_COUNT=$((WARN_COUNT + 1))
  console "WARN: $*"
  printf -- "- WARN: %s\n" "$*" >> "$REPORT"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  console "FAIL: $*"
  printf -- "- FAIL: %s\n" "$*" >> "$REPORT"
}

skip() {
  SKIP_COUNT=$((SKIP_COUNT + 1))
  console "SKIP: $*"
  printf -- "- SKIP: %s\n" "$*" >> "$REPORT"
}

section() {
  {
    printf '\n## %s\n\n' "$1"
  } >> "$REPORT"
  console ""
  console "== $1 =="
}

subsection() {
  {
    printf '\n### %s\n\n' "$1"
  } >> "$REPORT"
  console "-- $1"
}

cmd_exists() {
  command -v "$1" >/dev/null 2>&1
}

run_cmd() {
  local name="$1"
  shift
  local safe_name
  safe_name="$(printf '%s' "$name" | tr ' /:' '___' | tr -cd 'A-Za-z0-9_.-')"
  local out_file="${RAW_DIR}/${safe_name}.txt"

  console "RUN: $name"
  {
    printf '\n### Command: %s\n\n' "$name"
    printf '```bash\n'
    printf '%q ' "$@"
    printf '\n```\n\n'
  } >> "$REPORT"

  "$@" >"$out_file" 2>&1
  local status=$?

  {
    printf 'Exit code: `%s`\n\n' "$status"
    printf 'Output saved to: `%s`\n\n' "$out_file"
    printf '<details><summary>Output preview</summary>\n\n'
    printf '```text\n'
    sed -n '1,160p' "$out_file"
    printf '\n```\n\n'
    printf '</details>\n\n'
  } >> "$REPORT"

  if [ "$status" -eq 0 ]; then
    pass "$name"
  else
    fail "$name exited with $status"
  fi
  return "$status"
}

json_get() {
  local path="$1"
  local name="$2"
  local token="${3:-$AUTH_TOKEN}"
  local out_file="${RAW_DIR}/${name}.json"
  local code_file="${RAW_DIR}/${name}.status"

  local headers=(-H "Accept: application/json")
  if [ -n "$token" ]; then
    headers+=(-H "Authorization: Bearer $token")
  fi

  curl -sS --max-time "$CURL_TIMEOUT_SECONDS" \
    "${headers[@]}" \
    -w "%{http_code}" \
    -o "$out_file" \
    "$BASE_URL$path" > "$code_file" 2>"${RAW_DIR}/${name}.curl.err"

  local curl_status=$?
  local http_code
  http_code="$(cat "$code_file" 2>/dev/null || true)"
  printf '%s:%s:%s\n' "$curl_status" "$http_code" "$out_file"
}

json_post() {
  local path="$1"
  local body="$2"
  local name="$3"
  local token="${4:-$AUTH_TOKEN}"
  local out_file="${RAW_DIR}/${name}.json"
  local code_file="${RAW_DIR}/${name}.status"

  local headers=(-H "Accept: application/json" -H "Content-Type: application/json")
  if [ -n "$token" ]; then
    headers+=(-H "Authorization: Bearer $token")
  fi

  curl -sS --max-time "$CURL_TIMEOUT_SECONDS" \
    -X POST \
    "${headers[@]}" \
    --data-binary "$body" \
    -w "%{http_code}" \
    -o "$out_file" \
    "$BASE_URL$path" > "$code_file" 2>"${RAW_DIR}/${name}.curl.err"

  local curl_status=$?
  local http_code
  http_code="$(cat "$code_file" 2>/dev/null || true)"
  printf '%s:%s:%s\n' "$curl_status" "$http_code" "$out_file"
}

json_extract() {
  local file="$1"
  local expr="$2"
  if ! cmd_exists python3; then
    printf '\n'
    return
  fi
  python3 - "$file" "$expr" <<'PY'
import json, sys
path, expr = sys.argv[1], sys.argv[2]
try:
    with open(path, "r", encoding="utf-8") as f:
        obj = json.load(f)
    print(eval(expr, {}, {"obj": obj}))
except Exception:
    print("")
PY
}

json_pretty_to_report() {
  local file="$1"
  if [ -s "$file" ] && cmd_exists python3; then
    python3 - "$file" <<'PY' >> "$REPORT"
import json, sys
try:
    obj = json.load(open(sys.argv[1], encoding="utf-8"))
    print("```json")
    print(json.dumps(obj, indent=2, ensure_ascii=False)[:12000])
    print("```")
except Exception:
    print("```text")
    print(open(sys.argv[1], encoding="utf-8", errors="replace").read()[:12000])
    print("```")
PY
  else
    {
      printf '```text\n'
      sed -n '1,200p' "$file" 2>/dev/null || true
      printf '\n```\n'
    } >> "$REPORT"
  fi
}

init_report() {
  cat > "$REPORT" <<EOM
# Generation Diagnostics Report

Generated: \`${STAMP}\`

Repository path: \`$(pwd)\`

Base URL: \`${BASE_URL}\`

Output directory: \`${OUT_DIR}\`

## Purpose

This report diagnoses:

- APK compilation state;
- static/spec/script checks;
- server health;
- auth/session state;
- admin generation indicators where accessible;
- chat generation job state;
- stale active generation symptoms;
- SSE behavior and whether visible model content is returned;
- the specific \`Another generation is already active\` failure mode.

EOM
}

repo_context() {
  section "Repository context"

  if git rev-parse --show-toplevel >/dev/null 2>&1; then
    local root branch commit
    root="$(git rev-parse --show-toplevel)"
    branch="$(git branch --show-current 2>/dev/null || true)"
    commit="$(git rev-parse --short HEAD 2>/dev/null || true)"

    log_report "- Repo root: \`$root\`"
    log_report "- Current branch: \`$branch\`"
    log_report "- Current commit: \`$commit\`"
    log_report ""

    git status --short > "${RAW_DIR}/git_status_short.txt" 2>&1
    git log --oneline --decorate -20 > "${RAW_DIR}/git_log_recent.txt" 2>&1

    if [ "$branch" = "tree/codex/orchestration-phases-1-7" ] || [ "$branch" = "codex/orchestration-phases-1-7" ]; then
      pass "Current branch appears to be the orchestration branch: $branch"
    else
      warn "Current branch is '$branch'. Expected orchestration branch such as tree/codex/orchestration-phases-1-7 or codex/orchestration-phases-1-7."
    fi

    if [ -s "${RAW_DIR}/git_status_short.txt" ]; then
      warn "Working tree has changes. See ${RAW_DIR}/git_status_short.txt"
      {
        printf '\nWorking tree status:\n\n```text\n'
        cat "${RAW_DIR}/git_status_short.txt"
        printf '\n```\n'
      } >> "$REPORT"
    else
      pass "Working tree is clean"
    fi
  else
    fail "Not inside a git repository"
  fi
}

static_checks() {
  section "Static/spec checks"

  if [ "$RUN_STATIC" != "1" ]; then
    skip "RUN_STATIC=0"
    return
  fi

  run_cmd "git diff --check" git diff --check || true

  if [ -f "test_mvp_full_stack.sh" ]; then
    run_cmd "bash -n test_mvp_full_stack.sh" bash -n test_mvp_full_stack.sh || true
  else
    skip "test_mvp_full_stack.sh not found"
  fi

  if [ -f "test_skills_tools_thinking.sh" ]; then
    run_cmd "bash -n test_skills_tools_thinking.sh" bash -n test_skills_tools_thinking.sh || true
  else
    skip "test_skills_tools_thinking.sh not found"
  fi

  if [ -f "test_local_ops.sh" ]; then
    run_cmd "bash -n test_local_ops.sh" bash -n test_local_ops.sh || true
  else
    skip "test_local_ops.sh not found"
  fi

  subsection "Phase handoff files"

  for n in 1 2 3 4 5 6; do
    found="$(find docs -path "*phase${n}*handoff*.md" -o -path "*phase${n}*completion*.md" 2>/dev/null | sort | tr '\n' ' ')"
    if [ -n "$found" ]; then
      pass "Phase $n handoff/completion docs found"
      log_report "- Phase $n docs: \`$found\`"
    else
      warn "No obvious Phase $n handoff/completion doc found"
    fi
  done
}

compile_checks() {
  section "APK compilation and Gradle checks"

  if [ "$RUN_COMPILE" != "1" ]; then
    skip "RUN_COMPILE=0"
    return
  fi

  if [ ! -x "./gradlew" ]; then
    if [ -f "./gradlew" ]; then
      warn "./gradlew exists but is not executable; attempting bash ./gradlew"
      run_cmd "./gradlew clean assembleDebug" bash ./gradlew clean assembleDebug || true
    else
      fail "./gradlew not found"
    fi
  else
    run_cmd "./gradlew clean assembleDebug" ./gradlew clean assembleDebug || true
  fi

  if [ -x "./gradlew" ]; then
    run_cmd "./gradlew test" ./gradlew test || true
    run_cmd "./gradlew lint" ./gradlew lint || true
    run_cmd "./gradlew check" ./gradlew check || true
  fi
}

api_intro() {
  section "Live API diagnostics"

  if [ "$RUN_API" != "1" ]; then
    skip "RUN_API=0"
    return 1
  fi

  if ! cmd_exists curl; then
    fail "curl is not available"
    return 1
  fi

  if ! cmd_exists python3; then
    warn "python3 is not available; JSON extraction will be limited"
  fi

  return 0
}

probe_health() {
  subsection "Health"

  local result curl_status rest http_code file
  result="$(json_get "/health" "health")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- HTTP status: \`$http_code\`"
  log_report "- Raw file: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$curl_status" != "0" ]; then
    fail "Could not reach $BASE_URL/health. curl exit: $curl_status"
    return 1
  fi

  if [ "$http_code" = "200" ]; then
    pass "/health returned 200"
  else
    fail "/health returned HTTP $http_code"
  fi

  local model_loaded active_generation mode active_source job_store_active litert_active active_count
  model_loaded="$(json_extract "$file" 'obj.get("modelLoaded", obj.get("model", {}).get("loaded", ""))')"
  active_generation="$(json_extract "$file" 'obj.get("activeGeneration", obj.get("generation", {}).get("active", ""))')"
  mode="$(json_extract "$file" 'obj.get("securityMode", obj.get("mode", ""))')"
  active_source="$(json_extract "$file" 'obj.get("generation", {}).get("activeGenerationSource", "")')"
  job_store_active="$(json_extract "$file" 'obj.get("generation", {}).get("jobStoreActive", "")')"
  litert_active="$(json_extract "$file" 'obj.get("generation", {}).get("liteRtActive", "")')"
  active_count="$(json_extract "$file" 'obj.get("generation", {}).get("activeCount", "")')"

  [ -n "$model_loaded" ] && log_report "- Model loaded: \`$model_loaded\`"
  [ -n "$active_generation" ] && log_report "- Active generation from health: \`$active_generation\`"
  [ -n "$active_source" ] && log_report "- Health active generation source: \`$active_source\`"
  [ -n "$job_store_active" ] && log_report "- Health jobStoreActive: \`$job_store_active\`"
  [ -n "$litert_active" ] && log_report "- Health liteRtActive: \`$litert_active\`"
  [ -n "$active_count" ] && log_report "- Health activeCount: \`$active_count\`"
  [ -n "$mode" ] && log_report "- Mode: \`$mode\`"

  if [ "$active_generation" = "True" ] || [ "$active_generation" = "true" ]; then
    warn "/health reports active generation"
  fi

  return 0
}

assert_health_inactive() {
  local name="${1:-health_assert}"
  local result curl_status rest http_code file active_generation active_source job_store_active litert_active active_count
  result="$(json_get "/health" "$name")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Health assertion ${name}: HTTP \`${http_code}\`, raw: \`${file}\`"
  json_pretty_to_report "$file"

  if [ "$curl_status" != "0" ] || [ "$http_code" != "200" ]; then
    warn "Could not assert /health inactive for ${name}"
    return 1
  fi

  active_generation="$(json_extract "$file" 'obj.get("activeGeneration", obj.get("generation", {}).get("active", ""))')"
  active_source="$(json_extract "$file" 'obj.get("generation", {}).get("activeGenerationSource", "")')"
  job_store_active="$(json_extract "$file" 'obj.get("generation", {}).get("jobStoreActive", "")')"
  litert_active="$(json_extract "$file" 'obj.get("generation", {}).get("liteRtActive", "")')"
  active_count="$(json_extract "$file" 'obj.get("generation", {}).get("activeCount", "")')"

  log_report "- Health activeGeneration: \`${active_generation}\`"
  log_report "- Health active source: \`${active_source}\`"
  log_report "- Health jobStoreActive: \`${job_store_active}\`"
  log_report "- Health liteRtActive: \`${litert_active}\`"
  log_report "- Health activeCount: \`${active_count}\`"

  if [ "$active_generation" = "False" ] || [ "$active_generation" = "false" ] || [ "$active_source" = "none" ]; then
    pass "/health reports no active generation (${name})"
  else
    fail "/health still reports active generation (${name})"
  fi
}

obtain_auth() {
  subsection "Authentication"

  if [ -n "$AUTH_TOKEN" ]; then
    pass "Using AUTH_TOKEN from environment"
    log_report "- Auth token: provided by environment; not printed."
  else
    local body result curl_status rest http_code file token role
    body="{\"username\":\"${DIAG_USERNAME}\",\"password\":\"${DIAG_PASSWORD}\"}"
    result="$(json_post "/auth/register" "$body" "auth_register" "")"
    curl_status="${result%%:*}"
    rest="${result#*:}"
    http_code="${rest%%:*}"
    file="${rest#*:}"

    log_report "- Register diagnostic user: HTTP \`$http_code\`, raw: \`$file\`"
    json_pretty_to_report "$file"

    if [ "$curl_status" != "0" ]; then
      fail "Registration request failed at curl level"
      return 1
    fi

    if [ "$http_code" = "200" ]; then
      token="$(json_extract "$file" 'obj.get("token", "")')"
      role="$(json_extract "$file" 'obj.get("user", {}).get("role", "")')"
      if [ -n "$token" ]; then
        AUTH_TOKEN="$token"
        pass "Created diagnostic user and obtained token"
        log_report "- Diagnostic username: \`$DIAG_USERNAME\`"
        log_report "- Diagnostic role: \`$role\`"
      else
        fail "Register returned 200 but no token found"
        return 1
      fi
    elif [ "$http_code" = "409" ]; then
      warn "Diagnostic username already exists; attempting login"
      result="$(json_post "/auth/login" "$body" "auth_login" "")"
      curl_status="${result%%:*}"
      rest="${result#*:}"
      http_code="${rest%%:*}"
      file="${rest#*:}"
      log_report "- Login diagnostic user: HTTP \`$http_code\`, raw: \`$file\`"
      json_pretty_to_report "$file"
      token="$(json_extract "$file" 'obj.get("token", "")')"
      if [ "$http_code" = "200" ] && [ -n "$token" ]; then
        AUTH_TOKEN="$token"
        pass "Logged in diagnostic user"
      else
        fail "Could not register or login diagnostic user"
        return 1
      fi
    else
      fail "Register diagnostic user returned HTTP $http_code"
      return 1
    fi
  fi

  local result curl_status rest http_code file
  result="$(json_get "/auth/session" "auth_session" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Session check: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$http_code" = "200" ]; then
    pass "/auth/session reachable with token"
  else
    fail "/auth/session returned HTTP $http_code"
    return 1
  fi

  return 0
}

probe_admin_status() {
  subsection "Admin status probe"

  local result curl_status rest http_code file active_generation active_source job_store_active litert_active active_count
  result="$(json_get "/api/admin/status" "admin_status" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Admin status: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  case "$http_code" in
    200)
      pass "/api/admin/status reachable"
      active_generation="$(json_extract "$file" 'obj.get("activeGeneration", obj.get("model", {}).get("activeGeneration", ""))')"
      active_source="$(json_extract "$file" 'obj.get("generation", {}).get("activeGenerationSource", "")')"
      job_store_active="$(json_extract "$file" 'obj.get("generation", {}).get("jobStoreActive", "")')"
      litert_active="$(json_extract "$file" 'obj.get("generation", {}).get("liteRtActive", "")')"
      active_count="$(json_extract "$file" 'obj.get("generation", {}).get("activeCount", "")')"
      [ -n "$active_generation" ] && log_report "- Admin activeGeneration: \`$active_generation\`"
      [ -n "$active_source" ] && log_report "- Admin active generation source: \`$active_source\`"
      [ -n "$job_store_active" ] && log_report "- Admin jobStoreActive: \`$job_store_active\`"
      [ -n "$litert_active" ] && log_report "- Admin liteRtActive: \`$litert_active\`"
      [ -n "$active_count" ] && log_report "- Admin activeCount: \`$active_count\`"
      if [ "$active_generation" = "True" ] || [ "$active_generation" = "true" ]; then
        warn "Admin status reports activeGeneration=true"
      fi
      ;;
    401|403)
      warn "/api/admin/status not accessible with current token. This is expected if diagnostic user is not admin."
      ;;
    *)
      warn "/api/admin/status returned HTTP $http_code"
      ;;
  esac
}

probe_admin_generations() {
  subsection "Admin global generations probe"

  local result curl_status rest http_code file active_source active_count job_store_active litert_active
  result="$(json_get "/api/admin/generations" "admin_generations" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Admin generations: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  case "$http_code" in
    200)
      pass "/api/admin/generations reachable"
      active_source="$(json_extract "$file" 'obj.get("generation", {}).get("activeGenerationSource", "")')"
      active_count="$(json_extract "$file" 'obj.get("generation", {}).get("activeCount", "")')"
      job_store_active="$(json_extract "$file" 'obj.get("generation", {}).get("jobStoreActive", "")')"
      litert_active="$(json_extract "$file" 'obj.get("generation", {}).get("liteRtActive", "")')"
      [ -n "$active_source" ] && log_report "- Global active source: \`$active_source\`"
      [ -n "$active_count" ] && log_report "- Global active count: \`$active_count\`"
      [ -n "$job_store_active" ] && log_report "- Global jobStoreActive: \`$job_store_active\`"
      [ -n "$litert_active" ] && log_report "- Global liteRtActive: \`$litert_active\`"
      ;;
    401|403)
      warn "/api/admin/generations not accessible with current token. This is expected if diagnostic user is not admin."
      ;;
    *)
      warn "/api/admin/generations returned HTTP $http_code"
      ;;
  esac
}

cancel_global_generations_if_requested() {
  if [ "$CANCEL_ACTIVE" != "1" ]; then
    skip "CANCEL_ACTIVE=0; not cancelling global active generations"
    return
  fi

  subsection "Cancel all active generations"

  local result curl_status rest http_code file
  result="$(json_post "/api/admin/generations/cancel-all-active" '{}' "cancel_all_generations" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Cancel all generations: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  case "$http_code" in
    200)
      pass "Cancel all active generations request succeeded"
      ;;
    401|403)
      warn "Cancel all active generations requires admin access"
      ;;
    *)
      warn "Cancel all active generations returned HTTP $http_code"
      ;;
  esac
}

create_or_use_chat() {
  subsection "Diagnostic chat"

  if [ -n "$CHAT_ID" ]; then
    pass "Using CHAT_ID from environment: $CHAT_ID"
    return 0
  fi

  local body result curl_status rest http_code file id
  body='{"title":"Generation diagnostics chat","profile":"CONVERSATION"}'
  result="$(json_post "/api/chats" "$body" "create_chat" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Create chat: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$curl_status" != "0" ]; then
    fail "Create chat failed at curl level"
    return 1
  fi

  if [ "$http_code" = "200" ]; then
    id="$(json_extract "$file" 'obj.get("chat", {}).get("id", "")')"
    if [ -n "$id" ]; then
      CHAT_ID="$id"
      pass "Created diagnostic chat: $CHAT_ID"
      return 0
    fi
  fi

  fail "Could not create diagnostic chat; HTTP $http_code"
  return 1
}

list_chat_generations() {
  subsection "Chat generation jobs"

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return 1
  fi

  local result curl_status rest http_code file active_count
  result="$(json_get "/api/chats/${CHAT_ID}/generations" "chat_generations_before" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Chat generations before send: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$http_code" = "200" ]; then
    pass "Listed chat generations"
    active_count="$(json_extract "$file" 'sum(1 for g in obj.get("generations", []) if g.get("status") in ["QUEUED","RUNNING","STREAMING","queued","running","streaming"])')"
    log_report "- Active generation jobs in this chat: \`$active_count\`"
    if [ "$active_count" != "" ] && [ "$active_count" != "0" ]; then
      warn "This chat has active generation jobs before sending"
    fi
  elif [ "$http_code" = "404" ]; then
    warn "Generation list route not found or chat not found"
  else
    warn "List chat generations returned HTTP $http_code"
  fi
}

cancel_chat_generation_if_requested() {
  if [ "$CANCEL_ACTIVE" != "1" ]; then
    skip "CANCEL_ACTIVE=0; not cancelling active generations"
    return
  fi

  subsection "Cancel active generation"

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return
  fi

  local result curl_status rest http_code file
  result="$(json_post "/api/chats/${CHAT_ID}/generation/cancel" '{}' "cancel_chat_generation" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Cancel chat generation: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$http_code" = "200" ]; then
    pass "Cancel active chat generation request succeeded"
  else
    warn "Cancel active chat generation returned HTTP $http_code"
  fi
}

send_non_stream_probe() {
  subsection "Non-streaming message probe"

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return
  fi

  local body result curl_status rest http_code file error_code error_message
  body='{"content":"Diagnostic probe: reply with exactly one short sentence.","stream":false,"fileIds":[]}'
  result="$(json_post "/api/chats/${CHAT_ID}/messages" "$body" "message_non_stream" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Non-stream message: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  error_code="$(json_extract "$file" 'obj.get("error", {}).get("code", obj.get("errorCode", "")) if isinstance(obj.get("error", ""), dict) else ""')"
  error_message="$(json_extract "$file" 'obj.get("error", {}).get("message", obj.get("error", "")) if isinstance(obj.get("error", ""), dict) else obj.get("error", "")')"

  if [ "$http_code" = "200" ]; then
    pass "Non-streaming diagnostic message returned 200"
  elif [ "$http_code" = "409" ]; then
    fail "Non-streaming diagnostic message returned 409 conflict"
    log_report "- Error code: \`$error_code\`"
    log_report "- Error message: \`$error_message\`"
    if printf '%s' "$error_message $error_code" | grep -qi "generation.*active\|Another generation is already active"; then
      fail "Confirmed target symptom: backend reports another generation is already active"
    fi
  elif [ "$http_code" = "503" ]; then
    warn "Model/server unavailable for generation; HTTP 503"
  else
    warn "Non-streaming diagnostic message returned HTTP $http_code"
  fi
}

send_short_timeout_probe() {
  subsection "Short-timeout stuck recovery probe"

  if [ "$GENERATION_STUCK_RECOVERY_TEST" != "1" ] || [ "$SHORT_TIMEOUT_PROBE" != "1" ]; then
    skip "GENERATION_STUCK_RECOVERY_TEST or SHORT_TIMEOUT_PROBE disabled"
    return
  fi

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return
  fi

  local out_file status_file err_file body curl_status http_code
  out_file="${RAW_DIR}/message_short_timeout.json"
  status_file="${RAW_DIR}/message_short_timeout.status"
  err_file="${RAW_DIR}/message_short_timeout.curl.err"
  body='{"content":"Diagnostic timeout probe: wait briefly, then reply with one short sentence.","stream":false,"fileIds":[]}'

  console "RUN: short-timeout non-streaming probe with curl max-time ${SHORT_TIMEOUT_SECONDS}s"

  curl -sS --max-time "$SHORT_TIMEOUT_SECONDS" \
    -X POST \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $AUTH_TOKEN" \
    --data-binary "$body" \
    -w "%{http_code}" \
    -o "$out_file" \
    "$BASE_URL/api/chats/${CHAT_ID}/messages" > "$status_file" 2>"$err_file"

  curl_status=$?
  http_code="$(cat "$status_file" 2>/dev/null || true)"

  log_report "- Short-timeout curl exit: \`${curl_status}\`"
  log_report "- Short-timeout HTTP status: \`${http_code}\`"
  log_report "- Short-timeout output: \`${out_file}\`"
  log_report "- Short-timeout stderr: \`${err_file}\`"
  json_pretty_to_report "$out_file"

  if [ "$curl_status" = "28" ] || [ "$curl_status" = "124" ] || [ "$http_code" = "000" ] || [ -z "$http_code" ]; then
    pass "Short-timeout probe simulated client timeout/disconnect"
  elif [ "$http_code" = "200" ]; then
    pass "Short-timeout probe completed before client timeout"
  elif [ "$http_code" = "409" ]; then
    fail "Short-timeout probe returned 409 generation_active before recovery test"
  else
    warn "Short-timeout probe returned HTTP ${http_code} with curl exit ${curl_status}"
  fi

  sleep "$POST_TIMEOUT_GRACE_SECONDS"
  assert_health_inactive "health_after_short_timeout" || true
}

send_stream_probe() {
  subsection "Streaming/SSE message probe"

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return
  fi

  local stream_file status_file err_file body http_code
  stream_file="${RAW_DIR}/message_stream_sse.txt"
  status_file="${RAW_DIR}/message_stream_sse.status"
  err_file="${RAW_DIR}/message_stream_sse.curl.err"
  body='{"content":"Diagnostic streaming probe: reply with exactly one short sentence.","stream":true,"fileIds":[]}'

  console "RUN: streaming SSE probe with timeout ${STREAM_TIMEOUT_SECONDS}s"

  timeout "$STREAM_TIMEOUT_SECONDS" curl -sS \
    --max-time "$STREAM_TIMEOUT_SECONDS" \
    -X POST \
    -H "Accept: text/event-stream" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $AUTH_TOKEN" \
    --data-binary "$body" \
    -w "%{http_code}" \
    -o "$stream_file" \
    "$BASE_URL/api/chats/${CHAT_ID}/messages" > "$status_file" 2>"$err_file"

  local curl_status=$?
  http_code="$(cat "$status_file" 2>/dev/null || true)"

  log_report "- Streaming probe curl exit: \`$curl_status\`"
  log_report "- Streaming probe HTTP status: \`$http_code\`"
  log_report "- Streaming output: \`$stream_file\`"
  log_report "- Streaming stderr: \`$err_file\`"
  log_report ""
  log_report "<details><summary>SSE output preview</summary>"
  log_report ""
  log_report '```text'
  sed -n '1,220p' "$stream_file" >> "$REPORT" 2>/dev/null || true
  log_report '```'
  log_report ""
  log_report "</details>"

  if [ "$curl_status" = "124" ]; then
    warn "Streaming probe timed out after ${STREAM_TIMEOUT_SECONDS}s"
  elif [ "$curl_status" != "0" ]; then
    warn "Streaming probe curl exited with $curl_status"
  fi

  if [ "$http_code" = "200" ]; then
    pass "Streaming endpoint returned 200"
  elif [ "$http_code" = "409" ]; then
    fail "Streaming endpoint returned 409 conflict"
  elif [ "$http_code" = "503" ]; then
    warn "Streaming endpoint returned 503 model/server unavailable"
  else
    warn "Streaming endpoint returned HTTP '$http_code'"
  fi

  if grep -q "data: \[DONE\]" "$stream_file" 2>/dev/null; then
    pass "SSE stream included [DONE]"
  elif grep -qi '"error"\|"code"' "$stream_file" 2>/dev/null; then
    pass "SSE stream included explicit error completion"
  else
    warn "SSE stream did not include [DONE]"
  fi

  if grep -Eq '^data: .*"content"' "$stream_file" 2>/dev/null; then
    pass "SSE stream included at least one content event"
  else
    warn "SSE stream did not include a visible content event"
  fi

  if grep -qi "Another generation is already active\|generation_active" "$stream_file" "$err_file" 2>/dev/null; then
    fail "Streaming probe confirmed active-generation conflict"
  fi
}

post_probe_generations() {
  subsection "Generation jobs after probes"

  if [ -z "$CHAT_ID" ]; then
    skip "No CHAT_ID available"
    return
  fi

  local result curl_status rest http_code file active_count
  result="$(json_get "/api/chats/${CHAT_ID}/generations" "chat_generations_after" "$AUTH_TOKEN")"
  curl_status="${result%%:*}"
  rest="${result#*:}"
  http_code="${rest%%:*}"
  file="${rest#*:}"

  log_report "- Chat generations after probes: HTTP \`$http_code\`, raw: \`$file\`"
  json_pretty_to_report "$file"

  if [ "$http_code" = "200" ]; then
    active_count="$(json_extract "$file" 'sum(1 for g in obj.get("generations", []) if g.get("status") in ["QUEUED","RUNNING","STREAMING","queued","running","streaming"])')"
    log_report "- Active generation jobs after probes: \`$active_count\`"
    if [ "$active_count" != "" ] && [ "$active_count" != "0" ]; then
      fail "Active generation jobs remain after probes"
    else
      pass "No active generation jobs remain in diagnostic chat"
    fi
  else
    warn "Could not list chat generations after probes; HTTP $http_code"
  fi
}

probe_admin_generations_after() {
  subsection "Admin global generations after probes"
  probe_admin_generations
}

source_scan_generation_code() {
  section "Static source scan for generation-active failure mode"

  local files=(
    "app/src/main/java/com/example/androidhostllm/LocalHttpServer.kt"
    "app/src/main/java/com/example/androidhostllm/GenerationJobs.kt"
    "app/src/main/java/com/example/androidhostllm/LiteRtLmManager.kt"
    "app/src/main/assets/web/app.js"
  )

  for f in "${files[@]}"; do
    if [ -f "$f" ]; then
      pass "Found $f"
    else
      warn "Missing expected file $f"
    fi
  done

  subsection "Search: Another generation is already active"
  grep -RIn "Another generation is already active\|generation_active\|activeAny\|activeGeneration" app/src/main 2>/dev/null \
    > "${RAW_DIR}/grep_generation_active.txt" || true

  {
    printf '```text\n'
    cat "${RAW_DIR}/grep_generation_active.txt"
    printf '\n```\n'
  } >> "$REPORT"

  if grep -q "Another generation is already active" "${RAW_DIR}/grep_generation_active.txt"; then
    pass "Found source of active-generation conflict message"
  else
    warn "Could not find exact active-generation conflict message in source"
  fi

  subsection "Search: generation job finalization/catch blocks"
  grep -RIn "generationJobs\.\|markStreaming\|markRunning\|complete(\|fail(\|cancel(" app/src/main/java 2>/dev/null \
    > "${RAW_DIR}/grep_generation_jobs.txt" || true

  {
    printf '```text\n'
    cat "${RAW_DIR}/grep_generation_jobs.txt"
    printf '\n```\n'
  } >> "$REPORT"
}

interpretation() {
  section "Interpretation and likely causes"

  cat >> "$REPORT" <<'EOM'
Use this interpretation guide:

1. If `/health` or `/api/admin/status` reports `activeGeneration=true`, the model manager itself believes inference is still running.
2. If `/health` reports no active generation but message sends return `generation_active`, the likely culprit is a stale `GenerationJobStore` active job.
3. If the diagnostic chat has active jobs before sending, the frontend may have refreshed or disconnected while the backend job remained `RUNNING` or `STREAMING`.
4. If the diagnostic chat has no active jobs but sends still return `generation_active`, the active job may belong to another chat because the backend guard may be global.
5. If SSE returns 200 but no `content` event appears before timeout, the backend may be buffering generation and only sending final content after model completion.
6. If SSE returns 409 with `Another generation is already active`, the backend guard rejected the request before generation started.
7. Restarting the Android app/server may clear in-memory stale generation jobs, but that is a workaround, not a fix.
8. A robust fix should ensure generation jobs are finalized on stream disconnect/error, active jobs expire after timeout, and admin/global generation diagnostics can cancel orphaned jobs.

EOM
}

summary() {
  section "Summary"

  {
    printf -- "- Pass: \`%s\`\n" "$PASS_COUNT"
    printf -- "- Warn: \`%s\`\n" "$WARN_COUNT"
    printf -- "- Fail: \`%s\`\n" "$FAIL_COUNT"
    printf -- "- Skip: \`%s\`\n" "$SKIP_COUNT"
    printf -- "- Report path: \`%s\`\n" "$REPORT"
    printf -- "- Raw artifacts: \`%s\`\n" "$RAW_DIR"
    printf '\n'
  } >> "$REPORT"

  console ""
  console "Report written to: $REPORT"
  console "Raw artifacts written to: $RAW_DIR"
  console "Counts: PASS=$PASS_COUNT WARN=$WARN_COUNT FAIL=$FAIL_COUNT SKIP=$SKIP_COUNT"
}

main() {
  init_report
  repo_context
  static_checks
  compile_checks
  source_scan_generation_code

  if api_intro; then
    if probe_health; then
      if obtain_auth; then
        probe_admin_status
        probe_admin_generations
        cancel_global_generations_if_requested
        create_or_use_chat
        list_chat_generations
        cancel_chat_generation_if_requested
        send_short_timeout_probe
        send_non_stream_probe
        send_stream_probe
        post_probe_generations
        probe_admin_generations_after
        assert_health_inactive "health_after_all_probes" || true
      else
        warn "Skipping authenticated API probes because auth failed"
      fi
    else
      warn "Skipping deeper API probes because health probe failed"
    fi
  fi

  interpretation
  summary

  # Do not use nonzero exit by default because this is diagnostic and should always write a report.
  # Set STRICT_EXIT=1 to exit nonzero if failures were observed.
  if [ "${STRICT_EXIT:-0}" = "1" ] && [ "$FAIL_COUNT" -gt 0 ]; then
    exit 1
  fi
  exit 0
}

main "$@"
