# Resource Leak Detector - Development Plan

> **Note**: For requirements and design details, see [resource-leak-detector-requirements.md](./resource-leak-detector-requirements.md) and [resource-leak-detector-design.md](./resource-leak-detector-design.md).

## Overview

This document outlines the incremental development plan for implementing the Resource Leak Detector. Each step must be completed (feature complete with unit test coverage) before proceeding to the next step.

All components are located in package `com.salesforce.test.extensions.resourceleak`.

Unless specified otherwise, each step should account for all elements in the design document for the components being implemented in that step.

**Push Policy**: Push after each step, but ONLY AFTER explicit approval to do so.

---

## Steps

- [x] **1. Replace polling with lifecycle-boundary snapshots.** Delete `ResourceMonitorThread` and the polling-interval / per-monitor-budget config keys. Extend `ResourceLeakMonitorTestLifecycleExtension` to implement all four callbacks (`BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`); each enabled callback drives a synchronous snapshot of every enabled monitor. Add `ResourceLeakMonitor.testPlanExecutionStarted` baseline snapshot and `testPlanExecutionFinished` final snapshot. Update existing monitors to expose a single `snapshot()` method. Verify the existing `integration-tests/basic` and `integration-tests/ddb` modules still detect the same leaks they did under polling.

- [x] **2. Snapshot-list `ResourceState` model.** Replace the polling-era `Map<ResourceId, DiscreteResourceInfo>` and `List<NumericResourceMeasurement>` with two minimal, in-memory pieces: (1) the **current** resource state (last-observed discrete sets per monitor + last-observed numeric values), and (2) per-class lifecycle intervals (`Map<TestClassName, TestClassLifecycle>`, plus per-test intervals when granularity=test). Drop the polling-era `last`/`destroyed` fields. Snapshots themselves (the *history*) are not held in `ResourceState` — they are streamed to disk by `RawReportWriter` (step 4) as each callback fires. `ResourceLeakReporter`'s legacy diff-based leak detection continues to work against the in-memory current state. The result: `ResourceState` stays small (KB-scale, no growth proportional to test count); the snapshot history lives only on disk.
- [x] **3. Snapshot granularity configuration.** Add `snapshot.granularity=class|test` config key (default `class`). When `class`, the per-each callbacks no-op. When `test`, they take snapshots and store per-test lifecycle intervals. Verify both modes against the integration tests; verify per-class mode is the default.

- [x] **4. Streaming raw-report writer.** Add `RawReportWriter` that streams a JSON header at suite start, appends one JSON Lines snapshot record per callback fire on the test thread, and writes a closing footer at suite end. Output to `target/resource-leak-detector/raw-report.json` by default (configurable). Define and document the JSON schema (the contract between C1 and C3 — see design doc). Include `ResourceLeakReporter`'s existing report as a separate human-readable text file alongside the raw report.

- [x] **5. Attribution component (C3) as separate module.** The repo currently has the library as the root jar and `integration-tests/{basic,ddb}` as test-only modules — i.e., not a real multi-module layout for code yet. Convert the root to a parent (`packaging=pom`) with three sibling code modules: `library/` (the existing detector code), `attribution/` (this step), and the existing `integration-tests/` aggregator. The new module is pure data transformation: reads one or more raw reports, computes the candidate set per leak using the lifecycle-window-intersection algorithm, emits the final report. No JUnit dependency. Includes the empty-set defect detection (REQ-2.2.2) and the cross-run intersection logic (REQ-2.4) even though only single-run is wired up at this step. Unit tests cover: single-run attribution, candidate-window intersection edge cases, defect detection.
- [ ] **6. Wire C3 inline into single-run mode.** `ResourceLeakMonitor.testPlanExecutionFinished` invokes the attribution module in-process against its own raw report and writes the final leak report. The original direct `ResourceLeakReporter` call path is removed (C3 is now the single producer of the user-facing report). Verify the integration tests produce the same set of leaks as before, with the new candidate-set output.

- [ ] **7. Pre-class settle wait.** Add the optional pre-class settle wait described in the design doc. After each `AfterAllCallback`, `ResourceLeakMonitorTestLifecycleExtension` computes the per-class delta for threads and ports (AfterAll set − BeforeAll set) and stores it in a private mutable field, overwriting the previous class's delta. At each subsequent `BeforeAllCallback`, when `preclass.settle.enabled=true`, the extension intersects the stored delta with a fresh probe snapshot of the same monitors and polls (`preclass.settle.poll.interval.seconds`, default 1s) until the intersection is empty or `preclass.settle.max.seconds` (default 10s) elapses, then takes the boundary snapshot and records the BeforeAll timestamp. Log carry-over at DEBUG each iteration, total elapsed at completion, and at WARN if the timeout fires with carry-over still present. Wait applies only to threads and ports. Do not apply at `BeforeEachCallback`. Add config keys `preclass.settle.enabled` (default `false`), `preclass.settle.max.seconds` (default `10`), `preclass.settle.poll.interval.seconds` (default `1`). Unit tests cover: empty-carry-over fast path, carry-over clears mid-wait, timeout path with WARN log, threads/ports only (other monitors not consulted), first-class no-op. Add an integration-test variant that enables the wait and verifies the candidate set tightens for a deliberately slow-releasing thread vs. the wait-disabled baseline.

- [ ] **8. Pre-flight Maven configuration check.** Implement `ResourcePreflightChecker`. Invoked first thing in `testPlanExecutionStarted`. Locates `pom.xml` for the running module, parses Surefire configuration resolved against the active profile, verifies `forkCount=1` and `reuseForks=true`. On mismatch, throws a fatal error with a clear message and an example Surefire snippet. On non-Maven projects, fails fast with "unsupported build tool". Add config flag `preflight.enabled=true` (default true). Add an integration-test variant that intentionally violates the prerequisite to verify the failure path.

- [ ] **9. Standalone C3 CLI.** Add a thin CLI entry point in the attribution module that accepts one or two raw-report file paths and emits the final leak report to stdout (or to a file). Distributed as an executable jar. Manual verification: run against a saved raw report from the integration tests and confirm output matches the inline run's output.

- [ ] **10. Differential-ordering double-run mode (C2).** Implement C2 as a Maven plugin that invokes the test suite twice with full Surefire configuration controlled by the plugin (runOrder=alphabetical for run 1; runOrder=random + recorded seed for run 2; plus forkCount/reuseForks/autodetection flags). Each run produces its own raw report. After both runs complete, the plugin invokes the standalone C3 CLI with both reports; C3 intersects candidate sets and emits the final report. Add `runs=2` config switch. Include integration test that exercises the two-run path end-to-end.
- [ ] **11. Build-failure deferral for double-run.** Update the build-failure logic so that when `runs=2` and `build.failure.enabled=true`, both runs always execute and the failure decision is made by C3 after intersecting candidate sets. Run 1 must not short-circuit on observed leaks. Verify with an integration test that fails the build only after the second run completes.

- [ ] **12. Re-apply against zero-object-service and capture comparison report.** Update the `~/Documents/d/zero-object-service-leaks` checkout to consume the new detector. Run with `runs=1` first, then `runs=2`, and compare attribution quality against the prior polling-based report (`LEAK-REPORT.md`). Capture findings in `LEAK-REPORT-v2.md` alongside the original. Specifically check: (a) zero "no candidate" leaks (REQ-2.2.2); (b) system-property leaks no longer all attributed to a single class (the original Mode A bug); (c) `runs=2` measurably narrows attribution vs `runs=1`.
---

## Testing Guidelines

### Unit Test Requirements

- Each step must include comprehensive unit test coverage
- Use parameterized tests when two tests are doing effectively the same thing with different inputs/expected values
- Prefer test doubles or manual mocks over Mockito (per user rules)
- Tests should verify both positive and negative cases
- Tests should verify error handling and edge cases

### Code Quality

- Keep comments to those that are essential
- Don't include rationales unless they're critical
- Don't add `@param` javadoc (per user rules)
- Follow existing code style (don't reformat code unnecessarily)
- No wildcard imports
- Only add methods when they're used, not before

### Incremental Development

- Complete each step fully (feature complete with unit test coverage) before starting the next
- Each step should be independently testable and verifiable
- Integration tests can be added in later steps to verify end-to-end behavior
- After completing each step: run all tests (`mvn test` at the repo root) and `git add` the changes so they are staged for review. **Do not commit** — the user reviews the staged diff and commits manually.
