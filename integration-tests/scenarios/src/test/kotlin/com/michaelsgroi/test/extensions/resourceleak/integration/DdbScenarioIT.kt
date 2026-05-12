package com.michaelsgroi.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DdbScenarioIT {
    private val module = "integration-tests/ddb"

    @Test
    fun `report-only profile reports leaked DDB tables and exercises listTables pagination`() {
        val result = MavenScenarioRunner.run(module = module)

        assertTrue(result.succeeded, "expected BUILD SUCCESS, output tail:\n${result.output.takeLast(2000)}")
        assertTrue(result.containsLine("DynamoDB Table Leaks"))
        // Pagination payoff: the test leaks 5 tables with `ddbtables.list.page.size=2`,
        // so seeing both the first-page (0000) and the last-page (0004) tables proves
        // the monitor walked beyond the first page.
        assertTrue(result.containsLine("Table: pagination-test-table-0000"))
        assertTrue(result.containsLine("Table: pagination-test-table-0004"))
        assertFalse(result.containsLine("Build failure triggered"))
    }

    @Test
    fun `build-failure profile triggers build failure on DDB table leak`() {
        val result = MavenScenarioRunner.run(module = module, profiles = listOf("build-failure"))

        assertNotEquals(0, result.exitCode)
        assertTrue(result.containsLine("DynamoDB Table Leaks"))
        assertTrue(result.containsLine("Table: pagination-test-table-0000"))
        assertTrue(result.containsLine("Build failure triggered"))
    }
}
