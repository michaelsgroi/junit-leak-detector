package com.michaelsgroi.test.extensions.resourceleak

import jdk.jfr.Recording
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Wraps a JFR `Recording` that captures `jdk.ThreadStart` events for the duration of the
 * test plan. Each event includes the new thread's name+id, the parent thread, and a
 * stack trace of the call site that started it. The dumped `.jfr` file is consumed by
 * the attribution module to enrich each leaked thread with its creation stack.
 *
 * Why JFR rather than bytecode instrumentation: `jdk.ThreadStart` is a built-in JVM
 * event, captured without any agent or weaving. Overhead is negligible — events fire
 * once per thread creation, not continuously.
 *
 * Failure modes are non-fatal: if the recording can't be started (security manager,
 * unsupported JVM, IO error), we log a WARN and continue without it. The leak detector
 * still works; thread leaks just won't have creation stacks attached.
 */
class ThreadCreationRecorder(
    private val outputFile: File,
    private val stackDepth: Int,
) {
    private var recording: Recording? = null

    fun start() {
        try {
            val r =
                Recording().apply {
                    name = "junit-leak-detector-thread-creations"
                    enable("jdk.ThreadStart").withStackTrace()
                }
            r.start()
            recording = r
        } catch (e: Exception) {
            log.warn(
                "Failed to start JFR recording for thread-creation tracking; " +
                    "thread leaks will be reported without creation stacks: {}",
                e.message,
            )
        } catch (e: NoClassDefFoundError) {
            // JFR module not present (e.g., minimal JRE); silently skip.
            log.debug("jdk.jfr not available; skipping thread-creation tracking")
        }
    }

    fun stopAndDump() {
        val r = recording ?: return
        try {
            r.stop()
            outputFile.parentFile?.mkdirs()
            r.dump(outputFile.toPath())
        } catch (e: Exception) {
            log.warn("Failed to dump JFR recording to {}: {}", outputFile.absolutePath, e.message)
        } finally {
            try {
                r.close()
            } catch (_: Exception) {
                // best-effort close
            }
            recording = null
        }
    }

    @Suppress("unused") // future: per-event stack-depth control
    fun stackDepth(): Int = stackDepth

    companion object {
        private val log = LoggerFactory.getLogger(ThreadCreationRecorder::class.java)
    }
}
