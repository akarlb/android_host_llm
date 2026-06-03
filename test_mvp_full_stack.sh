#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-${PHONE_IP:-127.0.0.1}}"
PORT="${2:-${PORT:-8080}}"
BASE_URL="${BASE_URL:-http://${HOST}:${PORT}}"
OUT="${OUT:-results_mvp_full_stack.md}"

STAMP="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

ADMIN_NAME="mvp_admin_${STAMP}"
USER_A_NAME="mvp_user_a_${STAMP}"
USER_B_NAME="mvp_user_b_${STAMP}"
PASS="mvp-password-123"
ADMIN_TOKEN=""
USER_A_TOKEN=""
USER_B_TOKEN=""
CHAT_ID=""
FILE_ID=""
RESPONSE_BODY=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# MVP Full Stack Test Results"
  echo
  echo "- Base URL: \`$BASE_URL\`"
  echo "- Started: \`$(python3 - <<'PY'
from datetime import datetime, timezone
print(datetime.now(timezone.utc).isoformat())
PY
)\`"
  echo
} > "$OUT"

log_result() {
  local status="$1"
  local name="$2"
  echo "$status $name"
  echo "- $status: $name" >> "$OUT"
}

pass() {
  log_result "PASS" "$1"
}

fail() {
  log_result "FAIL" "$1"
  if [[ -n "${RESPONSE_BODY:-}" ]]; then
    {
      echo
      echo "Last response:"
      echo
      echo '```json'
      printf '%s\n' "$RESPONSE_BODY"
      echo '```'
    } >> "$OUT"
  fi
  exit 1
}

ms_now() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
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
  local start end status
  start="$(ms_now)"
  status="$(curl "${args[@]}" "$BASE_URL$path")"
  end="$(ms_now)"
  RESPONSE_BODY="$(cat "$response_file")"
  rm -f "$response_file"
  echo "$status $((end - start))"
}

json_field() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
for part in sys.argv[1].split("."):
    obj=obj[part]
print(obj)' "$1"
}

json_eval() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
print(eval(sys.argv[1], {}, {"obj": obj}))' "$1"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local name="$3"
  [[ "$actual" == "$expected" ]] || fail "$name returned $actual, expected $expected"
  pass "$name (${actual})"
}

assert_json_no_secret_words() {
  local name="$1"
  if grep -Eiq '"?(password|password_hash|password_salt|token_hash|storage_path|storagePath|apiKey|huggingFaceToken|hfToken)"?' <<<"$RESPONSE_BODY"; then
    fail "$name leaked a secret field"
  fi
}

read -r status _ <<<"$(request GET /health)"
assert_status "$status" "200" "GET /health"
model_loaded="$(printf '%s' "$RESPONSE_BODY" | json_field modelLoaded)"
[[ "$model_loaded" == "True" ]] || fail "modelLoaded is $model_loaded; load the LiteRT-LM model before running full regression"
pass "model is loaded"

read -r status _ <<<"$(request POST /auth/register "{\"username\":\"$ADMIN_NAME\",\"password\":\"$PASS\"}")"
assert_status "$status" "200" "register first user"
ADMIN_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
admin_role="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$admin_role" == "ADMIN" ]] || fail "first user role is $admin_role, expected ADMIN. Run on a fresh app data store."
pass "first user is ADMIN"

read -r status _ <<<"$(request POST /auth/register "{\"username\":\"$USER_A_NAME\",\"password\":\"$PASS\"}")"
assert_status "$status" "200" "register normal user A"
USER_A_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
user_a_role="$(printf '%s' "$RESPONSE_BODY" | json_field user.role)"
[[ "$user_a_role" == "USER" ]] || fail "second user role is $user_a_role, expected USER"
pass "second user is USER"

read -r status _ <<<"$(request POST /auth/register "{\"username\":\"$USER_B_NAME\",\"password\":\"$PASS\"}")"
assert_status "$status" "200" "register normal user B"
USER_B_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "third user registered for isolation checks"

read -r status _ <<<"$(request POST /auth/login "{\"username\":\"$USER_A_NAME\",\"password\":\"$PASS\"}")"
assert_status "$status" "200" "login normal user"
USER_A_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"

read -r status _ <<<"$(request GET /api/admin/status "" "$ADMIN_TOKEN")"
assert_status "$status" "200" "admin GET /api/admin/status"
grep -Fq '"codingBaseUrl"' <<<"$RESPONSE_BODY" || fail "admin status omitted codingBaseUrl"
grep -Fq '"conversationBaseUrl"' <<<"$RESPONSE_BODY" || fail "admin status omitted conversationBaseUrl"
pass "admin status exposes Coding and Conversation URLs"

read -r status _ <<<"$(request GET /api/admin/status "" "$USER_A_TOKEN")"
assert_status "$status" "403" "normal user denied /api/admin/status"

read -r status _ <<<"$(request GET /api/admin/users "" "$ADMIN_TOKEN")"
assert_status "$status" "200" "admin GET /api/admin/users"
assert_json_no_secret_words "admin users"
pass "admin users response omits secrets"

read -r status _ <<<"$(request GET /api/admin/files "" "$ADMIN_TOKEN")"
assert_status "$status" "200" "admin GET /api/admin/files"
assert_json_no_secret_words "admin files"
pass "admin files response omits storage paths"

admin_html="$(mktemp)"
status="$(curl -sS -o "$admin_html" -w "%{http_code}" -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/admin")"
[[ "$status" == "200" ]] || fail "GET /admin as admin returned $status"
grep -Fq "admin-dashboard" "$admin_html" || fail "admin HTML omitted dashboard marker"
grep -Fq "trusted local networks" "$admin_html" || fail "admin HTML omitted LAN warning"
rm -f "$admin_html"
pass "admin page returns dashboard HTML"

normal_admin_html="$(mktemp)"
status="$(curl -sS -o "$normal_admin_html" -w "%{http_code}" -H "Authorization: Bearer $USER_A_TOKEN" "$BASE_URL/admin")"
[[ "$status" == "200" ]] || fail "GET /admin as normal user returned $status"
grep -Fq "Access denied" "$normal_admin_html" || fail "normal user admin HTML omitted access denied marker"
rm -f "$normal_admin_html"
pass "normal user sees access denied for /admin"

read -r status _ <<<"$(request POST /api/chats '{"title":"MVP full-stack chat","profile":"CONVERSATION"}' "$USER_A_TOKEN")"
assert_status "$status" "200" "create normal-user chat"
CHAT_ID="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"

read -r status _ <<<"$(request GET "/api/chats/$CHAT_ID" "" "$USER_B_TOKEN")"
assert_status "$status" "404" "User B cannot read User A chat"

stream_file="$(mktemp)"
start="$(ms_now)"
status="$(
  curl -sS -o "$stream_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_A_TOKEN" \
    --data-binary '{"content":"Reply with one short sentence for the regression test.","stream":true,"fileIds":[]}' \
    "$BASE_URL/api/chats/$CHAT_ID/messages"
)"
end="$(ms_now)"
STREAM_BODY="$(cat "$stream_file")"
rm -f "$stream_file"
[[ "$status" == "200" ]] || fail "streaming app-chat message returned $status"
grep -Fq "data: [DONE]" <<<"$STREAM_BODY" || fail "streaming app-chat response omitted [DONE]"
grep -Fvq '"error"' <<<"$STREAM_BODY" || fail "streaming app-chat response included error: $STREAM_BODY"
pass "streaming app chat completed in $((end - start)) ms"

read -r status _ <<<"$(request GET "/api/chats/$CHAT_ID" "" "$USER_A_TOKEN")"
assert_status "$status" "200" "refresh chat history"
message_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "len(obj['messages'])")"
[[ "$message_count" -ge 2 ]] || fail "chat history did not persist user and assistant messages"
pass "chat history persists after refresh"

markdown_json="$(python3 - <<'PY'
import json
print(json.dumps({
  "filename": "mvp-context.md",
  "mimeType": "text/markdown",
  "content": "# MVP Context\nThe project code word is cobalt-lantern.\n\n## Notes\nMarkdown context is injected into prompts but not persisted in user messages.\n"
}))
PY
)"
read -r status _ <<<"$(request POST /api/files/upload "$markdown_json" "$USER_A_TOKEN")"
assert_status "$status" "200" "upload markdown"
FILE_ID="$(printf '%s' "$RESPONSE_BODY" | json_field file.id)"
pass "uploaded markdown file appears with id $FILE_ID"

read -r status _ <<<"$(request GET /api/files "" "$USER_A_TOKEN")"
assert_status "$status" "200" "list markdown files"
found="$(printf '%s' "$RESPONSE_BODY" | json_eval "any(file['id'] == '$FILE_ID' for file in obj['files'])")"
[[ "$found" == "True" ]] || fail "uploaded markdown file missing from list"
pass "file list includes uploaded markdown"

read -r status _ <<<"$(request GET "/api/files/$FILE_ID" "" "$USER_B_TOKEN")"
assert_status "$status" "404" "User B cannot read User A file"

context_body="$(python3 - <<PY
import json
print(json.dumps({"content":"What is the project code word from the uploaded notes?","stream":False,"fileIds":["$FILE_ID"]}))
PY
)"
read -r status _ <<<"$(request POST "/api/chats/$CHAT_ID/messages" "$context_body" "$USER_A_TOKEN")"
assert_status "$status" "200" "ask with markdown context"
included_chunks="$(printf '%s' "$RESPONSE_BODY" | json_field context.includedChunks)"
[[ "$included_chunks" -ge 1 ]] || fail "markdown context did not include chunks"
pass "markdown context metadata reports included chunks"

read -r status _ <<<"$(request GET "/api/chats/$CHAT_ID" "" "$USER_A_TOKEN")"
assert_status "$status" "200" "reload chat after markdown context"
assistant_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'assistant')")"
[[ "$assistant_count" -ge 2 ]] || fail "assistant markdown-context response was not saved"
expanded_persisted="$(printf '%s' "$RESPONSE_BODY" | json_eval "any('cobalt-lantern' in message['content'] and message['role'] == 'user' for message in obj['messages'])")"
[[ "$expanded_persisted" == "False" ]] || fail "expanded markdown context leaked into persisted user messages"
pass "markdown context response persisted without expanded user context"

read -r status _ <<<"$(request POST /api/files/upload '{"filename":"bad.pdf","mimeType":"application/pdf","content":"%PDF"}' "$USER_A_TOKEN")"
assert_status "$status" "400" "reject unsupported upload"

oversized="$(mktemp)"
python3 -c 'import json,sys; json.dump({"filename":"huge.md","mimeType":"text/markdown","content":"x"*(2*1024*1024+1)}, open(sys.argv[1], "w"))' "$oversized"
response_file="$(mktemp)"
status="$(
  curl -sS -o "$response_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_A_TOKEN" \
    --data-binary "@$oversized" \
    "$BASE_URL/api/files/upload"
)"
RESPONSE_BODY="$(cat "$response_file")"
rm -f "$oversized" "$response_file"
[[ "$status" == "413" ]] || fail "oversized upload returned $status, expected 413"
pass "reject oversized markdown upload"

read -r status _ <<<"$(request DELETE "/api/files/$FILE_ID" "" "$USER_A_TOKEN")"
assert_status "$status" "200" "delete markdown file"
read -r status _ <<<"$(request GET "/api/files/$FILE_ID" "" "$USER_A_TOKEN")"
assert_status "$status" "404" "deleted markdown file disappears"

for path in /v1/models /coding/v1/models /conversation/v1/models; do
  read -r status _ <<<"$(request GET "$path")"
  assert_status "$status" "200" "GET $path"
done

chat_payload='{"model":"local-litert-lm","messages":[{"role":"user","content":"Reply with the word ok."}],"stream":false}'
read -r status _ <<<"$(request POST /v1/chat/completions "$chat_payload")"
assert_status "$status" "200" "POST /v1/chat/completions stream=false"
grep -Fq '"choices"' <<<"$RESPONSE_BODY" || fail "/v1 non-streaming response omitted choices"

for path in /v1/chat/completions /coding/v1/chat/completions /conversation/v1/chat/completions; do
  stream_file="$(mktemp)"
  status="$(
    curl -sS -o "$stream_file" -w "%{http_code}" \
      -X POST \
      -H "Content-Type: application/json" \
      --data-binary '{"model":"local-litert-lm","messages":[{"role":"user","content":"Say ok in one short sentence."}],"stream":true}' \
      "$BASE_URL$path"
  )"
  body="$(cat "$stream_file")"
  rm -f "$stream_file"
  [[ "$status" == "200" ]] || fail "POST $path stream=true returned $status"
  grep -Fq "data: [DONE]" <<<"$body" || fail "POST $path stream=true omitted [DONE]"
  grep -Fvq '"error"' <<<"$body" || fail "POST $path stream=true included error: $body"
  pass "POST $path stream=true"
done

preflight_file="$(mktemp)"
status="$(
  curl -sS -o "$preflight_file" -w "%{http_code}" \
    -X OPTIONS \
    -H "Origin: http://example.test" \
    -H "Access-Control-Request-Method: POST" \
    -H "Access-Control-Request-Private-Network: true" \
    -D "$preflight_file.headers" \
    "$BASE_URL/v1/chat/completions"
)"
headers="$(cat "$preflight_file.headers")"
rm -f "$preflight_file" "$preflight_file.headers"
[[ "$status" == "204" ]] || fail "OPTIONS /v1/chat/completions returned $status"
grep -Fiq "Access-Control-Allow-Origin: *" <<<"$headers" || fail "CORS preflight omitted allow-origin"
grep -Fiq "Access-Control-Allow-Private-Network: true" <<<"$headers" || fail "CORS preflight omitted Private Network Access"
pass "OPTIONS CORS/PNA preflight"

read -r status _ <<<"$(request GET /debug/perf)"
assert_status "$status" "200" "GET /debug/perf"
if grep -Eiq 'hugging|apiKey|token' <<<"$RESPONSE_BODY"; then
  fail "/debug/perf leaked token/API key wording"
fi
pass "/debug/perf omits token/API key fields"

read -r status _ <<<"$(request POST /debug/benchmark '{"prompt":"Reply with ok.","iterations":1,"stream":true,"resetBeforeEach":true,"conversationMode":"FRESH_PER_REQUEST","responseMode":"CODING_CONCISE"}')"
assert_status "$status" "200" "POST /debug/benchmark"
grep -Fq '"results"' <<<"$RESPONSE_BODY" || fail "/debug/benchmark omitted results"
pass "benchmark returns results"

{
  echo
  echo "## Final Checklist"
  echo
  echo "- PASS: Admin bootstrap API role check completed."
  echo "- PASS: Admin UI HTML and admin APIs checked."
  echo "- PASS: Normal user registration, login, chat, streaming, and persistence checked."
  echo "- PASS: Markdown upload, context metadata, unsupported upload, oversized upload, and delete checked."
  echo "- PASS: Role isolation checked for admin API, chats, and files."
  echo "- PASS: /v1, /coding/v1, /conversation/v1 model and streaming routes checked."
  echo "- PASS: CORS/PNA preflight checked."
  echo
  echo "## Manual Browser Checklist"
  echo
  echo "- Open \`$BASE_URL/register\` on a same-LAN browser."
  echo "- Confirm first user lands in chat and has admin navigation."
  echo "- Open \`$BASE_URL/admin\` and confirm model status plus Coding/Conversation URLs are visible."
  echo "- Login as a normal user at \`$BASE_URL/login\`, open \`$BASE_URL/chat\`, send a message, refresh, and confirm history remains."
  echo "- Upload a Markdown file, select it, ask about it, then delete it."
  echo "- Confirm normal users see Access denied at \`$BASE_URL/admin\`."
  echo
  echo "- Finished: \`$(python3 - <<'PY'
from datetime import datetime, timezone
print(datetime.now(timezone.utc).isoformat())
PY
)\`"
} >> "$OUT"

echo "Wrote $OUT"
