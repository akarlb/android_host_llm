#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_chat_scoped_files_and_markdown.md}"
WORK_DIR="$(mktemp -d)"
USER_NAME="chat_files_user_$(date +%s)"
USER_PASS="chat-files-password-123"
USER_TOKEN=""
CHAT_A=""
CHAT_B=""
FILE_A=""
FILE_B=""
RESPONSE_BODY=""
STREAM_BODY=""

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Chat-Scoped Files and Markdown Test Results"
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

json_string() {
  python3 -c 'import json,sys
print(json.dumps(sys.stdin.read()))'
}

json_file_ids() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
print("\n".join(file["id"] for file in obj.get("files", [])))'
}

json_context_file_ids() {
  python3 -c 'import json,sys,re
ids=[]
for match in re.finditer(r"data:\s*(\{.*\})", sys.stdin.read()):
    obj=json.loads(match.group(1))
    if "context" in obj:
        ids=obj["context"].get("fileIds", ids)
print("\n".join(ids))'
}

upload_markdown() {
  local path="$1"
  local name="$2"
  local content
  content="$(json_string < "$path")"
  local status
  status="$(request POST /api/files/upload "{\"filename\":\"$name\",\"mimeType\":\"text/markdown\",\"content\":$content}" "$USER_TOKEN")"
  [[ "$status" == "200" ]] || fail "upload $name returned $status: $RESPONSE_BODY"
  printf '%s' "$RESPONSE_BODY" | json_field file.id
}

attach_file() {
  local chat_id="$1"
  local file_id="$2"
  local status
  status="$(request POST "/api/chats/$chat_id/files" "{\"fileId\":\"$file_id\"}" "$USER_TOKEN")"
  [[ "$status" == "200" ]] || fail "attach $file_id to $chat_id returned $status: $RESPONSE_BODY"
}

chat_file_ids() {
  local chat_id="$1"
  local status
  status="$(request GET "/api/chats/$chat_id/files" "" "$USER_TOKEN")"
  [[ "$status" == "200" ]] || fail "list chat files for $chat_id returned $status: $RESPONSE_BODY"
  printf '%s' "$RESPONSE_BODY" | json_file_ids
}

stream_message_raw() {
  local chat_id="$1"
  local payload="$2"
  local stream_file
  stream_file="$(mktemp)"
  local status
  status="$(
    curl -sS -o "$stream_file" -w "%{http_code}" \
      -X POST \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $USER_TOKEN" \
      -d "$payload" \
      "$BASE_URL/api/chats/$chat_id/messages"
  )"
  STREAM_BODY="$(cat "$stream_file")"
  rm -f "$stream_file"
  [[ "$status" == "200" ]] || fail "stream message returned $status: $STREAM_BODY"
  grep -Fq "data: [DONE]" <<<"$STREAM_BODY" || fail "stream response did not include [DONE]: $STREAM_BODY"
}

static_checks() {
  ! rg -n 'id="refresh-files-button"|id="file-list"|class="files-panel"' app/src/main/assets/web/chat.html >/tmp/chat_static_files.$$ \
    || fail "normal chat sidebar still contains file workflow: $(cat /tmp/chat_static_files.$$)"
  rg -n 'id="file-upload".*type="file"|type="file".*id="file-upload"' app/src/main/assets/web/chat.html >/dev/null \
    || fail "composer upload input missing"
  rg -n 'renderMarkdown\(content\)|function renderMarkdown|sanitizeMarkdownUrl|startsWith\("javascript:"\)' app/src/main/assets/web/app.js >/dev/null \
    || fail "safe Markdown renderer static checks failed"
  rg -n '\.message-content pre|\.message-content code|\.message-content ul|\.message-content ol' app/src/main/assets/web/styles.css >/dev/null \
    || fail "Markdown code/list CSS missing"
  ! rg -n 'message-role|>User<|>Assistant<' app/src/main/assets/web >/tmp/chat_static_labels.$$ \
    || fail "visible User/Assistant bubble labels remain: $(cat /tmp/chat_static_labels.$$)"
  pass "frontend static checks"
}

static_checks

if [[ "${CHAT_SCOPED_STATIC_ONLY:-0}" == "1" ]]; then
  {
    echo
    echo "- Finished static-only run: \`$(date -Is)\`"
  } >> "$OUT"
  echo "Wrote $OUT"
  exit 0
fi

printf '# File A\n\nUnique alpha context for Chat A.\n' > "$WORK_DIR/a.md"
printf '# File B\n\nUnique beta context for Chat B.\n' > "$WORK_DIR/b.md"

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "register/login user"

status="$(request POST /api/chats '{"title":"Chat A","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create Chat A returned $status: $RESPONSE_BODY"
CHAT_A="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"

status="$(request POST /api/chats '{"title":"Chat B","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create Chat B returned $status: $RESPONSE_BODY"
CHAT_B="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"
pass "create two chats"

FILE_A="$(upload_markdown "$WORK_DIR/a.md" "chat-a.md")"
FILE_B="$(upload_markdown "$WORK_DIR/b.md" "chat-b.md")"
attach_file "$CHAT_A" "$FILE_A"
pass "upload and attach file A to Chat A"

[[ "$(chat_file_ids "$CHAT_A")" == "$FILE_A" ]] || fail "Chat A did not only contain file A"
[[ -z "$(chat_file_ids "$CHAT_B")" ]] || fail "Chat B unexpectedly inherited Chat A file"
attach_file "$CHAT_B" "$FILE_B"
[[ "$(chat_file_ids "$CHAT_A")" == "$FILE_A" ]] || fail "Chat A changed after attaching file B to Chat B"
[[ "$(chat_file_ids "$CHAT_B")" == "$FILE_B" ]] || fail "Chat B did not only contain file B"
pass "attachments are scoped per chat"

stream_message_raw "$CHAT_A" '{"content":"Summarize the attached file.","stream":true}'
grep -Fxq "$FILE_A" <<<"$(printf '%s' "$STREAM_BODY" | json_context_file_ids)" \
  || fail "omitted fileIds did not use Chat A attachment context: $STREAM_BODY"
pass "omitted fileIds uses chat attachments"

stream_message_raw "$CHAT_A" '{"content":"Answer without file context.","stream":true,"fileIds":[]}'
[[ -z "$(printf '%s' "$STREAM_BODY" | json_context_file_ids)" ]] \
  || fail "explicit empty fileIds still used file context: $STREAM_BODY"
pass "explicit empty fileIds disables file context"

status="$(request DELETE "/api/chats/$CHAT_A/files/$FILE_A" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "detach file returned $status: $RESPONSE_BODY"
[[ -z "$(chat_file_ids "$CHAT_A")" ]] || fail "detached file still appears on Chat A"
status="$(request GET "/api/files/$FILE_A" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "detaching deleted the underlying file: $RESPONSE_BODY"
pass "detach removes only chat attachment"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
