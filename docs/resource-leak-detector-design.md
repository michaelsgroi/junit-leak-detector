# Resource Leak Detector - Design

> **Note**: For requirements, see [resource-leak-detector-requirements.md](./resource-leak-detector-requirements.md).

## Overview

The Resource Leak Detector monitors and reports resource leaks in unit tests, tracking network ports, threads, system properties, environment variables, memory usage, and DynamoDB Local tables.

Resource snapshots are captured **at JUnit lifecycle boundaries only** — never via scheduled wall-clock polling. The component uses JUnit Platform's `TestExecutionListener` API for global test-plan lifecycle (suite start/end) and a globally-registered JUnit Jupiter `Extension` for per-class and per-test lifecycle (`BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`).

The component is split into three independent pieces (per the Architecture section of the requirements):

- **C1 — Test runner + raw report.** Captures resource snapshots at lifecycle boundaries during a single test run; emits a structured raw report. No attribution logic.
- **C2 — Second run (optional).** Drives the test runner a second time with a different Surefire `runOrder`; emits its own raw report.
- **C3 — Attribution.** Independent post-processor. Consumes one or two raw reports; computes the candidate set per leak; emits the final human-readable leak report.

All component code lives in package `com.salesforce.test.extensions.resourceleak`.

All timestamps use ISO8601 format.

### Repo Layout

The project is a multi-module Maven build. The root pom is `packaging=pom` and aggregates four sibling modules:

```
junit-leak-detector/                     ← parent (packaging=pom)
├── library/                             ← C1: detector library (the JAR consumers depend on)
├── attribution/                         ← C3: attribution module (reusable lib + standalone CLI)
├── orchestrator/                        ← C2: Maven plugin for double-run mode
└── integration-tests/                   ← aggregator
    ├── basic/                           ← subject suite: leaking + control test classes
    ├── ddb/                             ← subject suite: DynamoDB Local leaking test
    └── scenarios/                       ← failsafe-driven scenario tests (verifies basic + ddb)
```

Only `library`, `attribution`, and `orchestrator` are publishable. `integration-tests/*` are test-only and not deployed.

### Scenario testing

End-to-end scenarios are verified by failsafe-driven Kotlin tests in `integration-tests/scenarios/`. Each scenario test:

1. Invokes `mvn test` against one of the sibling subject modules (`integration-tests/basic` or `integration-tests/ddb`) with a specific Maven profile that configures the detector for that scenario (e.g., `build-failure`, `verify-failfast`, `multi-fork`).
2. Captures the combined stdout/stderr from the sub-build.
3. Asserts in JUnit/Kotlin that the expected log lines, exit code, and report contents are present.

The `scenarios` module sits last in the integration-tests reactor so the library jar and the subject modules are already installed in the local Maven repo by the time failsafe fires (`mvn install` is the entry point — failsafe runs at `verify`, immediately before `install` for each module). Because of this dependency on installed artifacts, **`mvn install` is the supported full-build invocation**, not `mvn verify`.

Why failsafe rather than surefire: scenario tests are slow (each spawns a child Maven process) and conceptually distinct from unit tests. Failsafe's lifecycle places them at `verify`, after the fast unit-test layer has already gated the build. The `*IT` naming convention is the standard Maven idiom failsafe picks up.

Why a dedicated module rather than co-located with library tests: the scenario tests need the library jar and the subject modules to be installed before they run. Failsafe inside `library/` would fire before any sibling module is installed. A trailing module in the reactor sidesteps the chicken-and-egg.

### Packaging and Distribution

The Resource Leak Detector is maintained in its own standalone codebase, separate from any consuming project. It is published as a Maven artifact (e.g., `com.salesforce.test:resource-leak-detector:<version>`) and consumed as a `test`-scoped dependency.

Auto-registration with JUnit means consumers do not need to modify test code:

* `ResourceLeakMonitor` (the `TestExecutionListener`) is discovered via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`.
* `ResourceLeakMonitorTestLifecycleExtension` (the JUnit Jupiter `Extension`) is discovered via `META-INF/services/org.junit.jupiter.api.extension.Extension` (with `junit.jupiter.extensions.autodetection.enabled=true` set in the consuming project's Surefire configuration).

Configuration is supplied by the consuming project via a properties file on the test classpath, with optional system property overrides (see [Configuration](#configuration)). The artifact contains no consumer-specific references.

To fully disable the Resource Leak Detector with zero runtime overhead, the consuming project omits (or comments out) the Maven dependency. With the JAR off the test classpath, ServiceLoader discovers nothing, no classes from the library are loaded, and no hooks fire.

## Test-isolation Prerequisites

Reliable detection requires every test class in the suite to share one JVM. With `forkCount` > 1 or `reuseForks=false`, each fork starts clean and cross-class sticky leaks become invisible. The component handles this in two complementary ways:

### Orchestrator owns the invocation

The supported entry point is the double-run orchestrator Maven plugin (see [C2 — Second Run](#c2--second-run-optional)). The orchestrator invokes Surefire with the required flags set explicitly on the command line:

```
-DforkCount=1 -DreuseForks=true -Djunit.jupiter.extensions.autodetection.enabled=true
```

These overrides take precedence over whatever the consuming project has in `pom.xml` or active profiles. As long as users invoke the detector through the orchestrator, the prerequisites are satisfied by construction — no static analysis required.

### Runtime fork-detection (standalone library use)

Static parsing of `pom.xml` is explicitly out of scope. Profile-resolved Surefire configuration cannot be reliably reproduced from inside the test JVM (profiles, system-property overrides, plugin inheritance), and a partial parser would produce both false positives and false negatives.

Instead, when consumers use the library without the orchestrator, the component performs a **runtime fork-detection check** in `ResourceLeakMonitor.testPlanExecutionStarted`:

1. Write a per-fork marker file to `target/resource-leak-detector/forks/<pid>.marker` containing the current JVM PID and start timestamp.
2. List existing `*.marker` files in the same directory. Any markers older than the current JVM start (within a recent window, e.g., 5 minutes) suggest a prior fork of the same `mvn test` invocation has already run — i.e., `forkCount > 1` or `reuseForks=false`.
3. If prior markers are observed, log at WARN naming the suspected misconfiguration (`forkCount` > 1 or `reuseForks=false`), explaining the implication (cross-class leaks will be invisible or under-attributed), and pointing at the orchestrator + the documented Surefire snippet. The component does NOT refuse to run; it proceeds and reports what it can.
4. The marker directory is cleared at the start of each test plan when no prior markers are present (i.e., on the first fork), so stale markers from previous days don't trigger false warnings.

This catches the actual failure mode (multiple JVMs servicing one test plan) without relying on accurate static analysis. The trade-off: the warning fires after the first misconfigured fork has already run and produced a partial report; that's acceptable since the orchestrator is the recommended path for users who care.

### Documentation

User-facing documentation MUST include:

- A note that the orchestrator is the recommended invocation and handles all prerequisites automatically.
- For standalone-library users: an example Surefire snippet that sets `forkCount=1`, `reuseForks=true`, and `junit.jupiter.extensions.autodetection.enabled=true`.
- A description of the runtime fork-detection warning and how to fix the underlying configuration.

## C1 — Test Runner + Raw Report

### Components

**ResourceLeakMonitor**

JUnit Platform `TestExecutionListener` that orchestrates suite-level lifecycle.

* Registered globally via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`.
* In `testPlanExecutionStarted`: writes the per-fork marker (see [Runtime fork-detection](#runtime-fork-detection-standalone-library-use)), initializes `ResourceState`, and triggers the **baseline snapshot** (each enabled monitor captures its current state — these resources are excluded from leak detection).
* In `testPlanExecutionFinished`: triggers the **final snapshot**, then writes the raw report to disk and invokes the attribution component (C3) if running in single-pass mode.

**ResourceLeakMonitorTestLifecycleExtension**

JUnit Jupiter `Extension` that drives per-class (default) or per-test (opt-in) snapshots.

* Implements all four callbacks: `BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`.
* Only the `BeforeAll` and `AfterAll` callbacks take snapshots by default. The `BeforeEach`/`AfterEach` callbacks take snapshots only when `snapshot.granularity=test` is configured (see [Configuration](#configuration)). Per-test mode is opt-in because per-test snapshots multiply snapshot work by the average number of methods per class.
* Registered globally via `META-INF/services/org.junit.jupiter.api.extension.Extension`. Service-loader discovery means it applies to all test classes without `@ExtendWith` on individual tests.
* On each active callback, the extension records a timestamp and asks every enabled monitor to take a snapshot. Snapshots run synchronously in the test thread so they complete before the test or teardown proceeds.
* Stores per-class lifecycle intervals (`start` = `BeforeAll` time, `end` = `AfterAll` time) in `ResourceState`. Per-test intervals are also stored when `snapshot.granularity=test`.
* When `preclass.settle.enabled=true`, the `BeforeAllCallback` performs the pre-class settle wait described in [Pre-class settle wait](#pre-class-settle-wait) before recording the boundary timestamp and snapshot.

**ResourceState**

Singleton holding the **small, in-memory** state needed during a run. Snapshot history is *not* held here — it is streamed to disk by `RawReportWriter` as each callback fires (see [Streaming write](#streaming-write)). The size difference matters: a zos-sized run produces ~100 MB of snapshot history but the live state is only KB-scale.

* **Current state**: per-monitor last-observed discrete resource sets and last-observed numeric values. Used by `ResourceLeakReporter` to compute the leak list (final - baseline) and to feed `RawReportWriter` at each callback.
* **Baseline state**: per-monitor baseline discrete sets / numeric values, captured in `testPlanExecutionStarted`. Used to suppress baseline resources from leak detection.
* **Per-class lifecycles**: `Map<TestClassName, TestClassLifecycle>`. Per-test intervals also recorded when `snapshot.granularity=test`.
* **No polling-era fields**: no `last detected time`, no `destroyed time`. With boundary-only snapshots, presence/absence at each snapshot is enough.

**ResourceMonitor**

Base interface for resource monitoring. Each monitor declares its required runtime dependencies. Before instantiating any monitor, the component verifies the required classes are on the runtime classpath via `Class.forName(...)`. If a configured monitor's dependency is missing, the library fails fast with a clear error naming the monitor and the missing dependency.

| Monitor | Required runtime dependency |
|---|---|
| `PortMonitor` | None (uses platform shell tools) |
| `ThreadMonitor` | None (uses JDK `Thread` APIs) |
| `SystemPropertyMonitor` | None (uses JDK `System` APIs) |
| `EnvironmentVariableMonitor` | None (uses JDK `System` APIs) |
| `MemoryMonitor` | None (uses JDK `Runtime` APIs) |
| `DynamoDbLocalTableMonitor` | `software.amazon.awssdk:dynamodb` (test-scope in consumer) |

The library declares dependencies that are required only for specific monitors as `<scope>provided</scope>`.

Two subtypes:

* **DiscreteResourceMonitor**: Returns a `Set<ResourceId>` representing the resources currently observed. Implementations: `PortMonitor`, `ThreadMonitor`, `SystemPropertyMonitor`, `EnvironmentVariableMonitor`, `DynamoDbLocalTableMonitor`.
  * **Network ports** (`PortMonitor`): No standard JVM API enumerates bound ports for the current process, so platform-specific approaches are used:
    * RHEL 9: `ss -lntp` filtered by current PID, or `/proc/net/tcp` + `/proc/net/tcp6`.
    * macOS: `lsof -p <PID> -i` or `netstat -anp <PID>`.
  * **Threads** (`ThreadMonitor`): `Thread.getAllStackTraces().keys`, filtered to non-`TERMINATED`.
  * **System properties** (`SystemPropertyMonitor`): `System.getProperties()`.
  * **Environment variables** (`EnvironmentVariableMonitor`): `System.getenv()`.
  * **DynamoDB Local tables** (`DynamoDbLocalTableMonitor`): `DynamoDbClient.listTables()`.

* **NumericResourceMonitor**: Returns a single `NumericResourceMeasurement` (timestamp + value). Implementations: `MemoryMonitor` (`Runtime.totalMemory() - Runtime.freeMemory()`).

### Snapshot semantics

A snapshot at boundary B captures, for each enabled monitor, the resource set or numeric measurement observed at time B.

A leak is declared when the **final** snapshot (taken in `testPlanExecutionFinished`) shows resources or memory growth not present in the **baseline** snapshot. The intermediate snapshots (per-class, per-test) are the timing data used by the attribution component to compute candidate sets.

For threads, terminated threads are filtered out at snapshot time (`ThreadMonitor` ignores `TERMINATED` threads). Asynchronous thread/port release between test classes is handled by the optional pre-class settle wait below, not by a post-suite grace period.

For memory, a leak is declared if `final - baseline > memory.growth.threshold.mb`.

For DynamoDB Local tables, a leak is declared if a table exists in the final snapshot but not the baseline.

### Pre-class settle wait

When `preclass.settle.enabled=true` the extension waits for slow-to-release resources from the previous test class to clear before snapshotting at the next class's `BeforeAllCallback`. Threads and listening ports often take observable time to release after a class ends; without this wait, the next class's BeforeAll snapshot still shows them and widens the candidate set unnecessarily.

**State carried forward.** After each `AfterAllCallback`, the extension records, per applicable monitor (threads, ports), the **delta set**: resources present in the AfterAll snapshot but absent from the matching BeforeAll snapshot. This is the set of resources that appeared *during* that class. Only the immediately-previous class's delta is retained; older classes' deltas are discarded.

The delta lives as a private mutable field on `ResourceLeakMonitorTestLifecycleExtension`, not in `ResourceState`. JUnit Jupiter's ServiceLoader-discovered extensions are instantiated once per JVM test run (the engine's root extension registry holds a single instance and child registries inherit from it), so a private field on the extension naturally scopes the carry-over state to "this test run" and is reset by JVM lifetime. Under the default sequential execution model, callbacks for different classes do not overlap, so no synchronization is needed; if parallel test execution were ever enabled this state would need to move to a context-keyed store.

**Wait algorithm at `BeforeAllCallback`** (when enabled and a previous-class delta exists):

1. For each applicable monitor, compute `carryover = previousClassDelta ∩ currentSnapshot`. Resources in the previous-class delta but no longer present have already settled.
2. If `carryover` is empty for all monitors, proceed without waiting.
3. Otherwise, sleep `preclass.settle.poll.interval.seconds` (default 1s), re-snapshot the applicable monitors, recompute carry-over, and repeat — until either carry-over is empty or `preclass.settle.max.seconds` (default 10s) has elapsed.
4. Log at DEBUG level the resources being waited on at each iteration and the total elapsed wait at completion. If the timeout elapses with carry-over still non-empty, log at WARN naming the still-present resources, then proceed.
5. Take the boundary snapshot and record the BeforeAll timestamp **after** the wait completes. The lifecycle interval `[start, end]` for the new class therefore begins at the post-wait timestamp.

**Scope.** The wait applies only to threads and ports. System properties, environment variables, memory, and DynamoDB Local tables release synchronously (or, in the memory case, via GC at unpredictable times that polling cannot influence) and are excluded — waiting on them would mask real leaks rather than reduce attribution noise.

**First class.** The first class in the suite has no previous-class delta, so the wait is a no-op by construction. No special-case code is required.

**Why BeforeAll only.** The wait is intentionally not applied at `BeforeEachCallback`. Per-method settling would multiply the wait by methods-per-class with diminishing return on attribution sharpness, since the default attribution granularity is class-level. If `snapshot.granularity=test` is enabled in the future, per-test settling can be added under a separate config flag.

**Interaction with raw report.** The settle wait is a snapshot-timing concern only. It does not tag resources in the raw report or alter C3's attribution algorithm — its sole effect is to push the BeforeAll timestamp later, which naturally tightens the candidate sets C3 computes from lifecycle intervals.

### Raw report (C1 output)

The raw report is written to `target/resource-leak-detector/raw-report.json` (configurable via `raw.report.output.path`) as [JSON Lines](https://jsonlines.org/): one self-describing JSON object per line, distinguished by the `type` field. Three record types appear, in order: a single `header` line at suite start, one `snapshot` line per lifecycle callback, and a single `footer` line at suite end with the per-class lifecycle map.

Schema:

```
# header — first line, written from testPlanExecutionStarted
{
  "type": "header",
  "runId": "<uuid>",
  "startedAt": "<iso8601>",
  "monitors": ["ports", "threads", ...],
  "snapshotGranularity": "class" | "test"
}

# snapshot — one per active callback (BASELINE, BEFORE_ALL, BEFORE_EACH, AFTER_EACH, AFTER_ALL, FINAL)
{
  "type": "snapshot",
  "kind": "BASELINE" | "BEFORE_ALL" | "BEFORE_EACH" | "AFTER_EACH" | "AFTER_ALL" | "FINAL",
  "timestamp": "<iso8601>",
  "testClass": "<fqcn>" | null,
  "testMethod": "<methodName>" | null,
  "discrete": {
    "ports":      [<int>, ...],
    "threads":    [{"name": "<string>", "id": "<long-as-string>"}, ...],
    "systemprops":[<string>, ...],
    "envvars":    [<string>, ...],
    "ddbtables":  [<string>, ...]
  },
  "numeric": {
    "memory": {"value": "<long-as-string>", "timestamp": "<iso8601>"}
  }
}

# footer — last line, written from testPlanExecutionFinished
{
  "type": "footer",
  "finishedAt": "<iso8601>",
  "lifecycles": [
    { "testClass": "<fqcn>", "start": "<iso8601>", "end": "<iso8601>" },
    ...
  ]
}
```

Each snapshot records the full resource set observed at that boundary. Discrete resource keys correspond to the same config-value names accepted in `monitored.resource.types`; a key only appears if its monitor is enabled. Numeric values and thread IDs are emitted as JSON strings to avoid loss of precision (Long > 2^53). C3 computes deltas between consecutive snapshots when needed for attribution; the on-disk format itself is uncompressed for simplicity and readability.

Estimated size for the zos suite (~841 test classes, per-class snapshots): ~60 KB/snapshot × ~1,684 snapshots ≈ ~100 MB per run. Acceptable for v1; revisit with delta encoding or compression if it becomes unwieldy in practice.

A separate human-readable text report is written alongside as `leak-report.txt` (sibling of the raw report). The text report mirrors the existing logged output and serves as the user-facing leak summary; the raw report is the machine-readable contract consumed by C3.

### Streaming write

The raw report is **streamed to disk during the test run**, not buffered in memory and flushed at the end. Each callback that produces a snapshot serializes it and appends it to the open report file before returning control to the test runner. Rationale:

- A ~100 MB in-memory accumulation across an entire suite is wasteful and risks OOM on large suites.
- If the JVM crashes or is killed mid-run, a streamed report still contains everything captured up to the crash — useful for diagnosing the crash itself.
- Writes happen on the test thread (snapshotting is already synchronous), so no separate I/O thread is needed.

The report uses a streaming-friendly format: a JSON header (run-level metadata) followed by [JSON Lines](https://jsonlines.org/) — one snapshot object per line. C3 reads this back as a streaming parse rather than loading the whole file. The closing wrapper / metadata is finalized in `testPlanExecutionFinished`.

The schema is the contract between C1 and C3: everything C3 needs to compute candidate sets must be derivable from the raw report alone.

## C2 — Second Run (Optional)

C2 is packaged as a **Maven plugin** (the `orchestrator` module). Distributed via the same Maven artifact pipeline as the library, versioned alongside it, and invoked by users as `mvn com.salesforce.test:junit-leak-detector-orchestrator:run` (or via a binding in their pom). A plugin is preferred over a shell script because it's portable across CI environments, native to the Maven workflow consumers already use, and avoids shipping a separate distribution channel.

When double-run mode is enabled, the plugin invokes the test suite twice. **C2 controls the full Surefire configuration for both runs** so neither run depends on whatever Surefire defaults the project happens to have. Flags C2 sets explicitly on each invocation:

- `surefire.runOrder` — `alphabetical` for run 1, `random` for run 2
- `surefire.runOrderRandomSeed` — generated and recorded for run 2
- `forkCount=1`, `reuseForks=true` — required for cross-class leak detection
- `junit.jupiter.extensions.autodetection.enabled=true` — required for the extension to load
- Output paths so each run writes to its own raw report file

Outputs:
1. Run 1: `raw-report-1.json`
2. Run 2: `raw-report-2.json`

C2 is a thin orchestrator. The actual capture work is done by C1 in each invocation. C1 is unaware of run pairing — it just writes one raw report per JVM lifetime; C3 consumes both.

Default mode is single-run; double-run is opt-in via `runs=2` in configuration or the `-Dresource.leak.detector.runs=2` system property.

### Build failure with double-run mode

When `runs=2` and `build.failure.enabled=true`, **both runs always execute**. The build-failure decision is deferred to C3 after it has intersected candidate sets across both runs. Run 1 never short-circuits the suite, even if it observes leaks — the second run is needed for the sharper attribution that justifies failing the build in the first place.

## C3 — Attribution

A separate, post-process component. Input: one or two raw reports from C1/C2. Output: the final human-readable leak report.

C3 has no dependency on JUnit, no test execution, no resource monitoring code. It is pure data transformation and is re-runnable against existing raw reports without re-running the suite.

C3 ships in two forms backed by the same code:

- **Inline**: invoked from `ResourceLeakMonitor.testPlanExecutionFinished` when `runs=1`. Reads its own just-written raw report and prints the final leak report. Zero-friction default.
- **Standalone CLI**: required for `runs=2` (C1 cannot see the second run from inside the first), and useful for re-running attribution against saved raw reports without re-running tests. Distributed alongside the library.

### Candidate-set computation

For each leak, C3:

1. Identifies the **detection window** as `[t_last_absent, t_first_present]`, where `t_last_absent` is the timestamp of the most recent snapshot in which the resource was absent, and `t_first_present` is the timestamp of the earliest subsequent snapshot in which it was present. (For numeric resources, the analogous calculation uses the threshold-crossing point.)
2. Computes the **candidate set** as every test class whose `[start, end]` lifecycle interval intersects the detection window. A class that ended before `t_last_absent` is excluded; a class that started after `t_first_present` is excluded.
3. The candidate set must be non-empty. If C3 computes an empty set, it logs a defect indicator (this means the lifecycle data or snapshot ordering is inconsistent — a bug to be tracked).

For double-run mode:

1. C3 computes per-run candidate sets independently (using each run's own snapshot stream and lifecycle map).
2. C3 then **intersects** the candidate sets across the two runs. The intersection is the final reported candidate set.
3. If the intersection is empty (rare — would mean the leak appeared in completely disjoint candidate sets across orderings), C3 falls back to the union and notes it in the report.

### Final report

Plain text by default; optional JSON output for tooling. See the Appendix for an example.

## Data Structures

### Resource interfaces

```kotlin
interface DiscreteResourceMonitor {
    fun snapshot(): Set<ResourceId>
}

interface NumericResourceMonitor {
    fun snapshot(): NumericResourceMeasurement
}
```

### ResourceId

```kotlin
sealed class ResourceId {
    data class PortId(val port: Int) : ResourceId()
    data class ThreadId(val name: String, val id: Long) : ResourceId()
    data class PropertyId(val name: String) : ResourceId()
    data class EnvironmentVariableId(val name: String) : ResourceId()
    data class DynamoDbTableId(val name: String) : ResourceId()
}
```

### Snapshot

```kotlin
data class Snapshot(
    val kind: SnapshotKind,
    val timestamp: Instant,
    val testClass: String?,
    val testMethod: String?,
    val discrete: Map<ResourceTypeName, Set<ResourceId>>,
    val numeric: Map<ResourceTypeName, NumericResourceMeasurement>
)

enum class SnapshotKind { BASELINE, BEFORE_ALL, BEFORE_EACH, AFTER_EACH, AFTER_ALL, FINAL }
```

### Test class lifecycle

```kotlin
data class TestClassLifecycle(
    val start: Instant,
    val end: Instant
)
```

### Numeric measurement

```kotlin
data class NumericResourceMeasurement(
    val value: Long,
    val timestamp: Instant
)
```

### Optional metadata: thread stack traces

For thread leaks, `ThreadMonitor` may attach the thread's current stack trace (`Thread.getAllStackTraces()`) at the snapshot in which the thread first appeared, as descriptive metadata on the leak entry. This is reporting-only and does not influence candidate set computation. For all other resource types, no creator stack trace is captured today; see the Backlog section for the planned allocation-time stack-trace capture.

## Configuration

Configuration values are owned by the consuming project. They are read at startup from `resource-leak-detector.properties` on the test classpath, with optional system property overrides of the form `resource.leak.detector.<key>`.

```properties
# Resource selection
monitored.resource.types=ports,threads,memory

# Detection thresholds
memory.growth.threshold.mb=1024

# Build failure
build.failure.enabled=false
build.failure.resource.types=

# Run mode
runs=1                       # 1 = single run; 2 = double-run with differential ordering
snapshot.granularity=class   # class = BeforeAll/AfterAll only; test = also BeforeEach/AfterEach

# Pre-class settle wait
preclass.settle.enabled=false
preclass.settle.max.seconds=10
preclass.settle.poll.interval.seconds=1
```

| Key | Default | Notes |
|---|---|---|
| `monitored.resource.types` | (empty) | Comma-separated. Valid: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. |
| `memory.growth.threshold.mb` | `1024` | Memory leak threshold. |
| `build.failure.enabled` | `false` | Master switch for build failure on detected leaks. |
| `build.failure.resource.types` | (empty) | Comma-separated list of resource types whose leaks fail the build. |
| `runs` | `1` | Set to `2` to enable C2's differential-ordering mode. |
| `snapshot.granularity` | `class` | `class` = snapshots at `BeforeAllCallback`/`AfterAllCallback` only. `test` = also at `BeforeEachCallback`/`AfterEachCallback` for fine-grained debugging at the cost of ~Nx more snapshot operations. |
| `preclass.settle.enabled` | `false` | When `true`, the extension waits for threads/ports that appeared during the previous class to clear before snapshotting at the next class's `BeforeAll`. |
| `preclass.settle.max.seconds` | `10` | Maximum total time to wait for carry-over resources to clear. |
| `preclass.settle.poll.interval.seconds` | `1` | Poll interval while waiting. |
| `raw.report.output.path` | `target/resource-leak-detector/raw-report.json` | Path of the streaming JSON Lines raw report. The human-readable text report is written as `leak-report.txt` in the same directory. |

When the Maven dependency is commented out to disable the component, the properties file remains on disk unused.

## Appendix

### Leak Report Format

```
Resource Leak Detector Report
==============================

Run mode: double (intersected across 2 runs)
Run 1: alphabetical
Run 2: random (seed: 4815162342)

Network Port Leaks:
  - Port: 8080
    Detection Window: [2024-01-15T10:30:40.000Z, 2024-01-15T10:30:45.000Z]
    Candidate Set (1 class):
      - com.salesforce.zos.TestClass1
        Started: 2024-01-15T10:30:40.000Z
        Ended:   2024-01-15T10:30:50.000Z

Thread Leaks:
  - Thread: test-thread-1 (ID: 12345)
    Detection Window: [2024-01-15T10:30:55.000Z, 2024-01-15T10:31:00.456Z]
    Stack Trace at First Snapshot:
      at java.lang.Thread.sleep(Native Method)
      at com.example.LeakyHelper.startBackgroundWorker(LeakyHelper.java:42)
      at com.salesforce.zos.TestClass2.setup(TestClass2.kt:18)
    Candidate Set (1 class):
      - com.salesforce.zos.TestClass2
        Started: 2024-01-15T10:30:55.000Z
        Ended:   2024-01-15T10:31:05.000Z

Memory Leaks:
  - Baseline: 256 MB
  - Final:    320 MB
  - Increase: 64 MB
  - Threshold-cross window: [2024-01-15T10:31:25.000Z, 2024-01-15T10:31:30.123Z]
  - Candidate Set (1 class):
    - com.salesforce.zos.TestClass5
      Started: 2024-01-15T10:31:25.000Z
      Ended:   2024-01-15T10:31:35.000Z
```

### FAQ

**Why both `TestExecutionListener` and `Extension`?**

* `TestExecutionListener` (JUnit Platform): platform-level, sees the entire test plan across all engines. Provides `testPlanExecutionStarted` / `testPlanExecutionFinished` — the only hooks that bracket *all* tests across *all* engines. Used for baseline capture, final capture, pre-flight, and report writing.
* `Extension` (JUnit Jupiter): engine-level, provides per-class and per-test boundaries (`BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`). Used to drive the boundary snapshots.

The two together give complete coverage: global suite boundaries via the listener, per-class and per-test boundaries via the extension.

**Why no scheduled polling?**

A resource that is allocated and released within a single test is not a leak by definition — capturing intra-test transient state is a different feature (intra-test observability) and out of scope. Boundary-only snapshots eliminate the polling-cadence and "first poll after JVM warm-up" attribution artifacts that the previous polling design was prone to.

### Backlog

**B1 — Allocation-time stack-trace capture.** Bytecode-instrumentation-based capture of the stack at the moment a resource is allocated (port open, table create, thread spawn, system property set), via a Java agent built on ASM or ByteBuddy. This would let the component report the exact line that allocated each leaked resource, bypassing the candidate-set step entirely and resolving lazy-allocation cases (e.g., a Jetty server started in test A whose `qtp*` expansion threads spawn under load during test B). Deferred from v1 — boundary snapshots plus optional differential ordering should keep candidate sets small enough in practice.
