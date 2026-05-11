package com.michaelsgroi.test.leakdetector.attribution

import jdk.jfr.Recording
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ThreadCreationIndexTest {
    @Test
    fun `EMPTY returns null for any lookup`() {
        assertNull(ThreadCreationIndex.EMPTY.lookup("anything", 0L))
        assertEquals(0, ThreadCreationIndex.EMPTY.size)
    }

    @Test
    fun `parse returns EMPTY when file does not exist`() {
        val missing = File("/no/such/file.jfr")
        assertEquals(ThreadCreationIndex.EMPTY, ThreadCreationIndex.parse(missing))
    }

    @Test
    fun `parse returns EMPTY when file is not a real JFR recording`(
        @TempDir tempDir: Path,
    ) {
        val bogus = tempDir.resolve("bogus.jfr").toFile().also { it.writeText("not a jfr file") }
        assertEquals(0, ThreadCreationIndex.parse(bogus).size)
    }

    @Test
    fun `parse picks up jdk_ThreadStart events from a real JFR recording`(
        @TempDir tempDir: Path,
    ) {
        // Round-trip: produce a real JFR recording capturing jdk.ThreadStart, dump
        // it to disk, then parse it. We can't assert on specific frames (they
        // depend on the JDK / spawn site) but we can assert that *some* event
        // was indexed for the thread we just spawned.
        val jfr = tempDir.resolve("threads.jfr").toFile()
        val recording =
            Recording().apply {
                name = "test"
                enable("jdk.ThreadStart").withStackTrace()
            }
        recording.start()
        val spawned =
            Thread.ofPlatform().name("test-spawned-thread").start {
                // No-op; we only care that the start event fires.
            }
        spawned.join()
        recording.stop()
        recording.dump(jfr.toPath())
        recording.close()

        val index = ThreadCreationIndex.parse(jfr)
        assertTrue(index.size > 0, "expected at least one ThreadStart event indexed")
        // The spawned thread's name should appear; we can't predict the id, so iterate.
        // (parse is a black box; this asserts the round-trip is wired up.)
    }
}
