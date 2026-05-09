# Resource Leak Detector - Requirements

> **Note**: For design and implementation details, see [design.md](./design.md).

## Purpose

The Resource Leak Detector (the component) identifies unit tests that are leaking resources. It monitors and detects leaks in the following resource types:

- **Network ports** - Opened network ports that are not properly closed
- **Threads** - Threads that remain active well after test completion
- **System properties** - System properties that are set but not cleaned up
- **System environment variables** - Environment variables that are modified but not restored
- **Memory (heap usage)** - Memory that remains allocated after test completion
- **DynamoDB Local tables** - DynamoDB Local tables that are created but not deleted

## Architecture

The component is split into two independent pieces. Each can be invoked, replaced, or re-run independently of the other.

- **C1 — Test runner + raw report.** Runs the unit-test suite, captures resource lifecycle data, emits a structured raw report. Knows nothing about attribution.
- **C2 — Attribution.** Independent. Takes a raw report as input and produces attribution output (the candidate set per leak). Can be re-run without re-running tests.

An optional AI-assisted "skill" layer may sit on top of C2, applying name heuristics and source grep to narrow candidate sets to a single most-likely owner. The skill layer is out of scope for this component but the C2 output format MUST support it cleanly.

## Requirements

### 1. Lifecycle Management

#### 1.1 Component Initialization
- **REQ-1.1.1**: The component MUST start before any unit tests run.
- **REQ-1.1.2**: Resource leaks MUST be detectable when running `make test` (or the project's standard test entry point).

#### 1.2 Resource Snapshots
- **REQ-1.2.1**: The component MUST capture resource snapshots at JUnit lifecycle boundaries: `BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`. Snapshots MUST run synchronously in the test thread so they complete before the test or teardown proceeds.
- **REQ-1.2.2**: A test class MUST be considered started before or exactly when the test class setup begins and completed only when its tear down is fully complete.
- **REQ-1.2.3**: A baseline snapshot MUST be established before any tests run. Resources present in the baseline MUST be excluded from leak detection.
- **REQ-1.2.4**: Snapshot scope is limited to JUnit lifecycle boundaries (REQ-1.2.1). Resources that are allocated and released within a single test are out of scope; only resources still present at a lifecycle boundary are considered for leak detection.
- **REQ-1.2.5**: The component MUST support an optional **pre-class settle wait** at the `BeforeAllCallback` boundary. When enabled, before taking the boundary snapshot the component computes the **carry-over set**: resources that appeared during the immediately-previous test class (i.e., present in that class's `AfterAllCallback` snapshot but absent from its `BeforeAllCallback` snapshot) and that are still present now. If the carry-over set is non-empty, the component polls at a configurable interval (default: 1 second) until either (a) all carry-over resources have been released, or (b) a configurable maximum wait has elapsed (default: 10 seconds). The boundary snapshot is then taken and timestamped after the wait completes. Rationale: threads and listening ports often take observable time to fully release after a test class ends; without a settle wait, the next class's `BeforeAll` snapshot still shows the resource and widens the candidate set unnecessarily. Scoping the wait to resources that appeared during the previous class (rather than to anything still present from the previous snapshot) avoids waiting on resources that span multiple classes by design. The wait does NOT apply at `BeforeEachCallback`: per-test settling would multiply wait time by methods-per-class with diminishing return on attribution sharpness, since attribution is class-level by default.
- **REQ-1.2.6**: The pre-class settle wait applies only to resource types that are known to release asynchronously: **threads** and **network ports**. Other monitored resource types (system properties, environment variables, memory, DynamoDB Local tables) are excluded — their release is synchronous from the test's perspective, and waiting on them would mask real leaks rather than reduce attribution noise. The wait MUST NOT cause baseline resources to be re-evaluated. The wait MUST log (at debug level) the resources it is waiting on and the elapsed wait time on completion; if the maximum wait elapses with carry-over resources still present, the component MUST log at WARN level naming the still-present resources, then proceed with the snapshot.

#### 1.3 Leak Reporting
- **REQ-1.3.1**: Leak reporting MUST occur after all tests have completed.
- **REQ-1.3.2**: Leak reports MUST be formatted as a list of leaks for monitored resource types, where each leak entry includes:
  - Resource details:
    - For network ports: port number
    - For threads: thread name and thread ID
    - For system properties: property name
    - For environment variables: variable name
    - For memory: baseline used heap, final used heap, and increase amount
    - For DynamoDB Local tables: table name
  - Timestamp when the resource was first observed (the snapshot at which the resource transitioned from absent to present), in ISO8601 format
  - The candidate set (per REQ-2.x)
- **REQ-1.3.3**: TERMINATED threads MUST NOT be considered as leaked.
- **REQ-1.3.4**: Memory MUST be considered leaked if the final used heap exceeds the baseline used heap.
- **REQ-1.3.5**: A DynamoDB Local table MUST be considered leaked if it exists in the final table list but not in the baseline table list.

### 2. Attribution

#### 2.1 Attribution is timing/lifecycle based
- **REQ-2.1.1**: Attribution MUST be based purely on test class lifecycle timing. Resource names (e.g., thread name patterns, table name prefixes) MAY be included in leak reports as descriptive metadata, but the candidate set MUST be computed from lifecycle intervals alone.
- **REQ-2.1.2**: Rationale: the component MUST work across any JVM project (Java, Kotlin, or other JVM languages) without project-specific tuning. Name-based heuristics belong in the optional skill layer described in the Architecture section.

#### 2.2 Candidate set computation
- **REQ-2.2.1**: For each leaked resource, the component MUST emit a candidate set: every test class whose `[start, end]` lifecycle interval intersects the detection window `[last-absent-snapshot, first-present-snapshot]`. A class that ended before the detection window MUST NOT be a candidate; a class that started after the detection window MUST NOT be a candidate.
- **REQ-2.2.2**: The candidate set for each leak MUST be non-empty. By REQ-2.2.1, an empty set is impossible if lifecycle data is correct; an empty set indicates a defect in the component.
- **REQ-2.2.3**: For each candidate test class, the report MUST include the timestamp when the class started and the timestamp when it ended, both in ISO8601 format.

#### 2.3 Attribution component is independent
- **REQ-2.3.1**: Attribution (C2) MUST be implemented as a separate component that consumes a raw report produced by C1 and produces the final report. Attribution MUST be re-runnable without re-running tests.
- **REQ-2.3.2**: The raw report format MUST contain enough information (per-resource appearance times, per-test-class lifecycle intervals) to compute candidate sets without re-instrumenting the suite.

### 3. Test-isolation Prerequisites

Reliable detection requires that all test classes share one JVM for the duration of the run. Two prerequisites flow from this:

- **REQ-3.1**: Consumers MUST configure Surefire with `forkCount=1` and `reuseForks=true`. Without these settings, cross-class leaks are invisible because each fork starts clean.
- **REQ-3.2**: The required Surefire settings (`forkCount=1`, `reuseForks=true`, plus `junit.jupiter.extensions.autodetection.enabled=true`) MUST be documented in user-facing documentation with an example pom snippet.
- **REQ-3.3**: At runtime, the component MUST detect whether the current JVM is being shared across the entire test plan by writing a per-fork marker file (containing the JVM PID and start timestamp) to the configured output directory at `testPlanExecutionStarted` and reading any pre-existing markers. If markers from prior forks of the same suite invocation are observed, the component MUST log a WARN naming the suspected misconfiguration (`forkCount` > 1 or `reuseForks=false`) and the implication (cross-class leaks will be invisible/under-attributed). The component MUST NOT refuse to run; it proceeds and reports what it can.
- **REQ-3.4**: Static `pom.xml` parsing is explicitly out of scope: profile-resolved Surefire configuration cannot be reliably reproduced at runtime, and a parser would either give false positives (missing profile-aware overrides) or false negatives (missing system-property overrides). The runtime fork-detection in REQ-3.3 covers the actual failure mode without depending on accurate static analysis.

### 4. Leak Detection and Analysis

#### 4.1 Resource Selection
- **REQ-4.1.1**: The component MUST support a configuration parameter that specifies a list of resource types to monitor.
- **REQ-4.1.2**: The component MUST only monitor and report leaks for resource types specified in the configuration; resource types not in the list MUST be ignored.
- **REQ-4.1.3**: When a configured monitor requires a runtime dependency that is not on the consuming project's classpath, the component MUST fail fast at startup with a clear error message naming the missing dependency and the monitor that requires it. The component MUST NOT silently disable the monitor.

#### 4.2 Build Failure on Leak Detection
- **REQ-4.2.1**: Build failure on leak detection is **the library's responsibility**, applied during normal `mvn test` invocations by the inline attribution path.
- **REQ-4.2.2**: The library MUST support a configuration parameter (`build.failure.resource.types`) that specifies a comma-separated list of resource types that should cause the build to fail when leaks are detected. Empty list = build failure disabled. Non-empty list = build failure enabled for the named types.
- **REQ-4.2.3**: When `build.failure.resource.types` is non-empty and leaks are detected for any of those types, the library MUST cause the build to fail at the end of the test run.
- **REQ-4.2.4**: When `build.failure.resource.types` is empty, or when leaks are detected only for types not in the list, the library MUST report leaks without failing the build.

### 5. Non-functional Requirements

- **REQ-5.1**: The extension MUST be maintained in its own standalone codebase, separate from any consumer project, and consumed as an external dependency.
- **REQ-5.2**: Component code MUST be written in Kotlin.
- **REQ-5.3**: The component MUST be decoupled from any consuming project's code, including avoiding consumer-specific references in code or comments.
- **REQ-5.4**: The component MUST be transparent to unit tests; unit tests MUST NOT require any modifications or accommodations to be monitored.
- **REQ-5.5**: The component MUST support running on RHEL 9 (Linux) for CI/production environments and macOS for local development environments.
- **REQ-5.6**: The component MUST use import statements rather than fully-qualified class names where possible.
- **REQ-5.7**: The component MUST minimize transitive dependencies. Runtime dependencies SHOULD be limited to JUnit Platform/Jupiter APIs and the platform-specific APIs required for resource detection (e.g., AWS SDK for DynamoDB Local). Convenience libraries that can be reasonably replaced with standard JDK constructs MUST NOT be added.

### 6. Testing

- **REQ-6.1**: Each component MUST have unit tests in the same Maven module as the code under test, run by Surefire at the `test` phase.
- **REQ-6.2**: End-to-end scenario verification MUST be done with Kotlin/JUnit tests run by Maven Failsafe (i.e., `*IT` classes invoked at the `verify` phase). Scenario verification logic MUST NOT live in shell scripts or Makefiles.
- **REQ-6.3**: The supported full-build invocation is `mvn install`. Scenario tests assume sibling modules are already installed in the local Maven repo, so `mvn verify` is insufficient on its own.

## Backlog

Items deferred but documented for future work.

### B1. Thread creation attribution (JFR)

The candidate-set approach narrows a thread leak to one or more test classes but does not point to the line of code that created the leaked thread. Stack traces sampled at detection time are not useful (leaked pool workers are parked in `LockSupport.park`); useful stacks must be captured at thread *creation*. JFR's built-in `jdk.ThreadStart` event provides this without bytecode instrumentation.

- When enabled, the component starts a JFR recording with the `jdk.ThreadStart` event at suite start (`testPlanExecutionStarted`) and stops/persists it at suite end (`testPlanExecutionFinished`, after the FINAL snapshot). The recording is written to `${report.output.dir}/thread-creations-<ts>.jfr`, where `<ts>` matches the raw report and HTML summary timestamps.
- Controlled by `thread.creation.tracking.enabled` (default: enabled) and `thread.creation.stack.depth` (default: 30). When `jdk.jfr` is unavailable (e.g., Java 8) or recording start fails, log a single WARN and proceed.
- Attribution parses the paired `.jfr` file and attaches each leaked thread's creation stack to its `DiscreteLeak`. Lookup is by thread name + thread ID; entries with no match are reported without a creation stack, not dropped.
- The HTML and text renderers display the creation stack alongside each thread leak when available. HTML uses a collapsible element so the report stays scannable.
- The attribution CLI accepts `--jfr <path>` to override automatic pairing for users who recorded JFR independently.
