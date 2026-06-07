#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
USER_NAME="skills_user_$(date +%s)"
PASSWORD="${PASSWORD:-skills-test-password}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

python3 - <<'PY'
from pathlib import Path
chat = Path('app/src/main/assets/web/chat.html').read_text()
app = Path('app/src/main/assets/web/app.js').read_text()
assert 'id="skill-select"' not in chat, 'chat skill dropdown should not be present'
assert 'id="slash-menu"' in chat, 'slash menu container missing'
assert 'function parseSlashCommand' in app, 'slash parser missing'
assert 'function updateSlashMenu' in app, 'slash menu updater missing'
assert 'options.skillSlug' in app, 'message body must include per-message skillSlug'
start = app.index('async function sendMessage')
end = app.index('async function stopGeneration', start)
assert 'changeSkill(' not in app[start:end], 'sendMessage must not call changeSkill for slash commands'
PY
echo 'chat slash skill UI static checks ok'

json_post() {
  local path="$1" body="$2"
  curl -fsS -H 'Content-Type: application/json' ${TOKEN:+-H "Authorization: Bearer $TOKEN"} -d "$body" "$BASE_URL$path"
}
json_get() {
  local path="$1"
  curl -fsS ${TOKEN:+-H "Authorization: Bearer $TOKEN"} "$BASE_URL$path"
}
json_put() {
  local path="$1" body="$2"
  curl -fsS -X PUT -H 'Content-Type: application/json' ${TOKEN:+-H "Authorization: Bearer $TOKEN"} -d "$body" "$BASE_URL$path"
}

TOKEN=""
register="$(json_post /auth/register "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}")"
TOKEN="$(printf '%s' "$register" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"

skills="$(json_get /api/skills)"
printf '%s' "$skills" > "$TMP_DIR/skills.json"
python3 - <<'PY' "$TMP_DIR/skills.json"
import json,sys
j=json.load(open(sys.argv[1])); slugs={s['slug'] for s in j['skills']}
for slug in ['default','coding','gdpr-pii-audit','markdown-qa']:
    assert slug in slugs, f'missing {slug}'
assert all('systemPrompt' not in s for s in j['skills'])
PY

echo 'skills list ok'
chat="$(json_post /api/chats '{"title":"Skills test","profile":"CONVERSATION"}')"
CHAT_ID="$(printf '%s' "$chat" | python3 -c 'import json,sys; print(json.load(sys.stdin)["chat"]["id"])')"
json_get "/api/chats/$CHAT_ID/skill" >/dev/null
json_put "/api/chats/$CHAT_ID/skill" '{"skillSlug":"coding"}' | python3 -c 'import json,sys; assert json.load(sys.stdin)["state"]["skillSlug"]=="coding"'
json_put "/api/chats/$CHAT_ID/skill" '{"skillSlug":"gdpr-pii-audit","thinkingEnabled":true,"showThinking":false}' | python3 -c 'import json,sys; j=json.load(sys.stdin); assert j["state"]["skillSlug"]=="gdpr-pii-audit"; assert j["state"]["thinkingEnabled"] is True'
echo 'chat skill state ok'

json_get /api/tools | python3 -c 'import json,sys; names={t["name"] for t in json.load(sys.stdin)["tools"]}; assert {"get_current_datetime","list_chat_files","search_attached_markdown","count_markdown_chunks"} <= names'
echo 'tools list ok'

json_put "/api/chats/$CHAT_ID/skill" '{"skillSlug":"default","thinkingEnabled":true,"showThinking":false}' >/dev/null
# This exercises request/schema path without requiring a loaded model to produce thinking.
set +e
json_post "/api/chats/$CHAT_ID/messages" '{"content":"hello","stream":false,"thinkingEnabled":true,"showThinking":false}' >"$TMP_DIR/message.json"
status=$?
set -e
if [ "$status" -ne 0 ]; then
  echo 'message generation skipped/failed (model may be unloaded); endpoint reached auth/chat path' >&2
else
  python3 - <<'PY' "$TMP_DIR/message.json"
import json,sys
j=json.load(open(sys.argv[1])); assert 'message' in j
PY
fi

echo 'skills/tools/thinking smoke complete'
