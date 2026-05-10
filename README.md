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

## Investigation mode (orchestrator)

When inline mode reports a leak with a wide candidate set (multiple test classes implicated), use the orchestrator to narrow attribution. The orchestrator runs the suite twice with different test orderings (alphabetical, then random) and intersects the candidate sets — leaks attributed to the same test class in both runs collapse to that single class.

The orchestrator is an investigation/isolation tool. It does **not** impose its own build-failure decision and does **not** propagate sub-process exit codes; it always exits 0 on a clean orchestration regardless of test-suite outcomes. (Build-failure-on-leak is inline mode's job during regular `mvn test`.)

Run it (after `mvn install`):

```
orchestrator/bin/junit-leak-detector-orchestrator \
    --project-root /path/to/your/maven/module \
    --runs 2 \
    --memory-threshold-mb 100
```

Outputs land in `--output-dir` (default: the project root). All three files share the same ISO-8601 timestamp suffix:

- `raw-report-1-<ts>.json` — alphabetical run
- `raw-report-2-<ts>.json` — random run (with recorded seed)
- `leak-summary-<ts>.html` — final intersected leak summary; opened automatically in the default browser

Set `JUNIT_LEAK_DETECTOR_NO_OPEN=1` in the environment to suppress auto-opening (useful in CI).

Flags:

| Flag | Description | Default |
|---|---|---|
| `--project-root <dir>` | Required. The Maven module to invoke. | — |
| `--runs <1\|2>` | Number of runs. `1` = run once, no intersection. `2` = full investigation mode. | `2` |
| `--seed <long>` | Seed for run 2's random order. Recorded so the run is reproducible. | current time millis |
| `--output-dir <dir>` | Where to put the three output files. | the project root |
| `--memory-threshold-mb <n>` | Memory growth threshold in MB. | `0` |

The orchestrator forces these Surefire flags via `-D` properties on each `mvn test` invocation, overriding the consuming project's pom: `forkCount=1`, `reuseForks=true`, `junit.jupiter.extensions.autodetection.enabled=true`, `surefire.runOrder` (alphabetical/random), `surefire.runOrder.random.seed`, plus an empty `resource.leak.detector.build.failure.resource.types` to suppress the library's per-run build-failure trigger so run 1 cannot short-circuit run 2.

## Standalone attribution CLI

If you have a saved raw report and want to re-run attribution (or do a manual two-report intersection):

```
attribution/bin/junit-leak-detector-attribution \
    raw-report-1.json [raw-report-2.json] [--memory-threshold-mb N]
```

Writes `leak-summary-<ISO-timestamp>.html` next to the input raw report and opens it in the default browser. With one raw report, produces the single-run attributed summary. With two, intersects across runs. Set `JUNIT_LEAK_DETECTOR_NO_OPEN=1` to suppress auto-opening.

## Configuration

Library configuration is layered:

1. **Properties file** — `resource-leak-detector.properties` on the test classpath (e.g., `src/test/resources/resource-leak-detector.properties`). The long-lived base config. Keys go in unprefixed: `monitored.resource.types=ports,threads`.
2. **System properties** — `-Dresource.leak.detector.X=Y` on the CLI, or `<systemPropertyVariables>` in your Surefire config. These **override** the file value when set. Useful for per-invocation tweaks or per-profile scenario tests.
3. **Built-in defaults** — apply when neither the file nor a system property is set.

| Property | What it controls | Default |
|---|---|---|
| `monitored.resource.types` | Comma-separated list of monitors to enable. Valid values: `ports`, `threads`, `systemprops`, `envvars`, `memory`, `ddbtables`. Empty = nothing monitored. | (empty) |
| `memory.growth.threshold.mb` | Minimum heap growth (final − baseline, in MB) before flagging a memory leak | `1024` |
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
├── orchestrator/                          ← double-run investigation tool (runnable class + Bash launcher)
│   └── bin/junit-leak-detector-orchestrator
└── integration-tests/                     ← end-to-end consumer test apps (not published)
    ├── basic/                             ← 5 monitors (no DDB)
    ├── ddb/                               ← DynamoDB monitor only
    └── scenarios/                         ← failsafe-driven scenario tests over basic + ddb
```

Publishable artifacts: `junit-leak-detector` (library) and `junit-leak-detector-attribution`. The orchestrator and integration-tests modules are tools/tests and are not deployed.

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
