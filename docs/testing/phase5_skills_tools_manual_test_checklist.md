# Phase 5 Skills/Tools Manual Test Checklist

Run against an installed APK with the phone server started and a model loaded.

- Register/login and create a chat.
- Select a skill with tools enabled.
- Prompt for current date/time and confirm one tool call is logged with `SUCCESS`, `requestId`, `skillSlug`, `skillVersion`, `durationMs`, args/result previews, and timestamp.
- Prompt in a way that produces fenced JSON tool-call output and confirm it is parsed or repaired once.
- Prompt for an unknown tool name and confirm the log status is `UNKNOWN_TOOL` or `REPAIR_FAILED`, not a crash.
- Prompt for a disallowed tool under the `coding` skill and confirm `PERMISSION_DENIED`.
- Upload a Markdown file, use `markdown-qa`, and confirm file search/count tools still work.
- Try oversized or nested/executable-looking arguments and confirm `INVALID_ARGUMENTS`/`REJECTED`.
- Use the GDPR PII audit skill and confirm strict JSON output matches `confirm_deletion` and `reason`.
- Open admin tool logs and confirm trace fields render.
- Confirm normal chat without tool calls still answers normally.
