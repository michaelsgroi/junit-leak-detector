# junit-leak-detector

JUnit 5 listener that detects resource leaks in unit tests across multiple resource types: network ports, threads, system properties, environment variables, JVM heap memory, and DynamoDB Local tables.

Auto-registers via `ServiceLoader` — consumers add a single Surefire-scoped dependency block. No top-level test-scope dep, no properties file, no JVM flags, no `@ExtendWith` annotations.

For requirements and design, see [docs/requirements.md](docs/requirements.md) and [docs/design.md](docs/design.md).

## Consume

Add a single `maven-surefire-plugin` block to your project's `pom.xml`:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.michaelsgroi.test</groupId>
            <artifactId>junit-leak-detector</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</plugin>
```

That's it. The library JAR lives on Surefire's forked-test classpath only — it doesn't touch the project's main or test scope, doesn't bleed into IDE indexing, and doesn't appear in published artifacts. Defaults monitor `ports,threads,systemprops,envvars,memory`; DDB tables are opt-in via `monitored.resource.types`. To override defaults, add a `<configuration><systemPropertyVariables>` block inside the same plugin entry — see [Configuration](#configuration).

For thorough detection of cross-class leaks, run with `forkCount=1` and `reuseForks=true` (Surefire's defaults). Under multi-fork the detector still works correctly within each fork — leaks visible inside a single fork are detected and attributed normally — but leaks that would have spanned forks are invisible (false negative). The detector never produces false positives because of multi-fork.

After `mvn test`, three files land in the configured output directory (default: `target/resource-leak-detector`):

- `raw-report-<ISO-timestamp>.json` — JSON Lines machine-readable record
- `leak-summary-<ISO-timestamp>.html` — human-readable summary, mirrors what's logged
- `thread-creations-<ISO-timestamp>.jfr` — JFR recording of `jdk.ThreadStart` events; consumed by attribution to attach a creation stack to each leaked thread

All three share the same ISO-8601-seconds timestamp suffix so prior runs aren't overwritten. The library does not auto-open the HTML — running tests shouldn't open browser tabs as a side effect.

## Standalone attribution CLI

If you have a saved raw report and want to re-run attribution:

```
attribution/bin/junit-leak-detector-attribution \
    raw-report.json [--memory-threshold-mb N]
```

Writes `leak-summary-<ISO-timestamp>.html` next to the input raw report and opens it in the default browser. Set `JUNIT_LEAK_DETECTOR_NO_OPEN=1` to suppress auto-opening.

## Configuration

Configuration is read exclusively from system properties of the form `resource.leak.detector.<key>`, set via Surefire's `<systemPropertyVariables>` block alongside the dependency injection. There is no properties-file mechanism — one config source, one location. Defaults are sensible so consumers who don't override anything get a working detector.

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.michaelsgroi.test</groupId>
            <artifactId>junit-leak-detector</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
    <configuration>
        <systemPropertyVariables>
            <resource.leak.detector.build.failure.resource.types>ports,threads</resource.leak.detector.build.failure.resource.types>
            <resource.leak.detector.memory.growth.threshold.mb>100</resource.leak.detector.memory.growth.threshold.mb>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

| Property | What it controls | Default |
|---|---|---|
| `disabled` | Master kill switch. When `true`, the listener short-circuits at suite start; no marker files, no monitors, no snapshots, no report. | `false` |
| `monitored.resource.types` | Comma-separated list of monitors to enable. Valid values: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. | `ports,threads,systemprops,envvars,memory` |
| `memory.growth.threshold.mb` | Per-class heap growth (per-class-end − per-class-start, in MB) before flagging a memory leak. | `50` |
| `build.failure.resource.types` | Comma-separated list of resource types whose leaks should fail the build. Empty = report only. | (empty) |
| `snapshot.granularity` | `class` = per-class boundaries only. `test` = also per-test boundaries (parameterized invocations, dynamic tests, repeated tests, nested-class tests) for fine-grained debugging at ~Nx more snapshot operations. | `class` |
| `report.output.dir` | Directory where `raw-report-<ts>.json`, `leak-summary-<ts>.html`, and `thread-creations-<ts>.jfr` are written. | `target/resource-leak-detector` |
| `preclass.settle.enabled` | Optional pre-class settle wait. Before each per-class start boundary, polls until threads/ports introduced by the previous class have released, sharpening attribution. | `false` |
| `preclass.settle.max.seconds` | Max time the settle wait will block. | `10` |
| `final.settle.max.seconds` | Max wait at the FINAL boundary for threads/ports to drain after suite-shared shutdown hooks fire. | `90` |
| `thread.creation.tracking.enabled` | Capture `jdk.ThreadStart` events via JFR so attribution can show each leaked thread's creation stack. | `true` |
| `thread.creation.stack.depth` | Stack depth captured per `jdk.ThreadStart` event. | `30` |

**Disabling the detector entirely:** set `resource.leak.detector.disabled=true` (keeps the dep on the classpath but turns the detector off), or remove the Surefire `<dependencies>` block (zero runtime cost).

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
