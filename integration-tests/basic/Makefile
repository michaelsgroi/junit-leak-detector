SHELL := /bin/bash

.PHONY: test test-report-only test-build-failure test-failfast clean

test: test-report-only test-build-failure test-failfast

# Default scenario: 5 monitors enabled, no build.failure.resource.types.
# Build PASSES. The leak detector reports all 5 leak types but does not fail the build.
test-report-only:
	@echo "==> Running default profile (expects BUILD SUCCESS, all 5 leak types reported, no build failure)..."
	@mvn test 2>&1 | tee /tmp/junit-leak-detector-app-report-only.log; \
		exit_code=$${PIPESTATUS[0]}; \
		if [ $$exit_code -ne 0 ]; then \
			echo "FAIL: expected BUILD SUCCESS, got exit code $$exit_code"; \
			exit 1; \
		fi; \
		for leak in "System Property Leaks" "Environment Variable Leaks" "Thread Leaks" "Port Leaks" "Memory Leaks"; do \
			if ! grep -q "$$leak" /tmp/junit-leak-detector-app-report-only.log; then \
				echo "FAIL: expected report to include '$$leak'"; \
				exit 1; \
			fi; \
		done; \
		if grep -q "Build failure triggered" /tmp/junit-leak-detector-app-report-only.log; then \
			echo "FAIL: did not expect 'Build failure triggered' log line"; \
			exit 1; \
		fi; \
		for control in "control.property.SystemPropertyControlTest" "CONTROL_VAR_ENVIRONMENT_VARIABLE_CONTROL_TEST" "control-thread-ThreadControlTest"; do \
			if grep -q "$$control" /tmp/junit-leak-detector-app-report-only.log; then \
				echo "FAIL: control resource '$$control' was incorrectly flagged as a leak"; \
				exit 1; \
			fi; \
		done; \
		echo "PASS: build succeeded, all 5 leak types reported, no build failure triggered, no control resources flagged"

# build-failure scenario: same 5 monitors, plus build.failure.resource.types=memory.
# Build FAILS. Output is suppressed because the failure is expected; we only print
# the leak report (extracted from the captured log) and the PASS line.
test-build-failure:
	@echo ""
	@echo "==> Running build-failure profile (expects BUILD FAILURE: memory leak triggers build failure)..."
	@mvn test -Pbuild-failure > /tmp/junit-leak-detector-app-build-failure.log 2>&1; \
		exit_code=$$?; \
		sed -n '/Resource Leak Detector Report/,/Build failure triggered/p' /tmp/junit-leak-detector-app-build-failure.log; \
		if [ $$exit_code -eq 0 ]; then \
			echo "FAIL: expected non-zero exit (memory leak should trigger build failure), got 0"; \
			exit 1; \
		fi; \
		for leak in "System Property Leaks" "Environment Variable Leaks" "Thread Leaks" "Port Leaks" "Memory Leaks"; do \
			if ! grep -q "$$leak" /tmp/junit-leak-detector-app-build-failure.log; then \
				echo "FAIL: expected report to include '$$leak'"; \
				exit 1; \
			fi; \
		done; \
		if ! grep -q "Build failure triggered" /tmp/junit-leak-detector-app-build-failure.log; then \
			echo "FAIL: expected 'Build failure triggered' log line"; \
			exit 1; \
		fi; \
		echo "PASS: build failed as expected, all 5 leak types reported, build failure triggered for memory"

# fail-fast scenario: ddbtables monitor enabled but AWS SDK absent.
# Build FAILS at startup. Output is suppressed; we only print the fail-fast error and PASS.
test-failfast:
	@echo ""
	@echo "==> Running verify-failfast profile (expects BUILD FAILURE: missing AWS SDK for ddbtables)..."
	@mvn test -Pverify-failfast > /tmp/junit-leak-detector-app-failfast.log 2>&1; \
		exit_code=$$?; \
		failfast_line=$$(grep "Monitor 'ddbtables' requires software.amazon.awssdk:dynamodb" /tmp/junit-leak-detector-app-failfast.log); \
		if [ $$exit_code -eq 0 ]; then \
			echo "FAIL: expected non-zero exit, got 0"; \
			tail -40 /tmp/junit-leak-detector-app-failfast.log; \
			exit 1; \
		fi; \
		if [ -z "$$failfast_line" ]; then \
			echo "FAIL: expected fail-fast error message not found"; \
			tail -40 /tmp/junit-leak-detector-app-failfast.log; \
			exit 1; \
		fi; \
		echo "$$failfast_line"; \
		echo "PASS: build failed with expected fail-fast error"

clean:
	mvn clean
