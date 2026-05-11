# junit-leak-detector

JUnit 5 extension that detects resource leaks in unit tests across multiple resource types: network ports, threads, system properties, environment variables, JVM heap memory, and DynamoDB Local tables.

Auto-registers via `ServiceLoader` — consumers add the JAR as a `test`-scoped dependency, no `@ExtendWith` required. (For per-class lifecycle hooks, set `junit.jupiter.extensions.autodetection.enabled=true` in Surefire — see Consume below.)

For requirements and design, see [docs/requirements.md](docs/requirements.md) and [docs/design.md](docs/design.md).

## Consume (inline mode)

Add as a `test`-scoped dependency in your project's `pom.xml`:

```xml
<dependency>
    <groupId>com.michaelsgroi.test</groupId>
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

For reliable detection of cross-class leaks, ensure `forkCount=1` and `reuseForks=true` in your Surefire config (these are the defaults). Otherwise multiple JVMs service the suite and sticky cross-class leaks become invisible — the detector emits a runtime WARN if it sees prior-fork markers from a sibling JVM.

After `mvn test`, two files land in the configured output directory (default: the consuming module's working directory; configurable via `report.output.dir`):

- `raw-report-<ISO-timestamp>.json` — JSON Lines machine-readable record, the contract between the library and the attribution module
- `leak-summary-<ISO-timestamp>.html` — human-readable summary, mirrors what's logged

Both files share the same ISO-8601-seconds timestamp suffix so prior runs aren't overwritten. The library does not auto-open the HTML — running tests shouldn't open browser tabs as a side effect.

## Standalone attribution CLI

If you have a saved raw report and want to re-run attribution:

```
attribution/bin/junit-leak-detector-attribution \
    raw-report.json [--memory-threshold-mb N]
```

Writes `leak-summary-<ISO-timestamp>.html` next to the input raw report and opens it in the default browser. Set `JUNIT_LEAK_DETECTOR_NO_OPEN=1` to suppress auto-opening.

## Configuration

Library configuration is layered:

1. **Properties file** — `resource-leak-detector.properties` on the test classpath (e.g., `src/test/resources/resource-leak-detector.properties`). The long-lived base config. Keys go in unprefixed: `monitored.resource.types=ports,threads`.
2. **System properties** — `-Dresource.leak.detector.X=Y` on the CLI, or `<systemPropertyVariables>` in your Surefire config. These **override** the file value when set. Useful for per-invocation tweaks or per-profile scenario tests.
3. **Built-in defaults** — apply when neither the file nor a system property is set.

| Property | What it controls | Default |
|---|---|---|
| `monitored.resource.types` | Comma-separated list of monitors to enable. Valid values: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. Empty = nothing monitored. | (empty) |
| `memory.growth.threshold.mb` | Per-class heap growth (AFTER_ALL − BEFORE_ALL, in MB) before flagging a memory leak | `50` |
| `build.failure.resource.types` | Comma-separated list of resource types whose leaks should fail the build (calls `System.exit(1)`). Empty = report only. | (empty) |
| `snapshot.granularity` | `class` snapshots at `BeforeAll`/`AfterAll` only. `test` also snapshots at `BeforeEach`/`AfterEach` for fine-grained debugging at ~Nx more snapshot operations. | `class` |
| `report.output.dir` | Directory where `raw-report-<ts>.json` and `leak-summary-<ts>.html` are written. | the JVM's working directory |
| `preclass.settle.enabled` | Optional pre-class settle wait. Before each `BeforeAllCallback`, polls until threads/ports introduced by the previous class have released, sharpening attribution. | `false` |
| `preclass.settle.max.seconds` | Max time the settle wait will block. | `10` |
| `preclass.settle.poll.interval.seconds` | Poll interval during the settle wait. | `1` |

Each property is read both unprefixed from the file and as `resource.leak.detector.<key>` from system properties.

**Disabling the detector entirely:** comment out the dependency in your `pom.xml`. With the JAR off the classpath, no library code is loaded — zero overhead. There is intentionally no runtime master enable/disable flag.

**Optional monitor dependencies:** `ddbtables` requires `software.amazon.awssdk:dynamodb` on the test classpath. If `ddbtables` is enabled but the AWS SDK is missing, the detector fails fast at startup with a clear error naming the missing dependency. Other monitors use only JDK APIs.

**Reflection requirement for env-var modification:** `EnvironmentVariableMonitor` reads `System.getenv()` (no reflection needed for *detection*). However, tests that themselves *modify* env vars on JDK 17+ require `--add-opens java.base/java.lang=ALL-UNNAMED` in your Surefire `<argLine>`.

## Contributing

### Project Layout

Multi-module Maven build. The root `pom.xml` is `packaging=pom`.

```
junit-leak-detector/                       ← parent (packaging=pom)
├── library/                               ← detector library (artifactId=junit-leak-detector)
├── attribution/                           ← attribution module: raw-report → final report
│   └── bin/junit-leak-detector-attribution
└── integration-tests/                     ← end-to-end consumer test apps (not published)
    ├── basic/                             ← 5 monitors (no DDB)
    ├── ddb/                               ← DynamoDB monitor only
    └── scenarios/                         ← failsafe-driven scenario tests over basic + ddb
```

Publishable artifacts: `junit-leak-detector` (library) and `junit-leak-detector-attribution`. The integration-tests modules are tests and are not deployed.

### Build

From the project root:

```
mvn install
```

`mvn install` is the supported full-build invocation. It runs unit tests under Surefire, then runs the integration scenarios under Failsafe (which shells out to `mvn test` against the basic and ddb subject modules with various profiles). `mvn verify` alone is insufficient because the scenarios module depends on sibling jars being installed in the local Maven repo.

The library jar is published to the local Maven repo as `com.michaelsgroi.test:junit-leak-detector:0.1.0-SNAPSHOT`.

### Static checks

Spotless (ktlint formatting) and PMD CPD (copy-paste detection) are bound to the `process-sources` phase, so they run automatically during `mvn install` / `mvn test`. To run them on demand:

```
make checks               # spotless:check + pmd:cpd-check
mvn spotless:apply        # auto-fix formatting
```
