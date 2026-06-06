#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_admin_ui.md}"

STAMP="$(date +%s)"
ADMIN_NAME="admin_${STAMP}"
USER_NAME="user_${STAMP}"
ADMIN_PASS="admin-password-123"
USER_PASS="user-password-123"
ADMIN_TOKEN=""
USER_TOKEN=""
RESPONSE_BODY=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Admin UI Test Results"
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
    args+=(--data-binary "$body")
  fi
  local status
  status="$(curl "${args[@]}" "$BASE_URL$path")"
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

assert_html_with_token() {
  local path="$1"
  local token="$2"
  local marker="$3"
  local expected_status="${4:-200}"
  local response_file
  response_file="$(mktemp)"
  local status
  status="$(curl -sS -o "$response_file" -w "%{http_code}" -H "Authorization: Bearer $token" "$BASE_URL$path")"
  local body
  body="$(cat "$response_file")"
  rm -f "$response_file"
  [[ "$status" == "$expected_status" ]] || fail "GET $path returned $status, expected $expected_status: $body"
  grep -Fqi "<!doctype html>" <<<"$body" || fail "GET $path did not return HTML: $body"
  grep -Fq "$marker" <<<"$body" || fail "GET $path did not include marker '$marker'"
  pass "GET $path returns HTML containing $marker"
}

status="$(request POST /auth/register "{\"username\":\"$ADMIN_NAME\",\"password\":\"$ADMIN_PASS\"}")"
[[ "$status" == "200" ]] || fail "register first user returned $status: $RESPONSE_BODY"
ADMIN_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
ADMIN_ROLE="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$ADMIN_ROLE" == "ADMIN" ]] || fail "first registered user was $ADMIN_ROLE, expected ADMIN. Run against a fresh app data store."
pass "first registered user is admin"

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register second user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
USER_ROLE="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$USER_ROLE" == "USER" ]] || fail "second registered user was $USER_ROLE, expected USER"
pass "second registered user is normal user"

status="$(request GET /api/admin/status "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin status returned $status: $RESPONSE_BODY"
grep -Fq '"codingBaseUrl"' <<<"$RESPONSE_BODY" || fail "admin status omitted codingBaseUrl: $RESPONSE_BODY"
pass "admin can access /api/admin/status"

status="$(request GET /api/admin/status "" "$USER_TOKEN")"
[[ "$status" == "403" ]] || fail "normal user admin status returned $status: $RESPONSE_BODY"
pass "normal user cannot access /api/admin/status"

status="$(request GET /api/admin/status)"
[[ "$status" == "401" ]] || fail "unauthenticated admin status returned $status: $RESPONSE_BODY"
pass "unauthenticated request cannot access /api/admin/status"

status="$(request GET /api/admin/users "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin users returned $status: $RESPONSE_BODY"
grep -Fq '"users"' <<<"$RESPONSE_BODY" || fail "admin users omitted users: $RESPONSE_BODY"
grep -Fvq "password" <<<"$RESPONSE_BODY" || fail "admin users leaked password fields: $RESPONSE_BODY"
grep -Fvq "token" <<<"$RESPONSE_BODY" || fail "admin users leaked token fields: $RESPONSE_BODY"
pass "admin can access /api/admin/users without secrets"

status="$(request GET /api/admin/files "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin files returned $status: $RESPONSE_BODY"
grep -Fq '"files"' <<<"$RESPONSE_BODY" || fail "admin files omitted files: $RESPONSE_BODY"
grep -Fvq "storage_path" <<<"$RESPONSE_BODY" || fail "admin files leaked storage paths: $RESPONSE_BODY"
pass "admin can access /api/admin/files without storage paths"

status="$(request GET /api/admin/skills "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin skills returned $status: $RESPONSE_BODY"
grep -Fq '"systemPrompt"' <<<"$RESPONSE_BODY" || fail "admin skills omitted prompt metadata: $RESPONSE_BODY"
pass "admin can list full skill metadata"

CUSTOM_SLUG="admin-skill-${STAMP}"
skill_body="$(python3 - <<PY
import json
print(json.dumps({
  "slug": "$CUSTOM_SLUG",
  "displayName": "Admin Skill $STAMP",
  "description": "Created by admin UI smoke test.",
  "systemPrompt": "Answer briefly.",
  "toolUseMode": "OPTIONAL",
  "allowedTools": ["get_current_datetime"],
  "outputSchema": {"type": "object"},
  "strictOutput": False,
  "enabled": True
}))
PY
)"
status="$(request POST /api/admin/skills "$skill_body" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin create skill returned $status: $RESPONSE_BODY"
grep -Fq "\"slug\":\"$CUSTOM_SLUG\"" <<<"$RESPONSE_BODY" || fail "created skill response omitted slug: $RESPONSE_BODY"
pass "admin can create custom skill"

edit_body="$(python3 - <<PY
import json
print(json.dumps({
  "displayName": "Admin Skill Edited $STAMP",
  "description": "Edited by admin UI smoke test.",
  "systemPrompt": "Answer in one sentence.",
  "toolUseMode": "NONE",
  "allowedTools": [],
  "enabled": True
}))
PY
)"
status="$(request PUT "/api/admin/skills/$CUSTOM_SLUG" "$edit_body" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin edit skill returned $status: $RESPONSE_BODY"
grep -Fq "Admin Skill Edited" <<<"$RESPONSE_BODY" || fail "edited skill response omitted updated display name: $RESPONSE_BODY"
pass "admin can edit custom skill"

status="$(request GET /api/admin/tools "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin tools returned $status: $RESPONSE_BODY"
grep -Fq '"inputSchema"' <<<"$RESPONSE_BODY" || fail "admin tools omitted schemas: $RESPONSE_BODY"
pass "admin can list tool catalog with schemas"

status="$(request GET /api/admin/tools/logs "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin tool logs returned $status: $RESPONSE_BODY"
grep -Fq '"logs"' <<<"$RESPONSE_BODY" || fail "admin tool logs omitted logs: $RESPONSE_BODY"
pass "admin can list tool logs"

status="$(request GET /api/admin/skills "" "$USER_TOKEN")"
[[ "$status" == "403" ]] || fail "normal user admin skills returned $status: $RESPONSE_BODY"
pass "normal user cannot access admin skills"

status="$(request POST /api/admin/skills/test "{\"skillSlug\":\"$CUSTOM_SLUG\",\"prompt\":\"Hello\"}" "$ADMIN_TOKEN")"
[[ "$status" == "200" || "$status" == "503" ]] || fail "admin skill test returned $status: $RESPONSE_BODY"
pass "admin skill test endpoint is reachable and reports model-loaded state"

status="$(request DELETE "/api/admin/skills/$CUSTOM_SLUG" "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin delete skill returned $status: $RESPONSE_BODY"
pass "admin can delete custom skill"

assert_html_with_token /admin "$ADMIN_TOKEN" "admin-dashboard"
assert_html_with_token /admin "$ADMIN_TOKEN" "Skill Test Console"
assert_html_with_token /admin "$USER_TOKEN" "Access denied" "403"

admin_unauth_headers="$(mktemp)"
status="$(curl -sS -o /dev/null -D "$admin_unauth_headers" -w "%{http_code}" "$BASE_URL/admin")"
if [[ "$status" =~ ^30[0-9]$ ]]; then
  grep -Fiq "Location: /login" "$admin_unauth_headers" || fail "unauthenticated /admin redirect omitted Location: /login"
elif [[ "$status" == "401" || "$status" == "403" ]]; then
  true
else
  fail "unauthenticated /admin returned $status"
fi
rm -f "$admin_unauth_headers"
pass "unauthenticated /admin does not serve dashboard"

assert_html_with_token /chat "$USER_TOKEN" "message-form"

status="$(request GET /v1/models)"
[[ "$status" == "200" ]] || fail "/v1/models returned $status: $RESPONSE_BODY"
pass "existing /v1 models smoke test"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
