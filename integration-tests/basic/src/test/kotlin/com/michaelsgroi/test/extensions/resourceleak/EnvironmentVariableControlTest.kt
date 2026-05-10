package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Control test: sets an environment variable using direct reflection (the same
 * mechanism as EnvironmentVariableLeakingTest), then deletes it in @AfterEach.
 * Verifies the detector does NOT report this as a leak. Uses the same reflection
 * path as the leaking test to avoid order-fragile interactions with libraries
 * (like system-stubs-jupiter) that swap JDK internals via reflection.
 */
class EnvironmentVariableControlTest {
    private val varName = "CONTROL_VAR_ENVIRONMENT_VARIABLE_CONTROL_TEST"

    @Test
    fun `environment variable set and restored within test does not leak`() {
        EnvVarReflection.set(varName, "control-value")
        assertEquals("control-value", System.getenv(varName))
        Thread.sleep(150)
    }

    @AfterEach
    fun cleanup() {
        EnvVarReflection.remove(varName)
    }
}
