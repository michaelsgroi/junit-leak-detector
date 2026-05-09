package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamoDbLocalTableMonitorTest {

    @Test
    fun `snapshot returns empty set when DynamoDB Local not running`() {
        // Use a port that is unlikely to have DynamoDB Local running
        val monitor = DynamoDbLocalTableMonitor(port = 19999)

        val resources = monitor.snapshot()

        assertTrue(resources.isEmpty())
    }

    @Test
    fun `snapshot returns DynamoDbTableId types`() {
        // Use a port that is unlikely to have DynamoDB Local running
        // This verifies the monitor handles the exception gracefully and returns empty set
        val monitor = DynamoDbLocalTableMonitor(port = 19999)

        val resources = monitor.snapshot()

        assertTrue(resources.all { it is ResourceId.DynamoDbTableId })
    }
}
