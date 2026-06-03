#!/usr/bin/env bash

set -u
set -o pipefail

PHONE_IP="${1:-192.168.0.164}"
PORT="${2:-8080}"
BASE_URL="http://${PHONE_IP}:${PORT}"
V1_URL="${BASE_URL}/v1"
RESULTS_FILE="${3:-results.md}"

ITERATIONS="${ITERATIONS:-3}"

SHORT_PROMPT='Explain Kotlin unresolved reference in 3 short bullets.'
LONG_PROMPT='I have an Android Kotlin app using NanoHTTPD. A POST /v1/chat/completions route works with curl, but a browser extension says Failed to fetch. Give me the likely causes and the exact server-side headers/routes I should check.'

START_TIME="$(date '+%Y-%m-%d %H:%M:%S')"

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------

line() {
  printf '%s\n' "$*" | tee -a "$RESULTS_FILE"
}

blank() {
  printf '\n' | tee -a "$RESULTS_FILE"
}

section() {
  blank
  line "## $1"
  blank
}

subsection() {
  blank
  line "### $1"
  blank
}

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

code_block_start() {
  line '```'
}

code_block_end() {
  line '```'
  blank
}

run_and_record() {
  local title="$1"
  shift

  subsection "$title"

  line "**Command:**"
  code_block_start
  printf '%q ' "$@" | tee -a "$RESULTS_FILE"
  printf '\n' | tee -a "$RESULTS_FILE"
  code_block_end

  line "**Output:**"
  code_block_start

  local start_ms
  local end_ms
  local duration_ms
  local output
  local exit_code

  start_ms="$(now_ms)"

  output="$("$@" 2>&1)"
  exit_code=$?

  end_ms="$(now_ms)"

  duration_ms=$((end_ms - start_ms))

  printf '%s\n' "$output" | tee -a "$RESULTS_FILE"
  code_block_end

  line "**Exit code:** \`$exit_code\`"
  line "**Duration:** \`${duration_ms}ms\`"

  if [ "$exit_code" -eq 0 ]; then
    line "**Result:** PASS"
  else
    line "**Result:** FAIL"
  fi

  blank

  return "$exit_code"
}

curl_json_get() {
  local path="$1"
  curl -sS -i "${BASE_URL}${path}"
}

curl_json_post() {
  local path="$1"
  local json="$2"
  curl -sS -i -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    -d "$json"
}

curl_stream_post() {
  local path="$1"
  local json="$2"
  local max_time="${3:-90}"

  curl -N --max-time "$max_time" -sS -i -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "$json"
}

curl_options() {
  local path="$1"
  curl -sS -i -X OPTIONS "${BASE_URL}${path}" \
    -H "Origin: http://example.com" \
    -H "Access-Control-Request-Method: POST" \
    -H "Access-Control-Request-Headers: content-type" \
    -H "Access-Control-Request-Private-Network: true"
}

# ------------------------------------------------------------
# Init results.md
# ------------------------------------------------------------

cat > "$RESULTS_FILE" <<EOF
# LiteRT-LM Android Server Test Results

**Started:** $START_TIME  
**Base URL:** \`$BASE_URL\`  
**V1 URL:** \`$V1_URL\`  
**Benchmark iterations:** \`$ITERATIONS\`  

EOF

line "Running test sequence against: $BASE_URL"
line "Writing results to: $RESULTS_FILE"

# ------------------------------------------------------------
# Preflight
# ------------------------------------------------------------

section "0. Local Test Environment"

run_and_record "curl version" curl --version

if command -v jq >/dev/null 2>&1; then
  run_and_record "jq version" jq --version
else
  subsection "jq version"
  line "jq not found. JSON will not be pretty-printed, but tests will still run."
fi

# ------------------------------------------------------------
# Basic reachability and routes
# ------------------------------------------------------------

section "1. Basic Reachability / Routes"

run_and_record "GET /" \
  bash -lc "curl -sS -i '${BASE_URL}/'"

run_and_record "GET /routes" \
  bash -lc "curl -sS -i '${BASE_URL}/routes'"

run_and_record "GET /v1" \
  bash -lc "curl -sS -i '${BASE_URL}/v1'"

run_and_record "GET /coding" \
  bash -lc "curl -sS -i '${BASE_URL}/coding'"

run_and_record "GET /coding/v1" \
  bash -lc "curl -sS -i '${BASE_URL}/coding/v1'"

run_and_record "GET /conversation" \
  bash -lc "curl -sS -i '${BASE_URL}/conversation'"

run_and_record "GET /conversation/v1" \
  bash -lc "curl -sS -i '${BASE_URL}/conversation/v1'"

run_and_record "GET /health" \
  bash -lc "curl -sS -i '${BASE_URL}/health'"

run_and_record "GET /v1/models" \
  bash -lc "curl -sS -i '${BASE_URL}/v1/models'"

run_and_record "GET /coding/v1/models" \
  bash -lc "curl -sS -i '${BASE_URL}/coding/v1/models'"

run_and_record "GET /conversation/v1/models" \
  bash -lc "curl -sS -i '${BASE_URL}/conversation/v1/models'"

run_and_record "GET /debug/routes" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/routes'"

# ------------------------------------------------------------
# CORS / PNA
# ------------------------------------------------------------

section "2. Browser CORS / Chrome Private Network Access"

run_and_record "OPTIONS /v1/chat/completions with Private Network Access preflight" \
  bash -lc "curl -sS -i -X OPTIONS '${BASE_URL}/v1/chat/completions' \
    -H 'Origin: http://example.com' \
    -H 'Access-Control-Request-Method: POST' \
    -H 'Access-Control-Request-Headers: content-type' \
    -H 'Access-Control-Request-Private-Network: true'"

run_and_record "OPTIONS /v1/models" \
  bash -lc "curl -sS -i -X OPTIONS '${BASE_URL}/v1/models' \
    -H 'Origin: http://example.com' \
    -H 'Access-Control-Request-Method: GET' \
    -H 'Access-Control-Request-Private-Network: true'"

# ------------------------------------------------------------
# Config / perf before generation
# ------------------------------------------------------------

section "3. Config / Performance Before Generation"

run_and_record "GET /debug/config" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/config'"

run_and_record "GET /debug/perf before generation" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf'"

run_and_record "GET /debug/perf/history before generation" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf/history'"

# ------------------------------------------------------------
# OpenAI endpoint behavior
# ------------------------------------------------------------

section "4. OpenAI-Compatible Endpoint Checks"

run_and_record "GET /v1/chat/completions should return 405 Method Not Allowed" \
  bash -lc "curl -sS -i '${BASE_URL}/v1/chat/completions'"

run_and_record "POST /v1/chat/completions non-streaming prompt style" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/v1/chat/completions' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"Hello. Confirm you are running locally on the phone.\"}'"

run_and_record "POST /v1/chat/completions non-streaming messages style" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/v1/chat/completions' \
    -H 'Content-Type: application/json' \
    -d '{\"model\":\"local-litert-lm\",\"messages\":[{\"role\":\"user\",\"content\":\"Explain Kotlin unresolved reference in 3 short bullets.\"}],\"stream\":false}'"

# ------------------------------------------------------------
# Streaming
# ------------------------------------------------------------

section "5. Streaming Test"

run_and_record "POST /v1/chat/completions stream=true with curl -N" \
  bash -lc "curl -N --max-time 90 -sS -i -X POST '${BASE_URL}/v1/chat/completions' \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d '{\"model\":\"local-litert-lm\",\"messages\":[{\"role\":\"user\",\"content\":\"Write 5 short numbered lines.\"}],\"stream\":true}'"

run_and_record "POST /coding/v1/chat/completions stream=true with curl -N" \
  bash -lc "curl -N --max-time 90 -sS -i -X POST '${BASE_URL}/coding/v1/chat/completions' \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d '{\"model\":\"local-litert-lm\",\"messages\":[{\"role\":\"user\",\"content\":\"Fix this Kotlin unresolved reference error in 5 bullets.\"}],\"stream\":true}'"

run_and_record "POST /conversation/v1/chat/completions stream=true with curl -N" \
  bash -lc "curl -N --max-time 90 -sS -i -X POST '${BASE_URL}/conversation/v1/chat/completions' \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d '{\"model\":\"local-litert-lm\",\"messages\":[{\"role\":\"user\",\"content\":\"Can you explain what unresolved reference means?\"}],\"stream\":true}'"

run_and_record "GET /debug/perf after streaming generation" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf'"

run_and_record "GET /debug/perf/history after streaming generation" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf/history'"

# ------------------------------------------------------------
# Reset conversation
# ------------------------------------------------------------

section "6. Conversation Reset"

run_and_record "POST /v1/conversation/reset" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/v1/conversation/reset'"

run_and_record "GET /debug/perf after reset" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf'"

# ------------------------------------------------------------
# Config update tests
# ------------------------------------------------------------

section "7. Remote Config Tests"

run_and_record "POST /debug/config set sessionProfile CODING" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CODING\"}'"

run_and_record "POST /debug/config set sessionProfile CONVERSATION" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CONVERSATION\"}'"

run_and_record "POST /debug/config set CUSTOM PERSISTENT + CODING_CONCISE" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CUSTOM\",\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"CODING_CONCISE\",\"resetPolicy\":\"MANUAL_ONLY\",\"generationTimeoutSeconds\":180}'"

run_and_record "GET /debug/config after setting custom persistent concise" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/config'"

run_and_record "POST /debug/config invalid enum should return clear error" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"conversationMode\":\"INVALID_MODE\"}'"

# Restore known-good mode
run_and_record "POST /debug/config restore known-good CODING profile" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CODING\"}'"

# ------------------------------------------------------------
# Benchmarks
# ------------------------------------------------------------

section "8. Benchmarks"

run_and_record "GET /debug/benchmark/presets" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/benchmark/presets'"

run_and_record "Benchmark: sessionProfile CODING + stream=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CODING\",\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true}'"

run_and_record "Benchmark: sessionProfile CONVERSATION + stream=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CONVERSATION\",\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true}'"

run_and_record "Benchmark: PERSISTENT + CODING_CONCISE + stream=true + resetBeforeEach=false" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":false,\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"CODING_CONCISE\"}'"

run_and_record "Benchmark: PERSISTENT + CODING_CONCISE + stream=true + resetBeforeEach=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":true,\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"CODING_CONCISE\"}'"

run_and_record "Benchmark: FRESH_PER_REQUEST + CODING_CONCISE + stream=true + resetBeforeEach=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":true,\"conversationMode\":\"FRESH_PER_REQUEST\",\"responseMode\":\"CODING_CONCISE\"}'"

run_and_record "Benchmark: PERSISTENT + BALANCED + stream=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":true,\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"BALANCED\"}'"

run_and_record "Benchmark: PERSISTENT + DETAILED + stream=true" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${SHORT_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":true,\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"DETAILED\"}'"

run_and_record "Benchmark: realistic coding prompt / PERSISTENT + CODING_CONCISE" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/benchmark' \
    -H 'Content-Type: application/json' \
    -d '{\"prompt\":\"${LONG_PROMPT}\",\"iterations\":${ITERATIONS},\"stream\":true,\"resetBeforeEach\":true,\"conversationMode\":\"PERSISTENT\",\"responseMode\":\"CODING_CONCISE\"}'"

# Restore known-good config after benchmarks
section "9. Restore Known-Good Runtime Config"

run_and_record "Restore config: CODING profile" \
  bash -lc "curl -sS -i -X POST '${BASE_URL}/debug/config' \
    -H 'Content-Type: application/json' \
    -d '{\"sessionProfile\":\"CODING\"}'"

run_and_record "GET /debug/config final" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/config'"

run_and_record "GET /debug/perf final" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf'"

run_and_record "GET /debug/perf/history final" \
  bash -lc "curl -sS -i '${BASE_URL}/debug/perf/history'"

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------

section "10. Manual Review Checklist"

line "- Confirm \`GET /health\` returned HTTP 200."
line "- Confirm \`GET /v1/models\` returned model id \`local-litert-lm\`."
line "- Confirm OPTIONS responses include \`Access-Control-Allow-Private-Network: true\`."
line "- Confirm streaming response includes progressive \`data:\` chunks and final \`data: [DONE]\`."
line "- Confirm \`/debug/perf\` shows nonzero generation duration, output chars, chars/sec, and chunk count after generation."
line "- Confirm persistent benchmark has no errors."
line "- Confirm fresh-per-request benchmark has no \`FAILED_PRECONDITION\` session error."
line "- Compare benchmark averages:"
line "  - Persistent reset=false"
line "  - Persistent reset=true"
line "  - Fresh per request"
line "  - Coding concise"
line "  - Balanced"
line "  - Detailed"
line "- Keep best coding setup as: GPU + MTP + stream=true + fastest stable conversation/response mode."

blank
line "**Finished:** $(date '+%Y-%m-%d %H:%M:%S')"
line "**Results file:** \`$RESULTS_FILE\`"

echo
echo "Done. Results written to: $RESULTS_FILE"
