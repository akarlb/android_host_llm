#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_web_ui_smoke.md}"

USER_NAME="web_user_$(date +%s)"
USER_PASS="web-password-123"
USER_TOKEN=""
CHAT_ID=""
FILE_ID=""
RESPONSE_BODY=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Web UI Smoke Test Results"
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
  local content_type="${5:-application/json}"
  local response_file
  response_file="$(mktemp)"
  local headers=(-H "Content-Type: $content_type")
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

assert_html_route() {
  local path="$1"
  local marker="$2"
  local response_file
  response_file="$(mktemp)"
  local status
  status="$(curl -sS -o "$response_file" -w "%{http_code}" "$BASE_URL$path")"
  local body
  body="$(cat "$response_file")"
  rm -f "$response_file"
  [[ "$status" == "200" ]] || fail "GET $path returned $status: $body"
  grep -Fqi "<!doctype html>" <<<"$body" || fail "GET $path did not return HTML: $body"
  grep -Fq "$marker" <<<"$body" || fail "GET $path did not include marker '$marker'"
  pass "GET $path returns HTML"
}

assert_html_route /login "login-form"
assert_html_route /register "register-form"
assert_html_route /chat "message-form"

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "register user through API"

status="$(request POST /auth/login "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "login user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "login user through API"

status="$(request POST /api/chats '{"title":"Web UI smoke","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create chat returned $status: $RESPONSE_BODY"
CHAT_ID="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"
pass "create chat through API"

markdown_json="$(python3 -c 'import json
print(json.dumps({
  "filename": "web-ui-notes.md",
  "mimeType": "text/markdown",
  "content": "# Web UI Notes\nThe normal user UI uploads Markdown and selects files as chat context.\n"
}))')"
status="$(request POST /api/files/upload "$markdown_json" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "upload .md file returned $status: $RESPONSE_BODY"
FILE_ID="$(printf '%s' "$RESPONSE_BODY" | json_field file.id)"
pass "upload .md file through API"

stream_file="$(mktemp)"
status="$(
  curl -sS -o "$stream_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_TOKEN" \
    --data-binary "$(python3 -c "import json; print(json.dumps({'content':'What do the notes say the UI can do?', 'stream': True, 'fileIds':['$FILE_ID']}))")" \
    "$BASE_URL/api/chats/$CHAT_ID/messages"
)"
STREAM_BODY="$(cat "$stream_file")"
rm -f "$stream_file"
[[ "$status" == "200" ]] || fail "streaming context message returned $status: $STREAM_BODY"
grep -Fq "data: [DONE]" <<<"$STREAM_BODY" || fail "streaming response did not include [DONE]: $STREAM_BODY"
pass "streaming response includes [DONE]"

status="$(request POST /v1/chat/completions '{"model":"local-litert-lm","messages":[{"role":"user","content":"Reply with pong."}],"stream":false}')"
[[ "$status" == "200" ]] || fail "existing /v1 smoke test returned $status: $RESPONSE_BODY"
pass "existing /v1 smoke test"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
