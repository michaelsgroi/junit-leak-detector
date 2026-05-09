# junit-leak-detector

JUnit 5 extension that detects resource leaks in unit tests across multiple resource types: network ports, threads, system properties, environment variables, JVM heap memory, and DynamoDB Local tables.

Auto-registers via `ServiceLoader` — consumers add the JAR as a `test`-scoped dependency, no `@ExtendWith` required.

## Project Layout

This is a multi-module Maven project. The library lives at the root; integration test consumers live under `integration-tests/`.

```
junit-leak-detector/                       ← root: library (artifactId=junit-leak-detector)
├── pom.xml
├── src/{main,test}/{kotlin,java}/         ← library source
└── integration-tests/                     ← end-to-end consumer test apps (not published)
    ├── basic/                             ← 5 monitors (no DDB)
    └── ddb/                               ← DynamoDB monitor only
```

## Build

From the project root:

```
mvn clean install
```

Builds the library, runs library unit tests, runs both integration test modules. The library JAR is published to the local Maven repo as `com.salesforce.test:junit-leak-detector:0.1.0-SNAPSHOT`.

## Consume

Add as a `test`-scoped dependency in your project's `pom.xml`:

```xml
<dependency>
    <groupId>com.salesforce.test</groupId>
    <artifactId>junit-leak-detector</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Enable Jupiter extension auto-detection (required so the per-class lifecycle extension registers without `@ExtendWith`):

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <junit.jupiter.extensions.autodetection.enabled>true</junit.jupiter.extensions.autodetection.enabled>
            <resource.leak.detector.monitored.resource.types>ports,threads,systemprops,envvars,memory</resource.leak.detector.monitored.resource.types>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

The JUnit Platform listener (which orchestrates the whole thing) auto-registers without any flag.

## Configuration

Configuration values are layered:

1. **Properties file** — `resource-leak-detector.properties` on the test classpath (e.g., `src/test/resources/resource-leak-detector.properties`). This is the long-lived base config. Keys go in unprefixed: `monitored.resource.types=ports,threads`.
2. **System properties** — `-Dresource.leak.detector.X=Y` on the CLI, or `<systemPropertyVariables>` in your Surefire config. These **override** the file value when set. Useful for per-invocation tweaks or per-profile scenario tests.
3. **Built-in defaults** — apply when neither the file nor a system property is set.

See [`integration-tests/README.md`](integration-tests/README.md) for an example of how to use both layers (file = base, system properties = profile-specific overrides).

| Property | What it controls | Default |
|---|---|---|
| `resource.leak.detector.monitored.resource.types` | Comma-separated list of monitors to enable. Valid values: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. Empty = nothing monitored. | (empty) |
| `resource.leak.detector.polling.interval.milliseconds` | How often the worker thread polls live state | `5000` |
| `resource.leak.detector.thread.grace.period.seconds` | How long the reporter waits for non-TERMINATED threads to finish before flagging | `10` |
| `resource.leak.detector.memory.growth.threshold.mb` | Minimum heap growth (final − baseline, in MB) before flagging a memory leak | `1024` |
| `resource.leak.detector.build.failure.resource.types` | Comma-separated list of resource types whose leaks should fail the build (calls `System.exit(1)`). Empty = report only. | (empty) |

**Disabling the detector entirely:** comment out the dependency in your `pom.xml`. With the JAR off the classpath, no library code is loaded — zero overhead. There is intentionally no runtime master enable/disable flag.

**Optional monitor dependencies:** `ddbtables` requires `software.amazon.awssdk:dynamodb` on the test classpath. If `ddbtables` is enabled but the AWS SDK is missing, the detector fails fast at startup with a clear error naming the missing dependency. Other monitors use only JDK APIs.

**Reflection requirement for env-var monitor:** `EnvironmentVariableMonitor` reads `System.getenv()` (no reflection needed for *detection*). However, tests that themselves *modify* env vars on JDK 17+ require `--add-opens java.base/java.lang=ALL-UNNAMED` in your Surefire `<argLine>`.
