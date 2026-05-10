package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Control test: sets a system property and clears it in @AfterEach. Verifies the
 * detector does NOT report this as a leak.
 */
class SystemPropertyControlTest {
    private val propertyName = "control.property.SystemPropertyControlTest"

    @Test
    fun `system property set and restored within test does not leak`() {
        System.setProperty(propertyName, "control-value")
        assertEquals("control-value", System.getProperty(propertyName))
        Thread.sleep(150)
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty(propertyName)
    }
}
