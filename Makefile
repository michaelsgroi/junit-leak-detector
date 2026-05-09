SHELL := /bin/bash

.PHONY: all test checks spotless-check cpd-check clean

# Default target: run unit tests + integration scenario tests via mvn verify.
# Library scenario IT tests (failsafe) shell out to mvn against integration-tests/* modules,
# which must be installed to the local Maven repo first.
all: test

test:
	mvn install

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
