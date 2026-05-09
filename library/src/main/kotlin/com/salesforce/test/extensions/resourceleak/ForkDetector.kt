package com.salesforce.test.extensions.resourceleak

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Detects whether the current JVM is one of multiple forks servicing a single test plan.
 *
 * Reliable leak detection requires `forkCount=1` and `reuseForks=true` so that all test
 * classes share one JVM. When the consumer misconfigures Surefire, each fork starts clean
 * and cross-class sticky leaks become invisible. We catch this at runtime without parsing
 * pom.xml: each JVM writes a marker file at suite start; if any prior markers exist in
 * the same recent window, multiple forks are servicing the same suite and we warn.
 */
class ForkDetector(
    private val markerDirectory: File,
    private val priorMarkerWindow: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
    private val pid: Long = ProcessHandle.current().pid(),
    private val log: Logger = LoggerFactory.getLogger(ForkDetector::class.java),
) {
    /**
     * Writes this fork's marker and returns the list of prior markers found within the
     * detection window. A non-empty list means multiple JVMs are servicing the same suite.
     */
    fun checkAndRecordFork(): List<ForkMarker> {
        markerDirectory.mkdirs()
        val now = clock.instant()
        val priorMarkers = listPriorMarkers(now)
        writeMarker(now)
        if (priorMarkers.isNotEmpty()) {
            log.warn(
                "Multiple forks detected for this suite: {} prior fork(s) ran in the last {}s. " +
                    "This usually means forkCount>1 or reuseForks=false in Surefire, which makes " +
                    "cross-class leaks invisible because each fork starts clean. " +
                    "Set forkCount=1 and reuseForks=true (or use the orchestrator plugin which " +
                    "sets them automatically). Prior markers: {}",
                priorMarkers.size,
                priorMarkerWindow.seconds,
                priorMarkers,
            )
        }
        return priorMarkers
    }

    private fun listPriorMarkers(now: Instant): List<ForkMarker> {
        val files = markerDirectory.listFiles { f -> f.isFile && f.name.endsWith(MARKER_SUFFIX) } ?: return emptyList()
        return files
            .mapNotNull { parseMarker(it) }
            .filter { it.pid != pid && Duration.between(it.startedAt, now).abs() <= priorMarkerWindow }
    }

    private fun writeMarker(now: Instant) {
        val markerFile = File(markerDirectory, "$pid$MARKER_SUFFIX")
        markerFile.writeText("pid=$pid\nstartedAt=$now\n")
    }

    private fun parseMarker(file: File): ForkMarker? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val pidLine =
            text
                .lineSequence()
                .firstOrNull { it.startsWith("pid=") }
                ?.removePrefix("pid=")
                ?.trim()
        val startedAtLine =
            text
                .lineSequence()
                .firstOrNull { it.startsWith("startedAt=") }
                ?.removePrefix("startedAt=")
                ?.trim()
        val pid = pidLine?.toLongOrNull() ?: return null
        val startedAt = startedAtLine?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return ForkMarker(pid, startedAt)
    }

    companion object {
        private const val MARKER_SUFFIX = ".marker"
    }
}

data class ForkMarker(
    val pid: Long,
    val startedAt: Instant,
)
