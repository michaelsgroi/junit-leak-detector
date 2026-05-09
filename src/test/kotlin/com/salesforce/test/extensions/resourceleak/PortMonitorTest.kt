package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortMonitorTest {

    @Test
    fun `snapshot returns PortId types`() {
        val monitor = PortMonitor()

        val resources = monitor.snapshot()

        assertTrue(resources.all { it is ResourceId.PortId })
    }

    @Test
    fun `snapshot detects listening port`() {
        val serverSocket = java.net.ServerSocket(0) // bind to random available port
        try {
            val monitor = PortMonitor()

            val resources = monitor.snapshot()

            assertTrue(resources.contains(ResourceId.PortId(serverSocket.localPort)))
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `snapshot does not include closed port`() {
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.close()

        val monitor = PortMonitor()

        val resources = monitor.snapshot()

        assertTrue(!resources.contains(ResourceId.PortId(port)))
    }
}
