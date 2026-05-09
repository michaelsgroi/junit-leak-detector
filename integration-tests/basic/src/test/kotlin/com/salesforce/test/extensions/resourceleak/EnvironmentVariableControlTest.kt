package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

/**
 * Control test: sets an environment variable using system-stubs-jupiter, which
 * automatically restores the original env after the test. Verifies the detector
 * does NOT report this as a leak.
 */
@ExtendWith(SystemStubsExtension::class)
class EnvironmentVariableControlTest {

    @SystemStub
    var environmentVariables: EnvironmentVariables = EnvironmentVariables()

    @Test
    fun `environment variable set and restored within test does not leak`() {
        environmentVariables.set("CONTROL_VAR_ENVIRONMENT_VARIABLE_CONTROL_TEST", "control-value")
        assertEquals(
            "control-value",
            System.getenv("CONTROL_VAR_ENVIRONMENT_VARIABLE_CONTROL_TEST")
        )
        Thread.sleep(150)
    }
}
