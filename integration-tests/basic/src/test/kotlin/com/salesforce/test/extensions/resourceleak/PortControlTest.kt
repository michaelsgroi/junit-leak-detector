package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Control test: opens and CLOSES a port. Holds it long enough for at least one poll
 * cycle to detect it, then closes it before the test ends. Verifies the detector
 * does NOT report it as a leak.
 */
class PortControlTest {

    @Test
    fun `port opened and closed within test does not leak`() {
        val socket = ServerSocket(0)
        try {
            assertTrue(socket.localPort > 0)
            Thread.sleep(150)
        } finally {
            socket.close()
        }
    }
}
