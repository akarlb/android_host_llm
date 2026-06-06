# Tool Plugin Sandbox Design

Date: 2026-06-06

This is a future design target. Phase 5 does not implement arbitrary executable tools.

## Goals

- Allow controlled extension of backend tools without letting browser-created content execute arbitrary code.
- Make every tool inspectable, permissioned, timeout-bound, and auditable.
- Keep secrets, filesystem access, and network access denied by default.

## Tool Manifest

Each future plugin tool should declare:

- `name`, `displayName`, `description`, `version`, and `author`.
- `inputSchema` and `outputSchema` using the supported local schema subset.
- `dangerLevel`: `SAFE`, `SENSITIVE`, or `DESTRUCTIVE`.
- `allowedForSkills` and default enabled state.
- Required capabilities: `network`, `filesystemRead`, `filesystemWrite`, `secrets`, `adminOnly`.
- Timeout milliseconds and max input/output bytes.
- Deterministic test vectors for install-time validation.

## Sandbox Boundaries

- Plugins run outside the UI process and cannot call Android APIs directly.
- Inputs are JSON only; outputs are JSON only.
- No shell, process spawning, dynamic class loading, reflection, or native library loading.
- Filesystem access is scoped to a per-plugin directory unless a manifest capability grants read-only access to specific app-managed data.
- Network access is denied unless the admin approves specific host allowlists.
- Secrets are never passed by default. A secret capability must name the secret and expose it only through a short-lived handle.

## Permissions

- Admin approval is required to install, enable, update, or widen a plugin permission.
- Normal users can only execute tools enabled globally and allowed by the selected skill.
- Admin-only tools require an admin user and must not be callable from normal chat.
- `SENSITIVE` and `DESTRUCTIVE` tools require explicit skill allowlisting and per-call confirmation policy before implementation.

## Execution Policy

- One tool call per model turn.
- Hard timeout per tool call.
- Max argument and result sizes enforced before and after execution.
- All arguments validated against schema before execution.
- All results validated against schema before feeding them back to the model.
- Tool failures map to the standard taxonomy: `PARSE_FAILED`, `REPAIR_FAILED`, `UNKNOWN_TOOL`, `PERMISSION_DENIED`, `INVALID_ARGUMENTS`, `EXECUTION_FAILED`, `TIMEOUT`, `SUCCESS`, `REJECTED`.

## Audit Logging

Every call records request ID, chat ID, message ID, skill slug/version, raw model preview, parsed tool name, sanitized args, sanitized result preview, status, duration, error code/message, and timestamp.

## Required Tests Before Runtime Plugins

- Manifest validation rejects unknown capabilities and unsupported schemas.
- Permission widening requires admin approval.
- Network and filesystem denial tests.
- Timeout and oversized input/output tests.
- Secret redaction tests.
- Tool result schema validation tests.
- Upgrade/downgrade tests for plugin versions.
