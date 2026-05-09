package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import uk.org.webcompere.systemstubs.properties.SystemProperties

/**
 * Control test: sets a system property using system-stubs-jupiter, which automatically
 * restores the original property value after the test. Verifies the detector does NOT
 * report this as a leak.
 */
@ExtendWith(SystemStubsExtension::class)
class SystemPropertyControlTest {
    @SystemStub
    var systemProperties: SystemProperties = SystemProperties()

    @Test
    fun `system property set and restored within test does not leak`() {
        systemProperties.set("control.property.SystemPropertyControlTest", "control-value")
        assertEquals("control-value", System.getProperty("control.property.SystemPropertyControlTest"))
        Thread.sleep(150)
    }
}
