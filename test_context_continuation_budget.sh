#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
OUT="${OUT:-results_context_continuation_budget.md}"
WORK_DIR="$(mktemp -d)"
USER_NAME="continuation_user_$(date +%s)"
USER_PASS="continuation-password-123"
USER_TOKEN=""
CHAT_ID=""

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

{
  echo "# Context Continuation Budget Test Results"
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

generate_markdown() {
  local path="$1"
  local title="$2"
  python3 - "$path" "$title" <<'PY'
import sys
path, title = sys.argv[1], sys.argv[2]
with open(path, "w", encoding="utf-8") as f:
    f.write(f"# {title}\n\n")
    for section in range(1, 90):
        f.write(f"## Section {section}: {title} planning notes\n\n")
        for item in range(1, 10):
            f.write(
                f"Section {section} item {item} describes requirements, risks, implementation details, "
                f"acceptance checks, continuation notes, and operational context for {title}. "
                f"This paragraph is intentionally repetitive enough to create a large Markdown file.\n\n"
            )
PY
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

stream_message() {
  local chat_id="$1"
  local content="$2"
  local file_ids_json="$3"
  local stream_file
  stream_file="$(mktemp)"
  local payload
  payload="$(python3 - "$content" "$file_ids_json" <<'PY'
import json, sys
print(json.dumps({"content": sys.argv[1], "stream": True, "fileIds": json.loads(sys.argv[2])}))
PY
)"
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
  assert_no_raw_context_error "$STREAM_BODY"
}

assert_no_raw_context_error() {
  local body="$1"
  local forbidden=(
    "Input token ids are too long"
    "maximum number of tokens allowed"
    "token limit"
    "context length"
    "prompt too long"
    "Generation failed"
    "4111 >= 4096"
  )
  for pattern in "${forbidden[@]}"; do
    if grep -Fqi "$pattern" <<<"$body"; then
      fail "raw context/token error leaked: $pattern"
    fi
  done
}

status="$(request POST /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")"
[[ "$status" == "200" ]] || fail "register user returned $status: $RESPONSE_BODY"
USER_TOKEN="$(printf '%s' "$RESPONSE_BODY" | json_field token)"
pass "register/login user"

status="$(request POST /api/chats '{"title":"Continuation budget chat","profile":"CONVERSATION"}' "$USER_TOKEN")"
[[ "$status" == "200" ]] || fail "create chat returned $status: $RESPONSE_BODY"
CHAT_ID="$(printf '%s' "$RESPONSE_BODY" | json_field chat.id)"
pass "create chat"

generate_markdown "$WORK_DIR/large-one.md" "Large One"
generate_markdown "$WORK_DIR/large-two.md" "Large Two"
FILE_ONE="$(upload_markdown "$WORK_DIR/large-one.md" "large-one.md")"
FILE_TWO="$(upload_markdown "$WORK_DIR/large-two.md" "large-two.md")"
pass "upload large Markdown files"

stream_message "$CHAT_ID" "Summarize this file in detail. Give as much detail as possible." "[\"$FILE_ONE\"]"
pass "large file first question streams to [DONE] without raw token errors"

stream_message "$CHAT_ID" "continue" "[\"$FILE_ONE\"]"
grep -Fq '"continuationMode":true' <<<"$STREAM_BODY" || fail "continue response missing continuation metadata: $STREAM_BODY"
pass "large file continue streams with continuation metadata"

for index in 1 2 3; do
  stream_message "$CHAT_ID" "continue" "[\"$FILE_ONE\"]"
done
pass "repeated continue does not expose raw token/context errors"

stream_message "$CHAT_ID" "Compare both selected files and summarize the most important sections." "[\"$FILE_ONE\",\"$FILE_TWO\"]"
stream_message "$CHAT_ID" "continue" "[\"$FILE_ONE\",\"$FILE_TWO\"]"
grep -Fq '"continuationMode":true' <<<"$STREAM_BODY" || fail "multi-file continue missing continuation metadata: $STREAM_BODY"
pass "multi-file continue does not expose raw token/context errors"

if rg -n 'message-role|>User<|>Assistant<' app/src/main/assets/web; then
  fail "visible chat bubble labels remain in frontend assets"
fi
pass "frontend static check has no visible User/Assistant bubble labels"

{
  echo
  echo "- Finished: \`$(date -Is)\`"
} >> "$OUT"

echo "Wrote $OUT"
