# junit-leak-detector-app

End-to-end consumer test for [`junit-leak-detector`](../junit-leak-detector). Validates that the detector finds intentional leaks for the 5 monitors that have no optional runtime dependency: ports, threads, system properties, environment variables, and memory.

DynamoDB Local table detection is verified separately in `junit-leak-detector-ddbapp`.

## Run

Each `*LeakingTest` intentionally leaks one resource. The leak detector runs as a JUnit Platform listener + Jupiter extension auto-registered via ServiceLoader. After all tests complete, the leak report prints to the console.

Three scenarios are wired up via Maven profiles. Run them individually:

| Command | Scenario | Exit | What it verifies |
|---|---|---|---|
| `mvn test` | Report-only | `0` (PASS) | All 5 monitors enabled, no `build.failure.resource.types`. Build succeeds; full leak report is printed but the build does not fail. |
| `mvn test -Pbuild-failure` | Build-failure | `1` (FAIL) | Same 5 monitors, plus `build.failure.resource.types=memory`. Build fails with `Build failure triggered - leaks detected for resource types: memory`. |
| `mvn test -Pverify-failfast` | Fail-fast | `1` (FAIL) | Only `ddbtables` enabled, no AWS SDK on classpath. Build fails at startup with `Monitor 'ddbtables' requires software.amazon.awssdk:dynamodb on the test classpath.` |

`make test` runs all three and asserts the expected behavior, with the noisy Surefire output filtered out for the failing ones.

## Expected report

```
Resource Leak Detector Report
==============================
Test Classes Executed: 5

Test Class Execution Times:
  - com.michaelsgroi.test.extensions.resourceleak.EnvironmentVariableLeakingTest: 10ms
  - com.michaelsgroi.test.extensions.resourceleak.PortLeakingTest: 7ms
  - com.michaelsgroi.test.extensions.resourceleak.ThreadLeakingTest: 0ms
  - com.michaelsgroi.test.extensions.resourceleak.MemoryLeakingTest: 3ms
  - com.michaelsgroi.test.extensions.resourceleak.SystemPropertyLeakingTest: 0ms

System Property Leaks:
  - Property: leaked.by.SystemPropertyLeakingTest

Environment Variable Leaks:
  - Variable: LEAKED_BY_ENVIRONMENT_VARIABLE_LEAKING_TEST

Thread Leaks:
  - Thread: leaked-by-ThreadLeakingTest

Port Leaks:
  - Port: <ephemeral>

Memory Leaks:
  - Increase: 24 MB
```

## Verifying isolation from optional library deps

```
mvn dependency:tree
```

Confirm `software.amazon.awssdk` is absent. The library declares the AWS SDK as `<scope>provided</scope>`, so it is not pulled into this testapp's classpath; the `ddbtables` monitor would fail fast if enabled here. DDB detection is verified separately in `junit-leak-detector-ddbapp`.
