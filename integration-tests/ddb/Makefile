SHELL := /bin/bash

.PHONY: test test-report-only test-build-failure clean

test: test-report-only test-build-failure

# Default scenario: ddbtables monitor enabled, no build.failure.resource.types.
# Build PASSES. The leaked DDB table is reported but does not fail the build.
test-report-only:
	@echo "==> Running default profile (expects BUILD SUCCESS, leaked DDB table reported, no build failure)..."
	@mvn test 2>&1 | tee /tmp/junit-leak-detector-ddbapp-report-only.log; \
		exit_code=$${PIPESTATUS[0]}; \
		if [ $$exit_code -ne 0 ]; then \
			echo "FAIL: expected BUILD SUCCESS, got exit code $$exit_code"; \
			exit 1; \
		fi; \
		if ! grep -q "DynamoDB Table Leaks" /tmp/junit-leak-detector-ddbapp-report-only.log; then \
			echo "FAIL: expected report to include 'DynamoDB Table Leaks'"; \
			exit 1; \
		fi; \
		if ! grep -q "Table: leaked-by-DynamoDbTableLeakingTest" /tmp/junit-leak-detector-ddbapp-report-only.log; then \
			echo "FAIL: expected report to name the leaked table"; \
			exit 1; \
		fi; \
		if grep -q "Build failure triggered" /tmp/junit-leak-detector-ddbapp-report-only.log; then \
			echo "FAIL: did not expect 'Build failure triggered' log line"; \
			exit 1; \
		fi; \
		echo "PASS: build succeeded, leaked DDB table reported, no build failure triggered"

# build-failure scenario: ddbtables monitor enabled, plus build.failure.resource.types=ddbtables.
# Build FAILS. Output is suppressed because the failure is expected; we only print
# the leak report (extracted from the captured log) and the PASS line.
test-build-failure:
	@echo ""
	@echo "==> Running build-failure profile (expects BUILD FAILURE: DDB leak triggers build failure)..."
	@mvn test -Pbuild-failure > /tmp/junit-leak-detector-ddbapp-build-failure.log 2>&1; \
		exit_code=$$?; \
		sed -n '/Resource Leak Detector Report/,/Build failure triggered/p' /tmp/junit-leak-detector-ddbapp-build-failure.log; \
		if [ $$exit_code -eq 0 ]; then \
			echo "FAIL: expected non-zero exit (DDB leak should trigger build failure), got 0"; \
			exit 1; \
		fi; \
		if ! grep -q "DynamoDB Table Leaks" /tmp/junit-leak-detector-ddbapp-build-failure.log; then \
			echo "FAIL: expected report to include 'DynamoDB Table Leaks'"; \
			exit 1; \
		fi; \
		if ! grep -q "Table: leaked-by-DynamoDbTableLeakingTest" /tmp/junit-leak-detector-ddbapp-build-failure.log; then \
			echo "FAIL: expected report to name the leaked table"; \
			exit 1; \
		fi; \
		if ! grep -q "Build failure triggered" /tmp/junit-leak-detector-ddbapp-build-failure.log; then \
			echo "FAIL: expected 'Build failure triggered' log line"; \
			exit 1; \
		fi; \
		echo "PASS: build failed as expected, leaked DDB table reported, build failure triggered for ddbtables"

clean:
	mvn clean
