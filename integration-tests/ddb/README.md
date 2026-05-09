# junit-leak-detector-ddbapp

End-to-end consumer test for the `ddbtables` monitor in [`junit-leak-detector`](../junit-leak-detector). Validates that a leaked DynamoDB Local table is reported.

Kept separate from `junit-leak-detector-app` so that consumers without DynamoDB don't have to pull in the AWS SDK or DynamoDB Local.

## Run

The test starts an embedded in-JVM DynamoDB Local instance, creates a table, and intentionally does NOT clean up (no `@AfterAll` to delete the table or stop the server) — the leak is the point of the test. The leak detector reports the leaked table after all tests complete.

Two scenarios are wired up via Maven profiles. Run them individually:

| Command | Scenario | Exit | What it verifies |
|---|---|---|---|
| `mvn test` | Report-only | `0` (PASS) | `ddbtables` monitor enabled, no `build.failure.resource.types`. Build succeeds; leaked table is reported but the build does not fail. |
| `mvn test -Pbuild-failure` | Build-failure | `1` (FAIL) | Same monitor, plus `build.failure.resource.types=ddbtables`. Build fails with `Build failure triggered - leaks detected for resource types: ddbtables`. |

`make test` runs both and asserts the expected behavior, with the noisy Surefire output filtered out for the failing one.

## Expected report

```
Resource Leak Detector Report
==============================
Test Classes Executed: 1

Test Class Execution Times:
  - com.salesforce.test.extensions.resourceleak.DynamoDbTableLeakingTest: 1191ms

DynamoDB Table Leaks:
  - Table: leaked-by-DynamoDbTableLeakingTest
```

## Notes

- DynamoDB Local in `-inMemory` mode partitions data by signing region + credentials. The test client and the monitor must use the same `Region` (here, `US_EAST_1`) and same credentials to see the same tables.
- The library's `DynamoDbLocalTableMonitor` connects to `localhost:$EMBEDDED_DYNAMO_PORT` (default `8888`). Override with `-DEMBEDDED_DYNAMO_PORT=...` if needed.

## Verifying the fail-fast classpath check

Comment out the `software.amazon.awssdk:dynamodb` dependency in `pom.xml` and run `mvn test` again. The detector should fail at startup with a clear error naming the missing Maven coordinate.
