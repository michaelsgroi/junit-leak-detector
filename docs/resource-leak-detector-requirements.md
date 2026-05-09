# Resource Leak Detector - Requirements

> **Note**: For design and implementation details, see [resource-leak-detector-design.md](./resource-leak-detector-design.md).

## Purpose

The Resource Leak Detector (the component) identifies unit tests that are leaking resources. It monitors and detects leaks in the following resource types:

- **Network ports** - Opened network ports that are not properly closed
- **Threads** - Threads that remain active well after test completion
- **System properties** - System properties that are set but not cleaned up
- **System environment variables** - Environment variables that are modified but not restored
- **Memory (heap usage)** - Memory that remains allocated after test completion
- **DynamoDB Local tables** - DynamoDB Local tables that are created but not deleted

## Architecture

The component is split into three independent pieces. Each can be invoked, replaced, or re-run independently of the others.

- **C1 — Test runner + raw report.** Runs the unit-test suite, captures resource lifecycle data, emits a structured raw report. Knows nothing about attribution.
- **C2 — Optional second run.** Runs the suite a second time using a different test ordering (see REQ-2.4). Emits its own raw report.
- **C3 — Attribution.** Independent. Takes one or two raw reports as input and produces attribution output (the candidate set per leak). Can be re-run without re-running tests.

An optional AI-assisted "skill" layer may sit on top of C3, applying name heuristics and source grep to narrow candidate sets to a single most-likely owner. The skill layer is out of scope for this component but the C3 output format MUST support it cleanly.

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
- **REQ-2.3.1**: Attribution (C3) MUST be implemented as a separate component that consumes one or more raw reports produced by C1/C2 and produces the final report. Attribution MUST be re-runnable without re-running tests.
- **REQ-2.3.2**: The raw report format MUST contain enough information (per-resource appearance times, per-test-class lifecycle intervals) to compute candidate sets without re-instrumenting the suite.

#### 2.4 Differential ordering (optional second run)
- **REQ-2.4.1**: The component MUST support an optional "double run" mode. When enabled, the suite is executed twice with different test orderings; the attribution component intersects the candidate sets across runs to produce sharper attribution.
- **REQ-2.4.2**: Different orderings MUST be produced via Surefire's built-in `runOrder` configuration, not via custom shuffling code. The first run uses the project's normal `runOrder` (typically `alphabetical`). The second run sets `-Dsurefire.runOrder=random` with `-Dsurefire.runOrderRandomSeed=<seed>`.
- **REQ-2.4.3**: The seed used for the second run MUST be recorded in the raw report so the run is reproducible.
- **REQ-2.4.4**: Default mode is single-run (one execution, wider candidate sets). Double-run mode is opt-in.

### 3. Pre-flight Configuration Check

- **REQ-3.1**: Before running tests, the component MUST inspect the project's build configuration (`pom.xml` for Maven; equivalents for other supported build tools) to verify isolation settings required for reliable detection.
- **REQ-3.2**: Required isolation settings: `forkCount=1` and `reuseForks=true`. With `reuseForks=false`, sticky cross-class leaks are invisible because each fork starts clean.
- **REQ-3.3**: If the project's settings would prevent reliable detection, the component MUST refuse to run and MUST emit a clear error message telling the user exactly how to invoke the suite with the correct settings.
- **REQ-3.4**: The hard prerequisites in REQ-3.2 MUST be documented in user-facing documentation.

### 4. Leak Detection and Analysis

#### 4.1 Resource Selection
- **REQ-4.1.1**: The component MUST support a configuration parameter that specifies a list of resource types to monitor.
- **REQ-4.1.2**: The component MUST only monitor and report leaks for resource types specified in the configuration; resource types not in the list MUST be ignored.
- **REQ-4.1.3**: When a configured monitor requires a runtime dependency that is not on the consuming project's classpath, the component MUST fail fast at startup with a clear error message naming the missing dependency and the monitor that requires it. The component MUST NOT silently disable the monitor.

#### 4.2 Build Failure on Leak Detection
- **REQ-4.2.1**: Build failure on leak detection MUST be controlled by a configurable feature flag (default: disabled) that acts as a master switch.
- **REQ-4.2.2**: The component MUST support a separate configuration parameter that specifies a list of resource types that should cause the build to fail when leaks are detected.
- **REQ-4.2.3**: When the feature flag is enabled and leaks are detected for resource types in the build-failure list, the component MUST cause the build to fail.
- **REQ-4.2.4**: When the feature flag is disabled, or when leaks are detected for resource types not in the build-failure list, the component MUST only report leaks without failing the build.

### 5. Non-functional Requirements

- **REQ-5.1**: The extension MUST be maintained in its own standalone codebase, separate from any consumer project, and consumed as an external dependency.
- **REQ-5.2**: Component code MUST be written in Kotlin.
- **REQ-5.3**: The component MUST be decoupled from any consuming project's code, including avoiding consumer-specific references in code or comments.
- **REQ-5.4**: The component MUST be transparent to unit tests; unit tests MUST NOT require any modifications or accommodations to be monitored.
- **REQ-5.5**: The component MUST support running on RHEL 9 (Linux) for CI/production environments and macOS for local development environments.
- **REQ-5.6**: The component MUST use import statements rather than fully-qualified class names where possible.
- **REQ-5.7**: The component MUST minimize transitive dependencies. Runtime dependencies SHOULD be limited to JUnit Platform/Jupiter APIs and the platform-specific APIs required for resource detection (e.g., AWS SDK for DynamoDB Local). Convenience libraries that can be reasonably replaced with standard JDK constructs MUST NOT be added.

## Backlog

Items deferred but documented for future work.

### B1. Stack-trace capture at allocation time
Capture an allocation stack trace at the moment a resource is created (port open, table create, thread spawn, property set). Implementation: bytecode instrumentation via a Java agent using ASM or ByteBuddy, plus per-resource-type weaving (intercept `ServerSocket` constructors, `Thread.start()`, `System.setProperty`, DDB Local table-create calls, etc.).

Why valuable: lets us go directly from leak → exact line, bypassing the candidate-set step entirely. Resolves the lazy-allocation case (e.g., a Jetty server started in test A whose `qtp*` expansion threads spawn under load during test B) cleanly.

Not in v1: ship without it; rely on lifecycle-boundary snapshots (REQ-1.2.1) and differential ordering (REQ-2.4) to keep candidate sets small. Revisit when we see how tight the boundary-based sets actually are in practice.
