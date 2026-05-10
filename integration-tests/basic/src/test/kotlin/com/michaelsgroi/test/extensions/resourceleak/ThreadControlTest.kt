package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Control test: starts a thread and waits for it to complete (TERMINATED state).
 * The thread monitor filters out TERMINATED threads, so this should not appear
 * in the leak report.
 */
class ThreadControlTest {
    @Test
    fun `thread started and joined within test does not leak`() {
        val thread = Thread({ Thread.sleep(50) }, "control-thread-ThreadControlTest")
        thread.isDaemon = true
        thread.start()
        thread.join(2000)
        assertTrue(!thread.isAlive)
    }
}
