#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
ADMIN_NAME="ops_admin_$(date +%s)"
USER_NAME="ops_user_$(date +%s)"
PASSWORD="${PASSWORD:-ops-test-password}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TOKEN=""
RESPONSE_BODY=""

request() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local response_file status
  response_file="$(mktemp)"
  local headers=(-H 'Content-Type: application/json')
  if [[ -n "$token" ]]; then headers+=(-H "Authorization: Bearer $token"); fi
  local args=(-sS -o "$response_file" -w "%{http_code}" -X "$method" "${headers[@]}")
  if [[ -n "$body" ]]; then args+=(-d "$body"); fi
  status="$(curl "${args[@]}" "$BASE_URL$path")"
  RESPONSE_BODY="$(cat "$response_file")"
  rm -f "$response_file"
  echo "$status"
}

json_value() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

status="$(request GET /health)"
[[ "$status" == "200" ]] || fail "health returned $status: $RESPONSE_BODY"
printf '%s' "$RESPONSE_BODY" | python3 -c 'import json,sys; j=json.load(sys.stdin); assert j["appAlive"] is True; assert "databaseAvailable" in j'
echo "health ok"

status="$(request POST /auth/register "{\"username\":\"$ADMIN_NAME\",\"password\":\"$PASSWORD\"}")"
[[ "$status" == "200" ]] || fail "register admin returned $status: $RESPONSE_BODY"
ADMIN_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_value token)"

status="$(request GET /api/admin/status "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "admin status returned $status: $RESPONSE_BODY"
echo "admin status ok"

status="$(request GET /api/admin/ops/export "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "backup export returned $status: $RESPONSE_BODY"
printf '%s' "$RESPONSE_BODY" > "$TMP_DIR/export.json"
python3 - <<'PY' "$TMP_DIR/export.json"
import json, sys
text=open(sys.argv[1], encoding='utf-8').read()
j=json.loads(text)
assert 'schemaVersion' in j and 'exportedAtMs' in j
for forbidden in ['password_hash', 'password_salt', 'token_hash', 'session', 'storage_path']:
    assert forbidden not in text.lower(), forbidden
assert 'users' in j and 'skills' in j and 'safeAppSettings' in j
PY
echo "backup export ok"

status="$(request GET /api/admin/ops/diagnostics "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "diagnostics returned $status: $RESPONSE_BODY"
printf '%s' "$RESPONSE_BODY" > "$TMP_DIR/diagnostics.json"
python3 - <<'PY' "$TMP_DIR/diagnostics.json"
import json, sys
text=open(sys.argv[1], encoding='utf-8').read()
j=json.loads(text)
assert 'health' in j and 'counts' in j and 'storageScan' in j
for forbidden in ['password_hash', 'password_salt', 'token_hash']:
    assert forbidden not in text.lower(), forbidden
PY
echo "diagnostics ok"

status="$(request GET /api/admin/ops/storage/scan "" "$ADMIN_TOKEN")"
[[ "$status" == "200" ]] || fail "storage scan returned $status: $RESPONSE_BODY"
printf '%s' "$RESPONSE_BODY" | python3 -c 'import json,sys; j=json.load(sys.stdin); assert j["cleanupConfirmation"]=="cleanup-orphans"; assert "orphanChunks" in j'
echo "storage scan ok"

status="$(request POST /api/admin/ops/storage/cleanup '{"confirm":"wrong"}' "$ADMIN_TOKEN")"
[[ "$status" == "400" ]] || fail "cleanup without confirmation should return 400, got $status: $RESPONSE_BODY"
echo "cleanup confirmation ok"

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}")"
[[ "$status" == "200" ]] || fail "register normal user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_value token)"

status="$(request GET /api/admin/ops/diagnostics "" "$USER_TOKEN")"
[[ "$status" == "403" ]] || fail "normal user diagnostics should be denied, got $status: $RESPONSE_BODY"
echo "normal user denial ok"

echo "local ops smoke complete"
