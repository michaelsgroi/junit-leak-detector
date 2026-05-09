# Resource Leak Detector - Development Plan

> **Note**: For requirements and design details, see [resource-leak-detector-requirements.md](./resource-leak-detector-requirements.md) and [resource-leak-detector-design.md](./resource-leak-detector-design.md).

## Overview

This document outlines the incremental development plan for implementing the Resource Leak Detector. Each step must be completed (feature complete with unit test coverage) before proceeding to the next step.

All components are located in package `com.salesforce.test.extensions.resourceleak`.

Unless specified otherwise, each step should account for all elements in the design document for the components being implemented in that step.

**Branch**: `msgroi.w20908009.resource-leak-detector`

**Push Policy**: Push after each step, but ONLY AFTER explicit approval to do so.

- [x] **1: Minimal ResourceLeakMonitor Implementation** - Prove that `ResourceLeakMonitor` starts before all tests and ends after all tests with lifecycle hooks only.

- [x] **2: ResourceLeakMonitorTestLifecycleExtension with Timestamp Capture** - Prove that `ResourceLeakMonitorTestLifecycleExtension` can capture test start and end timestamps, creating `ResourceState` with test class lifecycle tracking.

- [x] **3: ResourceLeakReporter with Basic Test Class Reporting** - Get `ResourceLeakReporter` that reports how many test classes ran and how long each took, called by `ResourceLeakMonitor` after all tests complete.

- [x] **4: Build Failure Proof of Concept** - Prove that `ResourceLeakMonitor` can fail the test run and exit via system property check.

- [x] **5: ResourceState, ResourceMonitor, SystemPropertyMonitor, and ResourceMonitorThread** - Define core infrastructure (`ResourceMonitor` interface hierarchy, `ResourceId` sealed class) and implement system properties monitoring with leak detection and reporting via `SystemPropertyMonitor`, `ResourceMonitorThread`, and `ResourceLeakReporter` updates.

- [x] **6: Build Failure for SystemPropertyMonitor** - Add support for failing the build when system property leaks are detected via `resource.leak.detector.build.failure.resource.types` configuration.

- [x] **7: MemoryMonitor Support** - Add memory heap usage monitoring with `MemoryMonitor`, `NumericResourceMonitor` interface, numeric resource tracking in `ResourceState`, and memory leak detection in `ResourceLeakReporter` with threshold comparison and build failure support.

- [x] **8: EnvironmentVariableMonitor Support** - Add environment variable monitoring with `EnvironmentVariableMonitor`, `ResourceId.EnvironmentVariableId`, and environment variable leak reporting in `ResourceLeakReporter`.

- [x] **9: ThreadMonitor Support** - Add thread monitoring with `ThreadMonitor`, `ResourceId.ThreadId`, and thread leak detection in `ResourceLeakReporter` including grace period handling.

- [x] **10: DynamoDbLocalTableMonitor Support** - Add DynamoDB Local table monitoring with `DynamoDbLocalTableMonitor`, `ResourceId.DynamoDbTableId`, and DynamoDB table leak reporting in `ResourceLeakReporter`.

- [x] **11: PortMonitor Support** - Add network port monitoring with `PortMonitor` (platform-specific implementation for RHEL 9 and macOS), `ResourceId.PortId`, and port leak reporting in `ResourceLeakReporter`.

- [x] **12: Bootstrap junit-leak-detector Maven Project** - In `~/d/d/github/junit-leak-detector`, add `pom.xml` with `<packaging>jar</packaging>` (Kotlin + JUnit Platform/Jupiter, no extra runtime dependencies beyond what is required for resource detection), `src/main/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener` listing `ResourceLeakMonitor`, `src/main/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` listing `ResourceLeakMonitorTestLifecycleExtension`, `.gitignore`, and a minimal `README.md`. Run `mvn package` to produce the JAR (`target/junit-leak-detector-<version>.jar`) and verify it contains compiled classes plus the `META-INF/services/` files. Run `mvn install` to publish to the local Maven repo so the testapp can consume it. Library code (already copied into `src/main/kotlin/`) reads configuration via `System.getProperty("resource.leak.detector.X")` directly for now — properties-file loader is deferred to a later step.

- [x] **13: Fail-Fast Classpath Check for Optional Monitor Dependencies** - In `ResourceMonitorThread` (or wherever active monitors are assembled from configuration), guard each monitor's instantiation with `Class.forName(...)` for that monitor's required runtime dependency. If a configured monitor's required class is absent, throw `IllegalStateException` at startup with a clear message naming the monitor and the missing Maven coordinate. Currently applies only to `DynamoDbLocalTableMonitor` (requires `software.amazon.awssdk:dynamodb`); other monitors use only JDK APIs. Add unit test verifying the failure mode.

- [x] **14: Bootstrap junit-leak-detector-app Maven Project** - In `~/d/d/github/junit-leak-detector-app`, add `pom.xml` depending on `junit-leak-detector` (test-scoped) only — do NOT add the AWS SDK or DynamoDB Local. This testapp validates the 5 monitors that have no optional runtime dependency (ports, threads, system properties, environment variables, memory). Remove the `DynamoDbTableLeakingTest` skeleton from `src/test/kotlin/` since DDB detection is verified separately in `junit-leak-detector-ddbapp`. Add `.gitignore` and `README.md`. Configure Surefire to set `junit.jupiter.extensions.autodetection.enabled=true` and `resource.leak.detector.monitored.resource.types=ports,threads,systemprops,envvars,memory`.

- [x] **15: End-to-End Leak Detection Verification (5 monitors)** - Run `mvn test` in `junit-leak-detector-app`. Verify that the leak report names a leak for each of the 5 monitored resource types (port, thread, system property, environment variable, memory) — five leaks total, one per leaking test. Verify that the AWS SDK is NOT pulled in transitively (run `mvn dependency:tree` and confirm `software.amazon.awssdk` is absent). Capture the report output as a sample in the testapp's `README.md`.

- [x] **16: Bootstrap junit-leak-detector-ddbapp Maven Project** - Create `~/d/d/github/junit-leak-detector-ddbapp` as a separate Maven project. Add `pom.xml` depending on `junit-leak-detector` (test-scoped), `software.amazon.awssdk:dynamodb` (test-scoped — required because `DynamoDbLocalTableMonitor` is enabled), and `com.amazonaws:DynamoDBLocal` (test-scoped, embedded in-JVM). Configure Surefire to set `junit.jupiter.extensions.autodetection.enabled=true` and `resource.leak.detector.monitored.resource.types=ddbtables`. Add a single `DynamoDbTableLeakingTest` that starts an embedded DynamoDB Local instance, creates a table, and exits without deleting it.

- [x] **17: End-to-End DynamoDB Leak Detection Verification** - Run `mvn test` in `junit-leak-detector-ddbapp`. Verify the leak report names the leaked table. Then verify the fail-fast behavior: temporarily comment out the `software.amazon.awssdk:dynamodb` dependency and confirm `mvn test` fails at startup with a clear error naming the missing dependency (per REQ-2.1.3). Restore the dependency and capture the success-case report output in the testapp's `README.md`.

- [x] **18: Polling Interval Configuration and Performance Monitoring** - Add configurable polling interval and performance monitoring to `ResourceMonitorThread` with duration recording and warning logging.

- [~] **19: Baseline Period End Timestamp** *(deferred)* - The end timestamp is captured in `ResourceState.baselinePeriodEndTimestamp` (set by `ResourceLeakMonitorTestLifecycleExtension` on first test class start), but is intentionally not consumed by `ResourceLeakReporter` or used to gate `updateDiscreteResources`. Consuming it requires careful timing-sensitive design (worker-thread vs main-thread races during test plan startup); revisit if attribution accuracy proves insufficient in practice.

- [x] **20: Consolidate into Multi-Module Maven Project** - Convert the three separate repos at `~/d/d/github/junit-leak-detector`, `~/d/d/github/junit-leak-detector-app`, and `~/d/d/github/junit-leak-detector-ddbapp` into a single multi-module Maven project under `~/d/d/github/junit-leak-detector`. Layout:
  ```
  junit-leak-detector/                       ← root parent (packaging=pom)
  ├── pom.xml                                ← parent pom; <modules> lists library + integration-tests
  ├── README.md
  ├── docs/
  ├── junit-leak-detector/                   ← library module (artifactId=junit-leak-detector)
  │   ├── pom.xml                            ← <parent> → root
  │   └── src/{main,test}/{kotlin,java}/
  └── integration-tests/
      ├── pom.xml                            ← intermediate aggregator (packaging=pom)
      ├── basic/                             ← was junit-leak-detector-app (artifactId=junit-leak-detector-it-basic)
      │   ├── pom.xml
      │   ├── Makefile
      │   └── src/test/kotlin/
      └── ddb/                               ← was junit-leak-detector-ddbapp (artifactId=junit-leak-detector-it-ddb)
          ├── pom.xml
          ├── Makefile
          └── src/test/kotlin/
  ```
  Use `<version>${project.version}</version>` in IT modules' library dependency (not hard-coded `0.1.0-SNAPSHOT`). Top-level `Makefile` delegates to `mvn test` so a single root `mvn test` builds the lib and runs both ITs. Only the library module is publishable; IT modules stay test-scope and are not deployed.

- [x] **21: Properties-File Configuration Loader** - Replace direct `System.getProperty` reads with a configuration loader that reads `resource-leak-detector.properties` from the test classpath and applies system property overrides (key form `resource.leak.detector.<key>`). Defaults applied when file absent or key missing. After implementing, update the testapp to use a `resource-leak-detector.properties` file on its test classpath (instead of Surefire `<systemPropertyVariables>`) and re-verify end-to-end detection.

- [~] **22: Thread Creation Stack-Trace Capture** *(deferred)* - Capture the executing thread's stack trace at first non-baseline detection in `ThreadMonitor` and store it in `DiscreteResourceInfo.creationStackTrace`. Touches multiple framework boundaries (data class, monitor interface, state, reporter) and ripples through every `DiscreteResourceInfo` construction site including tests. Skipped for now; revisit if leak attribution proves insufficient without it.

- [x] **23: Control Tests in Testapp** - Add non-leaking "control" tests to the basic IT module alongside the leaking tests, to verify the detector does not flag false positives. Use `uk.org.webcompere:system-stubs-jupiter` in control tests to cleanly isolate system property and environment variable changes (testapp dependency only — must NOT be a transitive dependency of the library). Verify the leak report still names exactly the intentional leaks and does not flag the control tests.

- [x] **24: Build-Failure Verification in Testapp** - Configure the testapp's Surefire/properties to enable build failure for one resource type (e.g., `build.failure.resource.types=ports`). Verify that `mvn test` exits non-zero when the port-leaking test runs. Then verify that with that configuration cleared, `mvn test` exits zero despite the same leaks being detected and reported.

- [x] **25: Push to GitHub** - Create a public repository for the consolidated multi-module project, push initial commits, and verify it builds cleanly from a fresh clone (single `git clone` + `mvn test` runs library tests AND both integration test modules).

- [x] **26: Apply Leak Detector to zero-object-service** - Clone zero-object-service into `~/d/d/zero-object-service-leaks` (separate from any existing checkout) via `git clone git@git.soma.salesforce.com:salesforce-zero/zero-object-service.git zero-object-service-leaks`. Wire the `junit-leak-detector` library into the `zos` subfolder ONLY (not the parent or other modules), and only into Surefire (unit tests), not into Failsafe / integration test plugins. Specifically: add `junit-leak-detector` as a `test`-scoped dependency in `zos/pom.xml`; add `<systemPropertyVariables>` to the existing Surefire plugin config setting `junit.jupiter.extensions.autodetection.enabled=true`; place a `resource-leak-detector.properties` file under `zos/src/test/resources/` configuring which monitors are enabled (start with all 6, report-only — no `build.failure.resource.types`). Run `mvn test -pl zos` to surface real leaks in the existing test suite, capture the leak report to a file in the cloned repo for review.

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
