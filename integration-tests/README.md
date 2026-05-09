# integration-tests

End-to-end consumer tests for [`junit-leak-detector`](../). Each module is a real Maven project that depends on the published library JAR and exercises one slice of consumer behavior.

| Module | Purpose |
|---|---|
| [`basic`](basic/) | Validates the 5 monitors that have no optional runtime dependency: ports, threads, system properties, environment variables, memory. |
| [`ddb`](ddb/) | Validates the `ddbtables` monitor against an embedded in-JVM DynamoDB Local. Kept separate so consumers without DynamoDB don't have to pull in the AWS SDK. |

## Configuration layering (the pattern these modules use)

Both IT modules use the **same two-layer configuration pattern** the library is designed for:

1. **Base config** lives in `src/test/resources/resource-leak-detector.properties`. This is the long-lived config a real consumer would commit to their repo.
2. **Scenario overrides** live in Maven `<profile>` blocks in `pom.xml`. Each profile sets one or two `<systemPropertyVariables>` that override specific keys in the file at runtime, exercising a particular detector behavior (build failure, fail-fast, etc.).

The library reads each key in this order: **system property → properties file → built-in default**. So a profile-set system property always wins over the file value, and the file always wins over the default.

### Why this pattern, not pure properties file?

Maven profiles cleanly toggle scenarios via `mvn test -P<profile>`. They can't easily swap a properties file (would require `<testResources>` overlays per profile, which is fragile). System-property overrides give us per-scenario behavior with a one-line change in the pom.

### Why not pure system properties (the previous setup)?

Putting all config in `<systemPropertyVariables>` works but doesn't exercise the file-loading code path the library is designed around. A consumer reading these IT modules as examples would learn the wrong pattern. The properties file is the "primary" config source per the library's README; system properties are the override mechanism.

## Layout per module

```
basic/
├── pom.xml                                          ← default Surefire config + profiles
├── src/test/resources/
│   └── resource-leak-detector.properties           ← base config (5 monitors, 500ms polling, ...)
└── src/test/kotlin/.../resourceleak/
    └── *LeakingTest.kt                              ← intentionally-leaky tests
```

## Running

From any IT module's directory:

```
mvn test                  # default scenario from properties file
mvn test -Pbuild-failure  # overlay: enable build failure
make test                 # the module's Makefile asserts all scenarios with clean output
```

From the project root:

```
make test                 # builds the library, then runs all IT modules' make test targets
```
