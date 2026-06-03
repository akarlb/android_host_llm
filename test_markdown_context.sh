#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_markdown_context.md}"

USER_NAME="md_user_$(date +%s)"
OTHER_USER_NAME="md_other_$(date +%s)"
USER_PASS="markdown-password-123"
USER_TOKEN=""
OTHER_TOKEN=""
CHAT_ID=""
FILE_ID=""

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Markdown Context Test Results"
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

json_eval() {
  python3 -c 'import json,sys
obj=json.loads(sys.stdin.read())
print(eval(sys.argv[1], {}, {"obj": obj}))' "$1"
}

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "register/login user"

markdown_json="$(python3 -c 'import json
print(json.dumps({
  "filename": "project-notes.md",
  "mimeType": "text/markdown",
  "content": "# Intro\nThis project runs a phone-hosted local LLM.\n\n## Details\nMarkdown uploads are chunked deterministically for chat context.\n\n### Budget\nContext is capped and injected only into generation prompts.\n"
}))')"
status="$(request POST /api/files/upload "$markdown_json" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "upload valid markdown returned $status: $RESPONSE_BODY"
FILE_ID="$(printf '%s' "$RESPONSE_BODY" | json_field file.id)"
chunk_count="$(printf '%s' "$RESPONSE_BODY" | json_field file.chunkCount)"
[[ "$chunk_count" -ge 1 ]] || fail "uploaded file had no chunks: $RESPONSE_BODY"
pass "upload valid .md file"

status="$(request GET /api/files "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "list files returned $status: $RESPONSE_BODY"
found="$(printf '%s' "$RESPONSE_BODY" | json_eval "any(file['id'] == '$FILE_ID' for file in obj['files'])")"
[[ "$found" == "True" ]] || fail "uploaded file missing from list: $RESPONSE_BODY"
pass "file list includes upload"

status="$(request GET "/api/files/$FILE_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "file detail returned $status: $RESPONSE_BODY"
detail_chunks="$(printf '%s' "$RESPONSE_BODY" | json_eval "len(obj['chunks'])")"
[[ "$detail_chunks" -ge 1 ]] || fail "file detail returned no chunk metadata: $RESPONSE_BODY"
has_storage_path="$(printf '%s' "$RESPONSE_BODY" | json_eval "'storagePath' in obj['file']")"
[[ "$has_storage_path" == "False" ]] || fail "file detail exposed storage path: $RESPONSE_BODY"
pass "file detail returns chunk previews without storage path"

status="$(request POST /api/files/upload '{"filename":"bad.pdf","mimeType":"application/pdf","content":"%PDF"}' "$USER_TOKEN")"
[[ "$status" == "400" ]] || fail "non-md upload should be rejected, got $status: $RESPONSE_BODY"
pass "reject non-md upload"

oversized_file="$(mktemp)"
python3 -c 'import json,sys
json.dump({"filename":"huge.md","mimeType":"text/markdown","content":"a"*(2*1024*1024+1)}, open(sys.argv[1], "w"))' "$oversized_file"
response_file="$(mktemp)"
status="$(
  curl -sS -o "$response_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_TOKEN" \
    --data-binary "@$oversized_file" \
    "$BASE_URL/api/files/upload"
)"
RESPONSE_BODY="$(cat "$response_file")"
rm -f "$oversized_file" "$response_file"
[[ "$status" == "413" ]] || fail "oversized upload should return 413, got $status: $RESPONSE_BODY"
pass "reject oversized markdown"

status="$(request POST /api/chats '{"title":"Markdown context chat","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create chat returned $status: $RESPONSE_BODY"
CHAT_ID="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"
pass "create chat"

message_json="$(python3 -c "import json; print(json.dumps({'content':'What do the uploaded notes say about context?', 'stream': False, 'fileIds':['$FILE_ID']}))")"
status="$(request POST "/api/chats/$CHAT_ID/messages" "$message_json" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "non-streaming context message returned $status: $RESPONSE_BODY"
included_chunks="$(printf '%s' "$RESPONSE_BODY" | json_field context.includedChunks)"
[[ "$included_chunks" -ge 1 ]] || fail "context metadata did not include chunks: $RESPONSE_BODY"
role="$(printf '%s' "$RESPONSE_BODY" | json_field message.role)"
[[ "$role" == "assistant" ]] || fail "non-streaming response did not return assistant message: $RESPONSE_BODY"
pass "send non-streaming message with selected file context"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "get chat after context message returned $status: $RESPONSE_BODY"
stored_user_content="$(printf '%s' "$RESPONSE_BODY" | json_eval "next(message['content'] for message in obj['messages'] if message['role'] == 'user')")"
[[ "$stored_user_content" == "What do the uploaded notes say about context?" ]] || fail "expanded context was persisted instead of original user message: $RESPONSE_BODY"
pass "original user message persisted without expanded context"

stream_file="$(mktemp)"
status="$(
  curl -sS -o "$stream_file" -w "%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_TOKEN" \
    --data-binary "$(python3 -c "import json; print(json.dumps({'content':'Summarize the same notes in one sentence.', 'stream': True, 'fileIds':['$FILE_ID']}))")" \
    "$BASE_URL/api/chats/$CHAT_ID/messages"
)"
STREAM_BODY="$(cat "$stream_file")"
rm -f "$stream_file"
[[ "$status" == "200" ]] || fail "streaming context message returned $status: $STREAM_BODY"
grep -Fq '"context"' <<<"$STREAM_BODY" || fail "streaming response did not include context metadata: $STREAM_BODY"
grep -Fq "data: [DONE]" <<<"$STREAM_BODY" || fail "streaming response did not include [DONE]: $STREAM_BODY"
pass "send streaming message with selected file context"

status="$(request GET "/api/chats/$CHAT_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "get chat after stream returned $status: $RESPONSE_BODY"
assistant_count="$(printf '%s' "$RESPONSE_BODY" | json_eval "sum(1 for message in obj['messages'] if message['role'] == 'assistant')")"
[[ "$assistant_count" -ge 2 ]] || fail "assistant responses were not persisted: $RESPONSE_BODY"
pass "messages persist"

status="$(request POST /auth/register "{\"username\":\"$OTHER_USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register other user returned $status: $RESPONSE_BODY"
OTHER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
status="$(request GET "/api/files/$FILE_ID" "" "$OTHER_TOKEN")"
[[ "$status" == "404" ]] || fail "other user should not read file, got $status: $RESPONSE_BODY"
status="$(request DELETE "/api/files/$FILE_ID" "" "$OTHER_TOKEN")"
[[ "$status" == "404" ]] || fail "other user should not delete file, got $status: $RESPONSE_BODY"
pass "User A file is isolated from User B"

status="$(request DELETE "/api/files/$FILE_ID" "" "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "delete file returned $status: $RESPONSE_BODY"
status="$(request GET "/api/files/$FILE_ID" "" "$USER_TOKEN")"
[[ "$status" == "404" ]] || fail "deleted file should return 404, got $status: $RESPONSE_BODY"
pass "delete file removes it"

status="$(request POST /v1/chat/completions '{"model":"local-litert-lm","messages":[{"role":"user","content":"Reply with pong."}],"stream":false}')"
[[ "$status" == "200" ]] || fail "existing /v1 smoke test returned $status: $RESPONSE_BODY"
pass "existing /v1 smoke test"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
