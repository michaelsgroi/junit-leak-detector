package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Intentionally leaks an environment variable to verify the resource leak detector reports it.
 * Run with: mvn test -pl zos -Dtest=EnvironmentVariableLeakingTest -Dresource.leak.detector.monitored.resource.types=envvars
 */
class EnvironmentVariableLeakingTest {
    @Test
    fun `test that leaks an environment variable`() {
        EnvVarReflection.set("LEAKED_BY_ENVIRONMENT_VARIABLE_LEAKING_TEST", "leaked-value")
        assertEquals("leaked-value", System.getenv("LEAKED_BY_ENVIRONMENT_VARIABLE_LEAKING_TEST"))
    }
}
