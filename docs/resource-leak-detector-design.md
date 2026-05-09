# Resource Leak Detector - Design

> **Note**: For requirements, see [resource-leak-detector-requirements.md](./resource-leak-detector-requirements.md).

## Overview

The Resource Leak Detector monitors and reports resource leaks in unit tests, tracking network ports, threads, system properties, environment variables, memory usage, and DynamoDB Local tables. It uses JUnit Platform's `TestExecutionListener` API for global test lifecycle management and a globally-configured JUnit extension for per-class lifecycle start/end tracking. The extension is registered globally, so individual tests don't need to be aware of it or declare it explicitly.

All components are located in package `com.salesforce.test.extensions.resourceleak`.

### Packaging and Distribution

The Resource Leak Detector is maintained in its own standalone codebase, separate from any consuming project. It is published as a Maven artifact (e.g., `com.salesforce.test:resource-leak-detector:<version>`) and consumed as a `test`-scoped dependency.

Auto-registration with JUnit means consumers do not need to modify test code:

* `ResourceLeakMonitor` is discovered via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`
* `ResourceLeakMonitorTestLifecycleExtension` is discovered via `META-INF/services/org.junit.jupiter.api.extension.Extension` (with `junit.jupiter.extensions.autodetection.enabled=true` set in the consuming project's Surefire configuration)

Configuration is supplied by the consuming project via a properties file on the test classpath, with optional system property overrides (see [Configuration](#configuration)). The artifact contains no consumer-specific references in code or comments.

To fully disable the Resource Leak Detector with zero runtime overhead, the consuming project omits (or comments out) the Maven dependency. With the JAR off the test classpath, ServiceLoader discovers nothing, no classes from the library are loaded, and no hooks fire. This is the recommended way to disable the component; no runtime master enable/disable flag is provided.

All timestamps use ISO8601 format.

## Implementation Details

### Components

**ResourceLeakMonitor**

JUnit Platform `TestExecutionListener` that orchestrates the resource leak detection lifecycle.

* Registered globally via `META-INF/services/org.junit.platform.launcher.TestExecutionListener`
* In `testPlanExecutionStarted`, starts `ResourceMonitorThread`
* In `testPlanExecutionFinished`, stops `ResourceMonitorThread` which handles final resource detection, reporting, and build failure

**ResourceLeakMonitorTestLifecycleExtension**

JUnit extension that tracks test class lifecycle boundaries allowing for resource leaks to be attributed back to specific test classes later.

* Implements `BeforeAllCallback`, `AfterAllCallback` for per-class lifecycle tracking
* Registered globally via `META-INF/services/org.junit.jupiter.api.extension.Extension`
* On first initialization when the first test class starts, records baseline period end timestamp in `ResourceState` - baseline period ends when first test class begins
* In `beforeAll`/`afterAll` hooks, records test class start/end timestamps and stores them in the `ResourceState` singleton
* The JUnit service loader mechanism automatically applies the extension to all test classes without requiring `@ExtendWith` annotations or base class declarations

**ResourceState**

Singleton managing global state for resource tracking and test class lifecycle.

* Stores baseline resource usage before any tests run, test class lifecycle timestamps managed by `ResourceLeakMonitorTestLifecycleExtension`, and detected resources with their lifecycle timestamps managed by `ResourceMonitorThread`
* Resource state tracks two types of resources:
  * **Discrete resources**: Stored in `Map<ResourceId, DiscreteResourceInfo>` where `ResourceId` is resource-specific identifier and `DiscreteResourceInfo` contains first detected time, last detected time, nullable destroyed time, and baseline flag. Each discrete resource instance is tracked individually with its create/destroy lifecycle.
  * **Numeric resources**: Stored in a `List<NumericResourceMeasurement>` field that tracks numeric measurements on every `gatherResources` call with timestamps, allowing detection of when growth occurred and attribution to test classes. `ResourceMonitorThread` extracts numeric values from numeric resource monitors and stores them as measurements
* Test class lifecycle timestamps stored as `Map<TestClassName, TestClassLifecycle>` containing start and end times

**ResourceMonitor**

Base interface for resource monitoring. Each monitor declares its required runtime dependencies (if any). Before instantiating any monitor, `ResourceMonitorThread` checks that the monitor's required classes are present on the runtime classpath via `Class.forName(...)`. If a configured monitor's required dependency is missing, the library fails fast at startup with a clear error naming the monitor and the missing dependency — it does not silently disable the monitor.

Required runtime dependencies by monitor:

| Monitor | Required runtime dependency |
|---|---|
| `PortMonitor` | None (uses platform shell tools) |
| `ThreadMonitor` | None (uses JDK `Thread` APIs) |
| `SystemPropertyMonitor` | None (uses JDK `System` APIs) |
| `EnvironmentVariableMonitor` | None (uses JDK `System` APIs) |
| `MemoryMonitor` | None (uses JDK `Runtime` APIs) |
| `DynamoDbLocalTableMonitor` | `software.amazon.awssdk:dynamodb` (test-scope in consumer) |

The library declares dependencies that are required only for specific monitors as `<scope>provided</scope>`, so consumers who do not enable those monitors are not forced to include the dependency.

Two subtypes handle different resource categories:

* **DiscreteResourceMonitor**: Interface for discrete resources (present or not present) - tracked as individual instances with create/destroy lifecycle (ports, threads, system properties, environment variables, DynamoDB tables)
  * Defines `gatherResources` method that contains resource-type specific logic for gathering current resources from the system
  * Implementations:
    - **Network Ports** (`PortMonitor`): Detects bound ports opened by the current JVM process using platform-specific APIs. There is no standard JVM API to enumerate bound ports, so platform-specific approaches are used for efficiency.
      - RHEL 9 for CI/production: Executes `ss -lntp` filtered by current PID or reads `/proc/net/tcp` and `/proc/net/tcp6`
      - macOS for local development: Executes `lsof -p <PID> -i` or `netstat -anp <PID>`
    - **Threads** (`ThreadMonitor`): Enumerates threads using `Thread.getAllStackTraces()` and filters out TERMINATED threads
    - **System Properties** (`SystemPropertyMonitor`): Captures properties via `System.getProperties()`
    - **Environment Variables** (`EnvironmentVariableMonitor`): Captures environment via `System.getenv()`
    - **DynamoDB Local Tables** (`DynamoDbLocalTableMonitor`): Enumerates tables using `DynamoDbClient.listTables()`

* **NumericResourceMonitor**: Interface for numeric resources (measured values that change over time) - tracked as a single value with measurement history (memory heap usage)
  * Defines `measureResource` method that returns a single measurement with timestamp
  * Implementations:
    - **Memory (Heap Usage)** (`MemoryMonitor`): Calculates used heap as `totalMemory() - freeMemory()` using `Runtime.getRuntime()` and returns a `NumericResourceMeasurement` with the heap bytes value and current timestamp

**ResourceMonitorThread**

Background thread that orchestrates when and how to run monitoring, invokes all monitor implementations sequentially, manages state updates, and performs leak detection during finalization.

* Started and stopped by `ResourceLeakMonitor`
* Only runs monitor implementations for resource types specified in the configuration - resource types not in the configured list are ignored
* Periodically polls system resources at configurable interval for enabled monitors only
* Records the duration of each call to `gatherResources()` or `measureResource()` for each monitor and logs a warning if any single call exceeds the polling interval divided by the number of enabled monitors
* On startup, captures baseline resource snapshots:
  * For discrete resource monitors: Invokes `gatherResources` and stores the returned `Set<ResourceId>` in `ResourceState`'s `Map<ResourceId, DiscreteResourceInfo>` with current time as both first and last detected time and baseline flag set
  * For numeric resource monitors: Invokes `measureResource` and records the baseline measurement with its timestamp in `ResourceState`'s `List<NumericResourceMeasurement>` field
* During periodic polling:
  * For discrete resource monitors:
    * Invokes `gatherResources()` and receives `Set<ResourceId>`
    * Compares against existing resources in `ResourceState`'s map
    * For newly detected resources (not previously seen), records them with current time as both first and last detected time
    * For resources that exist in `ResourceState` but not in current detection, updates their destroyed time to current time if not already set
    * For resources that exist in `ResourceState` with non-null destroyed time but appear in current detection, clears destroyed time because resource was recreated, logs a warning, updates last detected time to current time, and retains original first detected time
    * For resources already in `ResourceState` with null destroyed time, updates last detected time to current time
  * For numeric resource monitors:
    * Invokes `measureResource` and records the measurement with its timestamp in `ResourceState`'s `List<NumericResourceMeasurement>` field
* On shutdown, runs the final resource detection by invoking each monitor one final time to update resource state with latest detection results, then triggers `ResourceLeakReporter` to generate and output the report

**ResourceLeakReporter**

Formats and outputs leak reports with source attribution.

* Invoked by `ResourceMonitorThread` during shutdown after final resource detection completes
* Accesses leak data from `ResourceState` singleton via its API which internally uses `DiscreteResourceInfo` and `List<NumericResourceMeasurement>`
* Only performs leak detection and reporting for resource types specified in the configuration - resource types not in the configured list are ignored
* Performs leak detection separately for discrete and numeric resources:
  * For discrete resources:
    * Compares resources in `ResourceState` with null `destroyed` time in their `DiscreteResourceInfo` still active against baseline resources - destroyed resources with non-null `destroyed` time are not reported as leaks
    * For threads: if non-TERMINATED threads are detected, sleeps for the grace period, then rechecks - threads still not TERMINATED after the grace period are considered leaked
    * Performs leak source attribution by matching resource timestamps against test class timestamps
  * For numeric resources:
    * Compares final measurements against baseline recorded during baseline period
    * For memory: reports as leak if final used heap exceeds baseline by more than a configurable threshold in MB. Uses the tracked heap usage measurements with timestamps to identify when significant growth occurred and attributes the leak to test classes that were running during the growth period
* Outputs formatted report to console/logs
* Build failure logic:
  * If leaks are detected for resource types in the build-failure list, calls `System.exit(1)` to fail the build

### Data Structures

#### Resource Implementation

- **DiscreteResourceMonitor**: Interface for discrete resource monitors. Defines `gatherResources` method returning `Set<ResourceId>`.

```kotlin
interface DiscreteResourceMonitor {
    fun gatherResources(): Set<ResourceId>
}
```

Implementations: `PortMonitor`, `ThreadMonitor`, `SystemPropertyMonitor`, `EnvironmentVariableMonitor`, `DynamoDbLocalTableMonitor`

- **NumericResourceMonitor**: Interface for numeric resource monitors. Defines `measureResource` method that returns a single `NumericResourceMeasurement`.

```kotlin
interface NumericResourceMonitor {
    fun measureResource(): NumericResourceMeasurement
}
```

Implementations: `MemoryMonitor`

- **ResourceId**: Sealed class used as key in `Map<ResourceId, DiscreteResourceInfo>` for discrete resources. Returned directly by `DiscreteResourceMonitor.gatherResources`. Acts as a type marker with no methods or properties - just restricts which types can be resource identifiers. Data class implementations automatically provide `equals`, `hashCode`, and `toString` methods required for Map key usage.

```kotlin
sealed class ResourceId {
    data class PortId(val port: Int) : ResourceId()
    data class ThreadId(val name: String, val id: Long) : ResourceId()
    data class PropertyId(val name: String) : ResourceId()
    data class EnvironmentVariableId(val name: String) : ResourceId()
    data class DynamoDbTableId(val name: String) : ResourceId()
}
```


#### Resource State Tracking

**Discrete Resource Tracking:**

- **DiscreteResourceInfo**: Internal data class used as value in `ResourceState`'s `Map<ResourceId, DiscreteResourceInfo>` for tracking discrete resources. Represents lifecycle timestamps for each resource instance. Resources with null `destroyed` time are considered still active. `last` detected time is updated each time the resource is detected, while `first` detected time remains unchanged after initial creation.

```kotlin
data class DiscreteResourceInfo(
    val first: Instant,                                  // First detected time
    val last: Instant,                                   // Last detected time
    val destroyed: Instant?,                             // Destroyed time; null if still active
    val isBaseline: Boolean,
    val creationStackTrace: List<StackTraceElement>?     // Captured at first detection (threads only); null otherwise
)
```

**Creation stack-trace capture (inspired by Netty's `ResourceLeakDetector`):**

For thread leaks specifically, the monitor captures a stack trace at first detection to point reviewers at the leak origin. The reporter prints the stack trace under the leaked thread when present.

* **Threads**: `ThreadMonitor` records `thread.stackTrace` (via `Thread.getAllStackTraces()`) at first detection. This is the executing thread's current stack — useful when the thread is parked/sleeping on whatever code path leaked it, less useful if it has progressed past the leak point.
* **Ports, system properties, environment variables, DynamoDB tables**: No reliable way to capture a creator stack trace from outside the JVM-level resource — these are detected by polling system state, not via instrumentation of the creation call. Field remains null. Adding instrumentation-based capture for these is out of scope.
* Stack trace capture has overhead, so it is performed once per resource at first non-baseline detection only — not on every poll.

**Numeric Resource Tracking:**

- **NumericResourceMeasurement**: Internal data class representing a single numeric resource measurement with timestamp. Used in `List<NumericResourceMeasurement>` to track numeric values over time. Measurements are recorded on each `gatherResources` call, allowing detection of when growth occurred. At leak detection, baseline and final measurements are compared, and the measurement history is used to identify when significant growth occurred and attribute it to test classes.

```kotlin
data class NumericResourceMeasurement(
    val value: Long,  // Numeric value (e.g., heap bytes for memory)
    val timestamp: Instant
)
```

**Test Class Lifecycle Tracking:**

- **TestClassLifecycle**: Internal data class used as value in `ResourceState`'s `Map<TestClassName, TestClassLifecycle>` for tracking test class execution boundaries used in leak attribution.

```kotlin
data class TestClassLifecycle(
    val start: Instant,    // Test class start time
    val end: Instant       // Test class end time
)
```

### Configuration

Configuration values are owned by the consuming project, not the library. They are read at startup from a properties file on the test classpath, with optional system property overrides.

**Primary source — properties file:**

The consuming project places `resource-leak-detector.properties` on its test classpath (e.g., `src/test/resources/resource-leak-detector.properties`). The library loads it via the thread context classloader. If the file is absent, built-in defaults are used.

```properties
monitored.resource.types=ports,threads,memory
thread.grace.period.seconds=10
polling.interval.milliseconds=5000
memory.growth.threshold.mb=1024
build.failure.resource.types=
```

**Override source — system properties:**

For each configuration key, the library checks for a system property of the form `resource.leak.detector.<key>` and, if set, uses it instead of the value from the properties file. This supports ad-hoc per-invocation overrides (CI flags, local debugging) without editing the file.

**Configuration keys:**

- `monitored.resource.types`: Comma-separated list of resource types to monitor. Valid values: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. Only resource types in this list are monitored and reported. If not specified, no resource types are monitored.
- `thread.grace.period.seconds`: Thread termination grace period in seconds, default: 10
- `polling.interval.milliseconds`: `ResourceMonitorThread` polling interval in milliseconds, default: 5000
- `memory.growth.threshold.mb`: Memory growth threshold in MB - memory leak reported if final heap after all tests exceeds baseline before any tests by more than this amount, default: 1024
- `build.failure.resource.types`: Comma-separated list of resource types that should cause the build to fail when leaks are detected. If not specified or empty, no resource types cause build failure and leaks are only reported.

When the Maven dependency is commented out to disable the component, the properties file remains on disk unused — no Surefire `pom.xml` cleanup needed.


## Appendix

### Leak Report Format

```
Resource Leak Detector Report
==============================

Network Port Leaks:
  - Port: 8080
    First Detected: 2024-01-15T10:30:45.123Z
    Candidate Test Classes:
      - com.salesforce.zos.TestClass1
        Started: 2024-01-15T10:30:40.000Z
        Ended: 2024-01-15T10:30:50.000Z

Thread Leaks:
  - Thread: test-thread-1 (ID: 12345)
    First Detected: 2024-01-15T10:31:00.456Z
    Stack Trace at First Detection:
      at java.lang.Thread.sleep(Native Method)
      at com.example.LeakyHelper.startBackgroundWorker(LeakyHelper.java:42)
      at com.salesforce.zos.TestClass2.setup(TestClass2.kt:18)
    Candidate Test Classes:
      - com.salesforce.zos.TestClass2
        Started: 2024-01-15T10:30:55.000Z
        Ended: 2024-01-15T10:31:05.000Z

System Property Leaks:
  - Property: test.property.name
    First Detected: 2024-01-15T10:31:10.789Z
    Candidate Test Classes:
      - com.salesforce.zos.TestClass3
        Started: 2024-01-15T10:31:05.000Z
        Ended: 2024-01-15T10:31:15.000Z

Environment Variable Leaks:
  - Variable: TEST_ENV_VAR
    First Detected: 2024-01-15T10:31:20.012Z
    Candidate Test Classes:
      - com.salesforce.zos.TestClass4
        Started: 2024-01-15T10:31:15.000Z
        Ended: 2024-01-15T10:31:25.000Z

Memory Leaks:
  - Baseline: 256 MB
  - Final: 320 MB
  - Increase: 64 MB
  - First Detected: 2024-01-15T10:31:30.123Z
  - Candidate Test Classes:
    - com.salesforce.zos.TestClass5
      Started: 2024-01-15T10:31:25.000Z
      Ended: 2024-01-15T10:31:35.000Z

DynamoDB Local Table Leaks:
  - Table: test-table-leaked
    First Detected: 2024-01-15T10:31:40.456Z
    Candidate Test Classes:
      - com.salesforce.zos.TestClass6
        Started: 2024-01-15T10:31:35.000Z
        Ended: 2024-01-15T10:31:45.000Z
```

### FAQ

**Why use both TestExecutionListener and Extension?**

**TestExecutionListener** - JUnit Platform API:
- **Scope**: Platform-level, sees entire test plan across all test engines
- **Hooks**: `testPlanExecutionStarted()`, `testPlanExecutionFinished()`, `executionStarted()`, `executionFinished()` for individual tests
- **Use case**: Global lifecycle management before all tests and after all tests

**Extension** - JUnit Jupiter API:
- **Scope**: Jupiter engine-level, operates within test class/method lifecycle
- **Hooks**: `BeforeAllCallback`, `AfterAllCallback`, `BeforeEachCallback`, `AfterEachCallback`
- **Use case**: Per-class lifecycle tracking with exact timing for setup/teardown boundaries

We use both because:
1. `TestExecutionListener` provides global hooks before all tests and after all tests that extensions don't have
2. Extensions provide per-class hooks `BeforeAllCallback` and `AfterAllCallback` with exact timing that `TestExecutionListener` cannot provide
3. Together they provide complete lifecycle coverage: global initialization/finalization + per-class tracking

### Alternative Approach - TestExecutionListener-only

An alternative approach using only `TestExecutionListener` without extensions was considered but not chosen:

**Approach 2: TestExecutionListener-based with Inferred Boundaries**
- Track test class transitions by monitoring test method execution in `TestExecutionListener`
- In `testPlanExecutionStarted`, build a map of all test classes and their methods from the `TestPlan`
- Track current test class and "last seen method per class" as methods execute
- When `executionStarted` sees a new test class (different from previous), infer boundaries:
  - Previous test class ended: timestamp when transition occurs - its teardown completed between last method finish and this transition
  - New test class started: timestamp when transition occurs - its setup completed between test plan start and this transition
- When `executionFinished` completes what appears to be the last method of a class (based on TestPlan), mark that class as ended

This approach was not chosen because the extension-based approach provides more accurate boundaries, which is a proven pattern for per-class lifecycle tracking in JUnit Jupiter.
