# Resource Leak Detector - Design

> **Note**: For requirements, see [resource-leak-detector-requirements.md](./resource-leak-detector-requirements.md).

## Overview

The Resource Leak Detector monitors and reports resource leaks in unit tests, tracking network ports, threads, system properties, environment variables, memory usage, and DynamoDB Local tables.

Per the requirements (REQ-1.2.1, REQ-1.2.2), resource snapshots are captured **at JUnit lifecycle boundaries only** — never via scheduled wall-clock polling. The component uses JUnit Platform's `TestExecutionListener` API for global test-plan lifecycle (suite start/end) and a globally-registered JUnit Jupiter `Extension` for per-class and per-test lifecycle (`BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`).

The component is split into three independent pieces (per the Architecture section of the requirements):

- **C1 — Test runner + raw report.** Captures resource snapshots at lifecycle boundaries during a single test run; emits a structured raw report. No attribution logic.
- **C2 — Second run (optional).** Drives the test runner a second time with a different Surefire `runOrder`; emits its own raw report.
- **C3 — Attribution.** Independent post-processor. Consumes one or two raw reports; computes the candidate set per leak; emits the final human-readable leak report.

All components live in package `com.salesforce.test.extensions.resourceleak`.

All timestamps use ISO8601 format.

### Packaging and Distribution

The Resource Leak Detector is maintained in its own standalone codebase, separate from any consuming project. It is published as a Maven artifact (e.g., `com.salesforce.test:resource-leak-detector:<version>`) and consumed as a `test`-scoped dependency.

Auto-registration with JUnit means consumers do not need to modify test code:

* `ResourceLeakMonitor` (the `TestExecutionListener`) is discovered via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`.
* `ResourceLeakMonitorTestLifecycleExtension` (the JUnit Jupiter `Extension`) is discovered via `META-INF/services/org.junit.jupiter.api.extension.Extension` (with `junit.jupiter.extensions.autodetection.enabled=true` set in the consuming project's Surefire configuration).

Configuration is supplied by the consuming project via a properties file on the test classpath, with optional system property overrides (see [Configuration](#configuration)). The artifact contains no consumer-specific references.

To fully disable the Resource Leak Detector with zero runtime overhead, the consuming project omits (or comments out) the Maven dependency. With the JAR off the test classpath, ServiceLoader discovers nothing, no classes from the library are loaded, and no hooks fire.

## Pre-flight Configuration Check

Per REQ-3.1–REQ-3.4, before any tests run the component verifies the project's build configuration meets the prerequisites for reliable detection.

**ResourcePreflightChecker** is invoked from `ResourceLeakMonitor.testPlanExecutionStarted` before any state is initialized. v1 supports Maven only — if the component cannot locate a `pom.xml` for the running suite, the checker fails fast with an "unsupported build tool" error. Support for other build tools (Gradle, Bazel) is out of scope for v1.

For Maven projects, the checker inspects `pom.xml` resolved against the active profile and verifies:

| Setting | Required value | Why |
|---|---|---|
| `forkCount` | `1` | Ensures all test classes share one JVM so cross-class sticky leaks are observable. |
| `reuseForks` | `true` | Same — `false` resets state between forks and hides cross-class leaks. |

If any required setting is missing or wrong, the checker throws a fatal error with a clear message naming the offending setting, the value found, the value required, and an example Surefire snippet. The component does not proceed.

The check can be disabled via configuration (`preflight.enabled=false`) for advanced users running custom build flows; doing so prints a warning and proceeds at the user's risk.

## C1 — Test Runner + Raw Report

### Components

**ResourceLeakMonitor**

JUnit Platform `TestExecutionListener` that orchestrates suite-level lifecycle.

* Registered globally via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`.
* In `testPlanExecutionStarted`: runs `ResourcePreflightChecker`, initializes `ResourceState`, and triggers the **baseline snapshot** (each enabled monitor captures its current state — these resources are excluded from leak detection per REQ-1.2.3).
* In `testPlanExecutionFinished`: triggers the **final snapshot** and the thread-grace-period check, then writes the raw report to disk and invokes the attribution component (C3) if running in single-pass mode.

**ResourceLeakMonitorTestLifecycleExtension**

JUnit Jupiter `Extension` that drives per-class (default) or per-test (opt-in) snapshots.

* Implements all four callbacks: `BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`.
* Only the `BeforeAll` and `AfterAll` callbacks take snapshots by default. The `BeforeEach`/`AfterEach` callbacks take snapshots only when `snapshot.granularity=test` is configured (see [Configuration](#configuration)). Per-test mode is opt-in because per-test snapshots multiply snapshot work by the average number of methods per class.
* Registered globally via `META-INF/services/org.junit.jupiter.api.extension.Extension`. Service-loader discovery means it applies to all test classes without `@ExtendWith` on individual tests.
* On each active callback, the extension records a timestamp and asks every enabled monitor to take a snapshot. Snapshots run synchronously in the test thread (REQ-1.2.1) so they complete before the test or teardown proceeds.
* Stores per-class lifecycle intervals (`start` = `BeforeAll` time, `end` = `AfterAll` time) in `ResourceState`. Per-test intervals are also stored when `snapshot.granularity=test`.

**ResourceState**

Singleton holding all data captured during a run.

* **Per-class lifecycles**: `Map<TestClassName, TestClassLifecycle>`.
* **Snapshots**: an ordered list of `Snapshot` objects, each with a timestamp, a snapshot kind (`BASELINE`, `BEFORE_ALL`, `BEFORE_EACH`, `AFTER_EACH`, `AFTER_ALL`, `FINAL`), the test class and method (where applicable), and the per-monitor resource sets / numeric measurements captured at that boundary.
* **No polling-based fields, no `last detected time`, no `destroyed time`**: these were polling-era artifacts. With boundary-only snapshots, presence/absence at each snapshot is enough.

**ResourceMonitor**

Base interface for resource monitoring. Each monitor declares its required runtime dependencies. Before instantiating any monitor, the component verifies the required classes are on the runtime classpath via `Class.forName(...)`. If a configured monitor's dependency is missing, the library fails fast with a clear error naming the monitor and the missing dependency (REQ-4.1.3).

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

For threads, the final snapshot is followed by a grace-period wait (REQ-1.3.3, default 10s) before re-snapshotting. Threads that have reached `TERMINATED` during the grace period are dropped from the leak list (REQ-1.3.4).

For memory, a leak is declared if `final - baseline > memory.growth.threshold.mb` (REQ-1.3.5).

For DynamoDB Local tables, a leak is declared if a table exists in the final snapshot but not the baseline (REQ-1.3.6).

### Raw report (C1 output)

The raw report is a structured JSON document written to `target/resource-leak-detector/raw-report.json`. Schema:

```
{
  "runId": "<uuid>",
  "startedAt": "<iso8601>",
  "finishedAt": "<iso8601>",
  "config": { ... },                        // resolved config (snapshotted)
  "surefire": {
    "runOrder": "alphabetical",
    "runOrderRandomSeed": null              // populated for second run
  },
  "monitors": ["ports", "threads", ...],
  "lifecycles": [
    { "testClass": "...", "start": "...", "end": "..." },
    ...
  ],
  "snapshotGranularity": "class" | "test",
  "snapshots": [
    {
      "kind": "BASELINE" | "BEFORE_ALL" | "BEFORE_EACH" | "AFTER_EACH" | "AFTER_ALL" | "FINAL",
      "timestamp": "...",
      "testClass": "..." | null,
      "testMethod": "..." | null,
      "discrete": { "ports": [...], "threads": [...], ... },          // full resource set at this boundary
      "numeric":  { "memory": <bytes> }
    },
    ...
  ]
}
```

Each snapshot records the full resource set observed at that boundary. C3 computes deltas between consecutive snapshots when needed for attribution; the on-disk format itself is uncompressed for simplicity and readability.

Estimated size for the zos suite (~841 test classes, per-class snapshots): ~60 KB/snapshot × ~1,684 snapshots ≈ ~100 MB per run. Acceptable for v1; revisit with delta encoding or compression if it becomes unwieldy in practice.

### Streaming write

The raw report is **streamed to disk during the test run**, not buffered in memory and flushed at the end. Each callback that produces a snapshot serializes it and appends it to the open report file before returning control to the test runner. Rationale:

- A ~100 MB in-memory accumulation across an entire suite is wasteful and risks OOM on large suites.
- If the JVM crashes or is killed mid-run, a streamed report still contains everything captured up to the crash — useful for diagnosing the crash itself.
- Writes happen on the test thread (REQ-1.2.1 already requires synchronous snapshotting), so no separate I/O thread is needed.

The report uses a streaming-friendly format: a JSON header (run-level metadata) followed by [JSON Lines](https://jsonlines.org/) — one snapshot object per line. C3 reads this back as a streaming parse rather than loading the whole file. The closing wrapper / metadata is finalized in `testPlanExecutionFinished`.

The schema is the contract between C1 and C3 (REQ-2.3.2): everything C3 needs to compute candidate sets must be derivable from the raw report alone.

## C2 — Second Run (Optional)

When double-run mode is enabled (REQ-2.4.1), C2 invokes the test suite twice. **C2 controls the full Surefire configuration for both runs** so neither run depends on whatever Surefire defaults the project happens to have. Flags C2 sets explicitly on each invocation:

- `surefire.runOrder` — `alphabetical` for run 1, `random` for run 2
- `surefire.runOrderRandomSeed` — generated and recorded for run 2 (REQ-2.4.3)
- `forkCount=1`, `reuseForks=true` — required by REQ-3.2
- `junit.jupiter.extensions.autodetection.enabled=true` — required for the extension to load
- Output paths so each run writes to its own raw report file

Outputs:
1. Run 1: `raw-report-1.json`
2. Run 2: `raw-report-2.json`

C2 is a thin orchestrator. The actual capture work is done by C1 in each invocation. C1 is unaware of run pairing — it just writes one raw report per JVM lifetime; C3 consumes both.

Default mode is single-run (REQ-2.4.4); double-run is opt-in via `runs=2` in configuration or the `-Dresource.leak.detector.runs=2` system property.

### Build failure with double-run mode

When `runs=2` and `build.failure.enabled=true`, **both runs always execute**. The build-failure decision is deferred to C3 after it has intersected candidate sets across both runs. Run 1 never short-circuits the suite, even if it observes leaks — the second run is needed for the sharper attribution that justifies failing the build in the first place.

## C3 — Attribution

A separate, post-process component (REQ-2.3.1). Input: one or two raw reports from C1/C2. Output: the final human-readable leak report.

C3 has no dependency on JUnit, no test execution, no resource monitoring code. It is pure data transformation and is re-runnable against existing raw reports without re-running the suite.

C3 ships in two forms backed by the same code:

- **Inline**: invoked from `ResourceLeakMonitor.testPlanExecutionFinished` when `runs=1`. Reads its own just-written raw report and prints the final leak report. Zero-friction default.
- **Standalone CLI**: required for `runs=2` (C1 cannot see the second run from inside the first), and useful for re-running attribution against saved raw reports without re-running tests. Distributed alongside the library.

### Candidate-set computation (REQ-2.2)

For each leak, C3:

1. Identifies the **detection window** as `[t_last_absent, t_first_present]`, where `t_last_absent` is the timestamp of the most recent snapshot in which the resource was absent, and `t_first_present` is the timestamp of the earliest subsequent snapshot in which it was present. (For numeric resources, the analogous calculation uses the threshold-crossing point.)
2. Computes the **candidate set** as every test class whose `[start, end]` lifecycle interval intersects the detection window. A class that ended before `t_last_absent` is excluded; a class that started after `t_first_present` is excluded.
3. Per REQ-2.2.2, the candidate set must be non-empty. If C3 computes an empty set, it logs a defect indicator (this means the lifecycle data or snapshot ordering is inconsistent — a bug to be tracked).

For double-run mode:

1. C3 computes per-run candidate sets independently (using each run's own snapshot stream and lifecycle map).
2. C3 then **intersects** the candidate sets across the two runs (REQ-2.4.1). The intersection is the final reported candidate set.
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

For thread leaks, `ThreadMonitor` may attach the thread's current stack trace (`Thread.getAllStackTraces()`) at the snapshot in which the thread first appeared, as descriptive metadata on the leak entry. This is reporting-only and does not influence candidate set computation (REQ-2.1.1). For all other resource types, no creator stack trace is captured today; see the Backlog section for the planned allocation-time stack-trace capture.

## Configuration

Configuration values are owned by the consuming project. They are read at startup from `resource-leak-detector.properties` on the test classpath, with optional system property overrides of the form `resource.leak.detector.<key>`.

```properties
# Resource selection
monitored.resource.types=ports,threads,memory

# Detection thresholds
thread.grace.period.seconds=10
memory.growth.threshold.mb=1024

# Build failure
build.failure.enabled=false
build.failure.resource.types=

# Run mode
runs=1                       # 1 = single run; 2 = double-run with differential ordering
snapshot.granularity=class   # class = BeforeAll/AfterAll only; test = also BeforeEach/AfterEach

# Pre-flight
preflight.enabled=true
```

| Key | Default | Notes |
|---|---|---|
| `monitored.resource.types` | (empty) | Comma-separated. Valid: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. |
| `thread.grace.period.seconds` | `10` | Per REQ-1.3.3. |
| `memory.growth.threshold.mb` | `1024` | Per REQ-1.3.5 (with threshold). |
| `build.failure.enabled` | `false` | Master switch; per REQ-4.2.1. |
| `build.failure.resource.types` | (empty) | Per REQ-4.2.2. |
| `runs` | `1` | Set to `2` to enable C2's differential-ordering mode (REQ-2.4). |
| `snapshot.granularity` | `class` | `class` = snapshots at `BeforeAllCallback`/`AfterAllCallback` only. `test` = also at `BeforeEachCallback`/`AfterEachCallback` for fine-grained debugging at the cost of ~Nx more snapshot operations. |
| `preflight.enabled` | `true` | Disable only for advanced users with custom build flows. |

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

Per REQ-1.2.1 and REQ-1.2.4: a resource that is allocated and released within a single test is not a leak by definition — capturing intra-test transient state is a different feature (intra-test observability) and out of scope. Boundary-only snapshots eliminate the polling-cadence and "first poll after JVM warm-up" attribution artifacts that the previous polling design was prone to.

### Backlog

**B1 — Allocation-time stack-trace capture.** Bytecode-instrumentation-based capture of the stack at the moment a resource is allocated (port open, table create, thread spawn, system property set), via a Java agent built on ASM or ByteBuddy. This would let the component report the exact line that allocated each leaked resource, bypassing the candidate-set step entirely and resolving lazy-allocation cases (e.g., a Jetty server started in test A whose `qtp*` expansion threads spawn under load during test B). Deferred from v1 — boundary snapshots plus optional differential ordering should keep candidate sets small enough in practice.
