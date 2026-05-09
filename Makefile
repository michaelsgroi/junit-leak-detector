SHELL := /bin/bash

.PHONY: all build test test-it clean

all: test

# Build the library, run library unit tests, install to local Maven repo,
# then run all integration tests.
test: build test-it

build:
	@echo "==> Building junit-leak-detector library and installing to local Maven repo..."
	mvn -q clean install

# Run both integration-test modules. Each module's Makefile asserts specific scenarios
# (report-only, build-failure, fail-fast) via Maven profiles.
test-it:
	@$(MAKE) -C integration-tests/basic test
	@$(MAKE) -C integration-tests/ddb test

clean:
	mvn clean
	mvn -f integration-tests/pom.xml clean
