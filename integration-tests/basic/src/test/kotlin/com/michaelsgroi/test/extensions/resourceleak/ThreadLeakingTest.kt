package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Intentionally leaks a thread to verify the resource leak detector reports it.
 * Run with: mvn test -pl zos -Dtest=ThreadLeakingTest -Dresource.leak.detector.monitored.resource.types=threads -Dresource.leak.detector.thread.grace.period.seconds=0
 */
class ThreadLeakingTest {
    @Test
    fun `test that leaks a thread`() {
        val thread =
            Thread({
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (_: InterruptedException) {
                    // exit
                }
            }, "leaked-by-ThreadLeakingTest")
        thread.isDaemon = true
        thread.start()
        assertTrue(thread.isAlive)
    }
}
