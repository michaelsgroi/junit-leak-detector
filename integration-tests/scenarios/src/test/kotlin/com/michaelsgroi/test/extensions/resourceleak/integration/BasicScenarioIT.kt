package com.michaelsgroi.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BasicScenarioIT {
    private val module = "integration-tests/basic"
    private val expectedLeakHeaders =
        listOf(
            "System Property Leaks",
            "Environment Variable Leaks",
            "Thread Leaks",
            "Port Leaks",
            "Memory Leaks",
        )
    private val expectedControlNames =
        listOf(
            "control.property.SystemPropertyControlTest",
            "CONTROL_VAR_ENVIRONMENT_VARIABLE_CONTROL_TEST",
            "control-thread-ThreadControlTest",
        )

    @Test
    fun `report-only profile reports all 5 leak types and does not fail the build`() {
        val result = MavenScenarioRunner.run(module = module)

        assertTrue(result.succeeded, "expected BUILD SUCCESS, output:\n${result.output.takeLast(2000)}")
        expectedLeakHeaders.forEach { header ->
            assertTrue(result.containsLine(header), "expected report to include '$header'")
        }
        assertFalse(
            result.containsLine("Build failure triggered"),
            "did not expect 'Build failure triggered' log line",
        )
        expectedControlNames.forEach { control ->
            assertFalse(
                result.containsLine(control),
                "control resource '$control' was incorrectly flagged as a leak",
            )
        }
    }

    @Test
    fun `build-failure profile triggers build failure on memory leak`() {
        val result = MavenScenarioRunner.run(module = module, profiles = listOf("build-failure"))

        assertNotEquals(0, result.exitCode, "expected build failure but got success")
        expectedLeakHeaders.forEach { header ->
            assertTrue(result.containsLine(header), "expected report to include '$header'")
        }
        assertTrue(
            result.containsLine("Build failure triggered"),
            "expected 'Build failure triggered' log line",
        )
    }

    @Test
    fun `verify-failfast profile fails fast when ddbtables monitor lacks AWS SDK dependency`() {
        val result = MavenScenarioRunner.run(module = module, profiles = listOf("verify-failfast"))

        assertNotEquals(0, result.exitCode, "expected fail-fast build failure")
        assertTrue(
            result.containsLine("Monitor 'ddbtables' requires software.amazon.awssdk:dynamodb"),
            "expected fail-fast error naming missing dependency",
        )
    }
}
