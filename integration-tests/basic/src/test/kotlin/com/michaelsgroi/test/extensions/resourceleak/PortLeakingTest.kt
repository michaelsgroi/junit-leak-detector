package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Intentionally leaks a port to verify the resource leak detector reports it.
 * Run with: mvn test -pl zos -Dtest=PortLeakingTest -Dresource.leak.detector.monitored.resource.types=ports
 */
class PortLeakingTest {
    companion object {
        // Static reference so the socket survives beyond the test method
        var leakedSocket: ServerSocket? = null
    }

    @Test
    fun `test that leaks a port`() {
        leakedSocket = ServerSocket(0)
        assertTrue(leakedSocket!!.localPort > 0)
    }
}
