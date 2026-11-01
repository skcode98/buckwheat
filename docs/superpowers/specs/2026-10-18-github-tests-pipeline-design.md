# GitHub Tests Pipeline Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a GitHub Actions pipeline that runs all tests in CI (not locally), generates detailed per-test timing grouped by package, and produces AI-agent analyzable results with full failure details.

**Architecture:** Add a dedicated `tests.yml` workflow that runs unit tests on Linux and (optionally) instrumentation tests on macOS, parses JUnit XML to compute per-test/per-class/per-group timing, writes `test-analysis.json` and `test-analysis.md` for AI consumption, uploads raw JUnit XML + HTML reports as artifacts, and writes a concise summary to `$GITHUB_STEP_SUMMARY`. Keep existing `build.yml` unchanged.

**Tech Stack:** GitHub Actions (ubuntu-latest/macos-latest), Gradle (Android), JUnit XML, Python 3 (inline script for parsing), `reactivecircus/android-emulator-runner@v2` (instrumentation only when enabled), `actions/checkout@v4`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`.

**Spec:** docs/superpowers/specs/2026-10-18-github-tests-pipeline-design.md

## Global Constraints

- Do not change existing `build.yml` behavior (keep APK build on push/tags as-is).
- Run all tests in CI only; no requirement to change local test commands.
- Use `--continue` so all tests execute even if some fail; still upload artifacts with `if: always()`.
- Group tests by top-level package under `com.danilkinkin.buckwheat.*` (e.g. `analytics`, `data`, `ai`, `backup`, `base`, `di`, `sync`, `ui`, `utils`, `domain`, etc.).
- Record time per test (from JUnit `time`), per class, per group, totals. Durations in milliseconds in JSON.
- Generate AI-analyzable outputs: `test-analysis.json` and `test-analysis.md` plus raw JUnit XML + HTML reports as artifacts.
- Concurrency: cancel in-progress runs per ref/branch.
- Timeouts: unit-tests 30m, android-tests 40m (when enabled).
- Fail workflow if any test failed (default job failure), but artifacts must still be published.

---

## Requirements

### R1 - Triggers
- Workflow triggers on `push` to all branches (`branches: ['**']`) and `pull_request` (`types: [opened, synchronize, reopened]`).
- No tag trigger (separate from `build.yml`).

### R2 - Concurrency
- `concurrency.group: ${{ github.workflow }}-${{ github.ref }}` with `cancel-in-progress: true`.

### R3 - Unit tests job
- Job `unit-tests` runs on `ubuntu-latest`, timeout `30m`.
- Steps: checkout, setup-java (temurin 17), setup-gradle (cache), `chmod +x gradlew`, `./gradlew testDebugUnitTest --continue`.
- Parse all `app/build/test-results/testDebugUnitTest/*.xml` with Python inline script.
- Group by package prefix `com.danilkinkin.buckwheat.<group>` where `<group>` is first segment after base package.
- Compute totals, per-group, per-class, per-test with status (passed/failed/skipped/error), duration_ms, failureMessage, stackTrace, stdout, stderr (when present).
- Generate `test-analysis.json` and `test-analysis.md` in workspace root.
- Upload artifacts with `if: always()`: `unit-test-results-xml` (XML), `unit-test-reports-html` (HTML), `unit-test-analysis` (JSON+MD).
- Write key totals + top 5 slowest (by test/class/group) to `$GITHUB_STEP_SUMMARY` with `if: always()`.

### R4 - Android instrumentation tests (optional/conditional)
- Job `android-tests` runs on `macos-latest`, timeout `40m`.
- Enabled only when: `github.event_name == 'pull_request' && contains(github.event.pull_request.labels.*.name, 'run-android-tests')` OR `github.event_name == 'workflow_dispatch'` (manual). Not run on every push by default (to avoid 30-60min local-like CI time).
- Use `reactivecircus/android-emulator-runner@v2` with API 34, arch `x86_64`, `disable-animations: true`, `emulator-options: -no-snapshot -no-window -gpu swiftshader_indirect`, reasonable `heap-size`, `disk-size`.
- Run `./gradlew connectedDebugAndroidTest --continue`.
- Same parsing/grouping and artifact uploads with names `android-test-results-xml`, `android-test-reports-html`, `android-test-analysis`.
- `if: always()` for all upload/summary steps.

### R5 - JSON schema
`test-analysis.json` must include:
```json
{
  "meta": {"runId","commit","branch","event","workflow","generatedAt","totalDurationMs"},
  "totals": {"tests":0,"passed":0,"failed":0,"skipped":0,"errors":0,"durationMs":0},
  "groups": [{"name":"","packagePrefix":"","tests":0,"passed":0,"failed":0,"skipped":0,"errors":0,"durationMs":0,"classes":[{"name":"","tests":0,"passed":0,"failed":0,"skipped":0,"errors":0,"durationMs":0}]}],
  "tests": [{"name":"","className":"","packageName":"","group":"","durationMs":0,"status":"passed|failed|skipped|error","failureMessage":null,"stackTrace":null,"stdout":null,"stderr":null}],
  "slowest": {"byTest":[{"name":"","className":"","group":"","durationMs":0}],"byClass":[{"name":"","class":"","group":"","durationMs":0}],"byGroup":[{"name":"","durationMs":0}]}
}
```

### R6 - Markdown report
`test-analysis.md` must include: Run meta, Totals table, Groups summary table, Per-group breakdown (classes + counts + duration), Slowest (by test/class/group), Failures section (full failure message + stack trace per failing test), Artifacts list.

### R7 - Artifacts retention
Default GitHub retention (sufficient). No custom retention override required.

### R8 - Error handling & robustness
- `set +e` not needed if using `--continue`; check step outcome via `if: success() || failure()` not necessary since `if: always()` used.
- XML parsing must be defensive (handle missing time, skipped tests, multiple suites/cases).
- Python script runs in runner (ubuntu/macos) with stdlib only.

---

## Solution

Create `.github/workflows/tests.yml` with unit-tests job (always) and android-tests job (conditional). Add inline Python script to parse JUnit XML and generate analysis files. Write summary to `$GITHUB_STEP_SUMMARY`. All uploads use `if: always()`.
