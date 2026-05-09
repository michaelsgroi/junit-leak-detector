package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Intentionally leaks system properties to verify the resource leak detector reports them.
 * Run with: mvn test -pl zos -Dtest=SystemPropertyLeakingTest -Dresource.leak.detector.monitored.resource.types=systemprops
 */
class SystemPropertyLeakingTest {
    @Test
    fun `test that leaks a system property`() {
        System.setProperty("leaked.by.SystemPropertyLeakingTest", "leaked-value")
        assertEquals("leaked-value", System.getProperty("leaked.by.SystemPropertyLeakingTest"))
    }
}
