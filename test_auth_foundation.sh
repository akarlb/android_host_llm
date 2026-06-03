#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_auth_foundation.md}"
RUN_CHAT="${RUN_CHAT:-1}"

ADMIN_USER="auth_admin_$(date +%s)"
USER_NAME="auth_user_$(date +%s)"
ADMIN_PASS="admin-password-123"
USER_PASS="user-password-123"

ADMIN_TOKEN=""
USER_TOKEN=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Auth Foundation Test Results"
  echo
  echo "- Base URL: \`$BASE_URL\`"
  echo "- Started: \`$(date -Is)\`"
  echo
} > "$OUT"

pass() {
  echo "PASS $1"
  echo "- PASS: $1" >> "$OUT"
}

fail() {
  echo "FAIL $1"
  echo "- FAIL: $1" >> "$OUT"
  exit 1
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local token="${4:-}"
  local response_file
  response_file="$(mktemp)"
  local headers=(-H "Content-Type: application/json")
  if [[ -n "$token" ]]; then
    headers+=(-H "Authorization: Bearer $token")
  fi
  local args=(-sS -o "$response_file" -w "%{http_code}" -X "$method" "${headers[@]}")
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  local status
  status="$(
    curl "${args[@]}" "$BASE_URL$path"
  )"
  RESPONSE_BODY="$(cat "$response_file")"
  rm -f "$response_file"
  echo "$status"
}

json_field() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
for part in sys.argv[1].split("."):
    obj=obj[part]
print(obj)' "$1"
}

status="$(request POST /auth/register "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")"
[[ "$status" == "200" ]] || fail "register first user returned $status: $RESPONSE_BODY"
ADMIN_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
role="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$role" == "ADMIN" ]] || fail "first user role was $role"
pass "first registered user is ADMIN"

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register second user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
role="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$role" == "USER" ]] || fail "second user role was $role"
pass "second registered user is USER"

status="$(request POST /auth/register "{\"username\":\"  ${USER_NAME^^}  \",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "409" ]] || fail "duplicate username returned $status: $RESPONSE_BODY"
pass "case-insensitive duplicate username returns 409"

status="$(request POST /auth/login "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "valid login returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "valid login succeeds"

status="$(request POST /auth/login "{\"username\":\"$USER_NAME\",\"password\":\"wrong-password\"}")"
[[ "$status" == "401" ]] || fail "invalid password returned $status: $RESPONSE_BODY"
pass "invalid password returns 401"

status="$(request GET /auth/session "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "session returned $status: $RESPONSE_BODY"
authenticated="$(printf '%s' "$RESPONSE_BODY" | json_field authenticated)"
[[ "$authenticated" == "True" ]] || fail "session did not authenticate: $RESPONSE_BODY"
pass "/auth/session resolves token"

status="$(request POST /auth/logout "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "logout returned $status: $RESPONSE_BODY"
status="$(request GET /auth/session "" "$USER_TOKEN")"
authenticated="$(printf '%s' "$RESPONSE_BODY" | json_field authenticated)"
[[ "$authenticated" == "False" ]] || fail "session still valid after logout: $RESPONSE_BODY"
pass "/auth/logout invalidates session"

status="$(request GET /health)"
[[ "$status" == "200" ]] || fail "/health returned $status: $RESPONSE_BODY"
pass "/health still works"

status="$(request GET /v1/models)"
[[ "$status" == "200" ]] || fail "/v1/models returned $status: $RESPONSE_BODY"
pass "/v1/models still works"

if [[ "$RUN_CHAT" == "1" ]]; then
  status="$(request POST /v1/chat/completions '{"stream":true,"messages":[{"role":"user","content":"Reply with one short sentence."}]}')"
  [[ "$status" == "200" ]] || fail "streaming chat returned $status: $RESPONSE_BODY"
  pass "basic streaming chat returns 200"
else
  echo "SKIP basic streaming chat because RUN_CHAT=$RUN_CHAT"
  echo "- SKIP: basic streaming chat because \`RUN_CHAT=$RUN_CHAT\`" >> "$OUT"
fi

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
