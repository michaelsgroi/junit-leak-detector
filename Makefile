SHELL := /bin/bash

.PHONY: all test install checks spotless-check cpd-check clean

# Default target: build + run unit and scenario tests + install to local Maven repo.
all: test

# Full build with all tests (Surefire + Failsafe scenarios).
test:
	mvn install

# Install to local Maven repo without running tests. Useful when you just want
# to consume the built jars from another project (e.g., to run the inline
# detector against a different repo).
install:
	mvn install -DskipTests

# On-demand static checks (also wired to run automatically at process-sources
# during mvn test/install).
checks: spotless-check cpd-check

spotless-check:
	@echo "==> Spotless check (ktlint)..."
	mvn -o spotless:check

cpd-check:
	@echo "==> CPD check (copy-paste detection)..."
	mvn -o pmd:cpd-check

clean:
	mvn clean
