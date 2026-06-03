#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_chat_api.md}"

USER_NAME="chat_user_$(date +%s)"
OTHER_USER_NAME="chat_other_$(date +%s)"
USER_PASS="chat-password-123"
USER_TOKEN=""
OTHER_TOKEN=""
CHAT_ID=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Chat API Test Results"
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

json_eval() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
print(eval(sys.argv[1], {}, {"obj": obj}))' "$1"
}

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "register/login user"

status="$(request POST /api/chats '{"title":"API test chat","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create chat returned $status: $RESPONSE_BODY"
CHAT_ID="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"
pass "create chat"

status="$(request GET /api/chats "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "list chats returned $status: $RESPONSE_BODY"
found="$(printf '%s' "$RESPONSE_BODY" | json_eval "any(chat['id'] == '$CHAT_ID' for chat in obj['chats'])")"
[[ "$found" == "True" ]] || fail "created chat missing from list: $RESPONSE_BODY"
pass "list chats includes created chat"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "get chat returned $status: $RESPONSE_BODY"
message_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "len(obj['messages'])")"
[[ "$message_count" == "0" ]] || fail "new chat should have no messages: $RESPONSE_BODY"
pass "get chat returns metadata and empty messages"

status="$(request POST /auth/register "{\"username\":\"$OTHER_USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register other user returned $status: $RESPONSE_BODY"
OTHER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
status="$(request GET "/api/chats/$CHAT_ID" "" "$OTHER_TOKEN")"
[[ "$status" == "404" ]] || fail "other user should not read chat, got $status: $RESPONSE_BODY"
pass "other users cannot read this chat"

status="$(request POST "/api/chats/$CHAT_ID/messages" '{"content":"Reply with the single word pong.","stream":false,"fileIds":[]}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "non-streaming message returned $status: $RESPONSE_BODY"
role="$(printf '%s' "$RESPONSE_BODY" | json_field message.role)"
[[ "$role" == "assistant" ]] || fail "non-streaming response did not return assistant message: $RESPONSE_BODY"
pass "send non-streaming message"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "get chat after non-stream returned $status: $RESPONSE_BODY"
user_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'user')")"
assistant_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'assistant')")"
[[ "$user_count" -ge 1 && "$assistant_count" -ge 1 ]] || fail "non-stream messages were not persisted: $RESPONSE_BODY"
pass "user and assistant messages persisted"

stream_file="$(mktemp)"
status="$(
  curl -sS -o "$stream_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -d '{"content":"Reply with a short sentence.","stream":true,"fileIds":[]}' \
    "$BASE_URL/api/chats/$CHAT_ID/messages"
)"
STREAM_BODY="$(cat "$stream_file")"
rm -f "$stream_file"
[[ "$status" == "200" ]] || fail "streaming message returned $status: $STREAM_BODY"
grep -Fq "data: [DONE]" <<<"$STREAM_BODY" || fail "streaming response did not include [DONE]: $STREAM_BODY"
grep -Fvq "FAILED_PRECONDITION" <<<"$STREAM_BODY" || fail "streaming response hit LiteRT-LM session precondition: $STREAM_BODY"
pass "streaming message returns [DONE]"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "get chat after stream returned $status: $RESPONSE_BODY"
user_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'user')")"
assistant_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'assistant')")"
[[ "$user_count" -ge 2 && "$assistant_count" -ge 2 ]] || fail "streaming assistant response was not persisted: $RESPONSE_BODY"
pass "streaming assistant response persisted"

status="$(request DELETE "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "delete chat returned $status: $RESPONSE_BODY"
pass "delete/archive chat"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "404" ]] || fail "archived chat should return 404, got $status: $RESPONSE_BODY"
pass "archived chat is hidden"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
