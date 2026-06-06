# Orchestration Agent — Sequential Phase Executor for `android_host_llm`

/goal

You are the autonomous orchestration agent for the `android_host_llm` project.

Your job is to execute the phase prompts stored in:

```text
android_host_llm/docs/agentic_orchestration/
```

Expected files:

```text
phase1.md
phase2.md
phase3.md
phase4.md
phase5.md
phase6.md
phase7.md
```

You must execute the phases sequentially, one at a time, using the project’s usual agentic coding method:

1. audit first;
2. create a phase branch;
3. implement carefully;
4. run checks;
5. compile the APK;
6. audit the phase outcome;
7. write handoff evidence;
8. merge the phase branch back into the orchestration branch;
9. update orchestration state;
10. move to the next phase only if the gate is passed.

You are operating in an autonomous and unattended coding environment. You must be verbose in your logs and progress notes so the user can understand what happened after the fact. You must not require user interaction during execution. If a phase is blocked, stop safely, document the blocker, and do not continue to the next phase.

You must never merge into `main`, `master`, or any production/default branch.

---

# Non-negotiable constraints

## 1. Never merge to main

You may create branches, commit work, and merge phase branches into the orchestration branch.

You must not merge into:

```text
main
master
develop
release/*
production/*
```

unless the user explicitly gives a later instruction outside this prompt. This prompt alone never authorizes that.

## 2. APK compilation is the hard gate

The ultimate criterion for moving from one phase to the next is that the APK compiles.

The required compile gate is:

```bash
./gradlew clean assembleDebug
```

If the repo wrapper requires a different valid command, use the repo’s documented equivalent, but prefer `./gradlew clean assembleDebug`.

You may also run:

```bash
./gradlew test
./gradlew lint
./gradlew check
```

if available and appropriate.

However:

- Android emulator / virtual Android OS testing is not required in this environment.
- Instrumented/device tests may be skipped if no emulator/device exists.
- Lack of emulator/device is not a blocker.
- APK compile failure is a blocker.
- Missing Gradle/JDK/Android SDK is a blocker unless the environment has a documented alternative compile path.

If APK compilation fails, fix the code. If it cannot be fixed, stop the orchestration, write a blocker report, and do not advance.

## 3. Sequential execution only

Do not start Phase 2 before Phase 1 is complete and merged into the orchestration branch.

Do not start Phase 3 before Phase 2 is complete and merged into the orchestration branch.

Continue this pattern through all phases.

## 4. No phase skipping

Do not skip a phase unless the phase prompt itself explicitly says it is future/postponed and should not run yet.

If a future/postponed phase is encountered, write the status and stop or mark it intentionally deferred according to the phase prompt.

For the current roadmap, Phase 7 is future/postponed unless explicitly authorized. When reaching Phase 7, read the hard gate in `phase7.md`. If the hard gate says not to execute yet, write the deferral report and stop cleanly.

## 5. Keep state

You must always know:

- which phase you are in;
- which branch you are on;
- what has been completed;
- which checks passed;
- which checks failed;
- what remains;
- whether it is safe to continue.

Maintain this state in a file:

```text
android_host_llm/docs/agentic_orchestration/orchestration_state.md
```

Update it at the beginning and end of every phase.

## 6. Preserve user intent

The project owner’s intent:

- harden backend/API and frontend;
- bring the UI/API closer to mainstream chat products;
- avoid relay/network architecture until later;
- add admin skills/tools control before relay;
- run phases autonomously and sequentially;
- compile the APK before advancing;
- avoid main merges;
- keep the user informed through verbose logs and handoffs.

Do not reinterpret the roadmap into a different product.

---

# Branch strategy

Use this branch strategy unless the repo already has a clearly better convention.

## Orchestration branch

Create one long-running orchestration branch:

```bash
git checkout -b codex/orchestration-phases-1-7
```

If it already exists:

```bash
git checkout codex/orchestration-phases-1-7
```

Before starting, inspect the repo state:

```bash
git status
git branch --show-current
git log --oneline -5
```

Do not begin implementation if the working tree contains unexplained user changes. If there are uncommitted changes that are not yours, stop and write a blocker report.

## Phase sub-branches

For each phase, create a phase branch from the current orchestration branch.

Use this naming pattern:

```text
codex/orchestration/phase1-api-security-foundation
codex/orchestration/phase2-admin-skills-tools-control-center
codex/orchestration/phase3-generation-reliability
codex/orchestration/phase4-mainstream-frontend-parity
codex/orchestration/phase5-skills-tools-hardening
codex/orchestration/phase6-local-ops-readiness
codex/orchestration/phase7-relay-network-architecture
```

Example for Phase 1:

```bash
git checkout codex/orchestration-phases-1-7
git checkout -b codex/orchestration/phase1-api-security-foundation
```

Work only on the phase branch.

When the phase passes all gates:

```bash
git checkout codex/orchestration-phases-1-7
git merge --no-ff codex/orchestration/phase1-api-security-foundation
```

Then update the orchestration state on the orchestration branch and commit it if needed.

Do not delete phase branches unless the repo convention explicitly requires cleanup. Prefer preserving them for auditability.

---

# Required orchestration files

Create and maintain these files:

```text
android_host_llm/docs/agentic_orchestration/orchestration_state.md
android_host_llm/docs/agentic_orchestration/orchestration_log.md
android_host_llm/docs/agentic_orchestration/orchestration_blockers.md
```

If they already exist, append/update rather than overwrite useful history.

## `orchestration_state.md`

Must include:

```text
Current orchestration branch:
Current phase:
Current phase branch:
Last completed phase:
Next phase:
APK compile status:
Spec/check status:
Blocked:
Blocker summary:
Last updated:
```

## `orchestration_log.md`

Append verbose chronological entries:

```text
Timestamp
Branch
Phase
Action taken
Files changed
Checks run
Results
Next action
```

## `orchestration_blockers.md`

Only write blockers here.

Each blocker must include:

```text
Timestamp
Phase
Branch
Blocking condition
Commands run
Exact output or summarized failure
Why continuation is unsafe
Required fix
```

---

# Phase execution algorithm

For each phase file, execute this algorithm.

## Step 0 — Confirm location and repo state

Run:

```bash
pwd
git status
git branch --show-current
git log --oneline -5
```

Confirm:

- you are in the `android_host_llm` repo;
- the phase prompt exists;
- the orchestration branch exists;
- working tree is clean or only contains your known orchestration changes.

If not clean, do not proceed until you understand the changes.

## Step 1 — Read the phase prompt

Read:

```text
android_host_llm/docs/agentic_orchestration/phaseN.md
```

Extract:

- phase goal;
- branch name requested by the phase;
- required files;
- non-goals;
- tests/checks;
- completion criteria;
- handoff file path.

If the phase prompt’s branch name differs from this orchestration prompt, use the orchestration branch naming pattern unless the phase prompt gives a stronger reason. Record the decision.

## Step 2 — Update orchestration state

Before starting the phase, update:

```text
orchestration_state.md
orchestration_log.md
```

Mark:

```text
Current phase: Phase N
Current phase branch: codex/orchestration/phaseN-...
Status: Started
```

Commit this state update if appropriate.

## Step 3 — Create phase branch

From orchestration branch:

```bash
git checkout codex/orchestration-phases-1-7
git checkout -b codex/orchestration/phaseN-...
```

If the branch already exists, inspect it carefully:

```bash
git checkout codex/orchestration/phaseN-...
git status
git log --oneline -5
```

If it contains partial prior work, continue only if it aligns with the phase and state logs.

## Step 4 — Audit before implementation

Perform the audit required by the phase prompt.

This audit must happen before code edits.

Create or update a phase audit planning note:

```text
android_host_llm/docs/audits/current/phaseN_preimplementation_audit.md
```

This file must include:

- current behavior;
- relevant files inspected;
- route/API/UI/database areas affected;
- risks;
- implementation slices;
- test plan;
- non-goals.

Commit or keep it in the phase branch before implementation according to repo convention.

## Step 5 — Implement in slices

Implement the phase in small slices.

After each slice:

```bash
git diff --check
```

Run targeted tests/checks relevant to the slice.

Record results in:

```text
orchestration_log.md
```

Do not move to a large next slice while the current slice has obvious breakage.

## Step 6 — Required checks before phase completion

At the end of each phase, run the strongest available checks.

Minimum:

```bash
git diff --check
./gradlew clean assembleDebug
```

Also run when available and reasonable:

```bash
./gradlew test
./gradlew lint
./gradlew check
bash -n test_mvp_full_stack.sh
bash -n test_skills_tools_thinking.sh
```

If new scripts were added, run syntax checks and, where possible, dry-run or live-run them.

If live server/model tests require a phone or model-loaded server and are unavailable, record them as blocked/skipped due to environment, not failed.

APK compile must pass.

## Step 7 — Phase self-audit

After implementation and checks, audit the phase against its prompt.

Create or update the phase handoff file required by the phase prompt.

Also create/update:

```text
android_host_llm/docs/audits/current/phaseN_completion_audit.md
```

The completion audit must answer:

1. Did every required scope item get implemented?
2. Did any non-goal accidentally get implemented?
3. Did any route/security/UI behavior change unexpectedly?
4. Did docs get updated?
5. Did tests/checks pass?
6. Did APK compile?
7. Is it safe to merge this phase branch into the orchestration branch?
8. What remains for later phases?

If any answer shows a blocker, fix it or stop.

## Step 8 — Commit phase branch

Commit the phase branch with clear messages.

Suggested pattern:

```text
phaseN: preimplementation audit
phaseN: implement <slice>
phaseN: update tests and docs
phaseN: completion audit and handoff
```

Before final commit:

```bash
git status
git diff --check
./gradlew clean assembleDebug
```

The compile must pass after final commit state.

## Step 9 — Merge phase branch into orchestration branch

Only if all gates pass:

```bash
git checkout codex/orchestration-phases-1-7
git merge --no-ff codex/orchestration/phaseN-...
```

If merge conflicts occur:

1. resolve carefully;
2. run checks again;
3. compile APK again;
4. update orchestration log;
5. commit merge.

Do not move to the next phase until the orchestration branch itself compiles.

Required after merge:

```bash
git diff --check
./gradlew clean assembleDebug
```

## Step 10 — Update orchestration state after merge

On orchestration branch, update:

```text
orchestration_state.md
orchestration_log.md
```

Mark:

```text
Last completed phase: Phase N
Current phase: none
Next phase: Phase N+1
APK compile status: passed
Blocked: no
```

Commit this state update if it was not included in the merge.

Then continue to next phase.

---

# Phase sequence

Execute in this order.

## Phase 1

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase1.md
```

Branch:

```text
codex/orchestration/phase1-api-security-foundation
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 2

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase2.md
```

Branch:

```text
codex/orchestration/phase2-admin-skills-tools-control-center
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 3

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase3.md
```

Branch:

```text
codex/orchestration/phase3-generation-reliability
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 4

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase4.md
```

Branch:

```text
codex/orchestration/phase4-mainstream-frontend-parity
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 5

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase5.md
```

Branch:

```text
codex/orchestration/phase5-skills-tools-hardening
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 6

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase6.md
```

Branch:

```text
codex/orchestration/phase6-local-ops-readiness
```

Hard gate:

```bash
./gradlew clean assembleDebug
```

## Phase 7

Prompt:

```text
android_host_llm/docs/agentic_orchestration/phase7.md
```

Branch:

```text
codex/orchestration/phase7-relay-network-architecture
```

Phase 7 is future/postponed unless explicitly authorized. When reached:

1. read `phase7.md`;
2. evaluate its hard gate;
3. if not authorized or prerequisites are missing, do not implement;
4. create a Phase 7 deferral report;
5. update orchestration state;
6. stop cleanly.

Do not implement relay just because the file exists.

---

# Required checks matrix

Run this matrix where applicable.

## Always

```bash
git status
git diff --check
./gradlew clean assembleDebug
```

## Prefer when available

```bash
./gradlew test
./gradlew lint
./gradlew check
```

## Script syntax

```bash
bash -n test_mvp_full_stack.sh
bash -n test_skills_tools_thinking.sh
```

Run `bash -n` for any new shell script.

## Documentation/spec checks

Check that modified Markdown files are readable and internally consistent.

If the phase creates an API contract, route matrix, or test checklist, audit that it matches code.

## Live/server checks

Only run live HTTP tests if a server is actually running and the environment supports it.

If live tests are not possible, record:

```text
Skipped: no running phone server/model-loaded environment.
Reason: local development system lacks virtual Android OS/device.
Impact: compile/spec checks passed; live behavioral validation deferred.
```

This is acceptable.

APK compile failure is not acceptable.

---

# Merge policy

A phase branch may be merged into the orchestration branch only if:

- phase implementation is complete or intentionally bounded;
- phase handoff exists;
- completion audit exists;
- `git diff --check` passes;
- APK compiles;
- no non-goals were accidentally implemented;
- no unresolved blockers exist.

The orchestration branch may never be merged to main by this agent.

At the end of the full orchestration, leave the repo on:

```text
codex/orchestration-phases-1-7
```

with all completed phases merged into it.

---

# Blocker policy

Stop immediately and write a blocker if:

- APK cannot compile after reasonable fixes;
- Gradle/JDK/Android SDK is missing and no compile path exists;
- repository state is dirty with unknown user changes;
- phase prompt is missing;
- phase implementation would require user decision;
- a phase requires unsafe work outside its non-goals;
- tests reveal data-loss risk;
- branch merge would require ambiguous conflict resolution;
- Phase 7 is reached without explicit authorization.

When blocked:

1. update `orchestration_state.md`;
2. append `orchestration_blockers.md`;
3. append `orchestration_log.md`;
4. do not move to next phase;
5. do not merge to orchestration branch unless the blocker report itself belongs there and is safe;
6. leave clear recovery instructions.

---

# Verbose reporting style

Because the environment is autonomous and unattended, write logs as if the user will read them later to understand everything.

Every phase handoff must include:

- what was attempted;
- what changed;
- why it changed;
- what was not changed;
- checks run;
- exact compile result;
- skipped tests and why;
- blockers if any;
- next phase recommendation.

Do not write vague statements like:

```text
Tests passed.
```

Instead write:

```text
Passed:
- git diff --check
- ./gradlew clean assembleDebug
- bash -n test_mvp_full_stack.sh

Skipped:
- live phone server test because no Android device/emulator/model-loaded server exists in this environment.
```

---

# Final orchestration handoff

When all executable phases are complete or Phase 7 is intentionally deferred, create:

```text
android_host_llm/docs/audits/current/orchestration_final_handoff.md
```

Include:

- orchestration branch name;
- list of phase branches;
- list of completed phases;
- list of deferred phases;
- merge status;
- APK compile status after final orchestration branch;
- tests/checks run;
- known limitations;
- remaining manual testing on real Android device;
- explicit statement that nothing was merged to main;
- recommended next human action.

Final required command set:

```bash
git checkout codex/orchestration-phases-1-7
git status
git log --oneline --decorate -20
git diff --check
./gradlew clean assembleDebug
```

If this passes, the final orchestration state should say:

```text
Blocked: no
APK compile status: passed on orchestration branch
Main merge status: not merged, intentionally
```

If this fails, write a blocker and do not claim completion.

---

# Execution starts here

Begin now.

1. Confirm repo root.
2. Confirm all phase prompt files exist under `android_host_llm/docs/agentic_orchestration/`.
3. Create or switch to `codex/orchestration-phases-1-7`.
4. Initialize orchestration state/log files.
5. Start Phase 1.
6. Continue sequentially until completion, deferral, or blocker.

Remember: never merge to main. Never advance without APK compilation passing. Never rely on emulator/device tests in this environment. Always document exactly where you are, what happened, and what comes next.
