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

## Requirements

### 1. Lifecycle Management

#### 1.1 Component Initialization
- **REQ-1.1.1**: The component MUST start before any unit tests run
- **REQ-1.1.2**: Resource leaks MUST be detectable when running `make test`

#### 1.2 Leak Reporting
- **REQ-1.2.1**: Leak reporting MUST occur after all tests have completed and after the thread termination grace period
- **REQ-1.2.2**: Analysis MUST compare current resource state against the baseline established before tests started, excluding baseline resources from leak detection
- **REQ-1.2.3**: A test class MUST be considered started before or exactly when the test class setup begins and completed only when its tear down is fully complete
- **REQ-1.2.4**: Leak reports MUST be formatted as a list of leaks for monitored resource types, where each leak entry includes:
  - Resource details:
    - For network ports: port number
    - For threads: thread name and thread ID
    - For system properties: property name
    - For environment variables: variable name
    - For memory: baseline used heap, final used heap, and increase amount
    - For DynamoDB Local tables: table name
  - Timestamp when the resource was first detected by the component, formatted in ISO8601 format
  - List of leak source candidate test classes: if a resource appeared between when a test class started and when it ended, that test class MUST be reported as a leak source candidate
  - For each leak source candidate test class: timestamp when the test class started and timestamp when the test class ended, both formatted in ISO8601 format
- **REQ-1.2.5**: A thread MUST only be considered leaked if it does not reach TERMINATED state within the configurable grace period (default: 10 seconds) after all tests have completed
- **REQ-1.2.6**: TERMINATED threads MUST NOT be considered as leaked
- **REQ-1.2.7**: Memory MUST be considered leaked if the final used heap exceeds the baseline used heap
- **REQ-1.2.8**: A DynamoDB Local table MUST be considered leaked if it exists in the final table list but not in the baseline table list

### 2. Leak Detection and Analysis

#### 2.1 Resource Selection
- **REQ-2.1.1**: The component MUST support a configuration parameter that specifies a list of resource types to monitor
- **REQ-2.1.2**: The component MUST only monitor and report leaks for resource types specified in the configuration; resource types not in the list MUST be ignored
- **REQ-2.1.3**: When a configured monitor requires a runtime dependency that is not on the consuming project's classpath, the component MUST fail fast at startup with a clear error message naming the missing dependency and the monitor that requires it. The component MUST NOT silently disable the monitor.

#### 2.2 Build Failure on Leak Detection
- **REQ-2.2.1**: Build failure on leak detection MUST be controlled by a configurable feature flag (default: disabled) that acts as a master switch
- **REQ-2.2.2**: The component MUST support a separate configuration parameter that specifies a list of resource types that should cause the build to fail when leaks are detected
- **REQ-2.2.3**: When the feature flag is enabled and leaks are detected for resource types in the build-failure list, the component MUST cause the build to fail
- **REQ-2.2.4**: When the feature flag is disabled, or when leaks are detected for resource types not in the build-failure list, the component MUST only report leaks without failing the build

### 3. Non-functional Requirements

- **REQ-3.1**: The extension MUST be maintained in its own standalone codebase, separate from this project (zero-object-service), and consumed as an external dependency
- **REQ-3.2**: Component code MUST be written in Kotlin
- **REQ-3.3**: The component MUST be decoupled from any consuming project's code, including avoiding consumer-specific references in code or comments
- **REQ-3.4**: The component MUST be transparent to unit tests; unit tests MUST NOT require any modifications or accommodations to be monitored
- **REQ-3.5**: The component MUST support running on RHEL 9 (Linux) for CI/production environments and macOS for local development environments
- **REQ-3.6**: The component MUST record the duration of each call to `gatherResources()` for each monitor, and MUST log a warning if any single call exceeds the polling interval divided by the number of monitors
- **REQ-3.7**: The component MUST use import statements rather than fully-qualified class names where possible
- **REQ-3.8**: The component MUST minimize transitive dependencies. Runtime dependencies SHOULD be limited to JUnit Platform/Jupiter APIs and the platform-specific APIs required for resource detection (e.g., AWS SDK for DynamoDB Local). Convenience libraries that can be reasonably replaced with standard JDK constructs MUST NOT be added.
