package com.michaelsgroi.test.extensions.resourceleak

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds standard output filenames for a single test run.
 *
 * All files in one run share the same ISO-8601 timestamp suffix (down to seconds,
 * filename-safe — `:` is replaced because it's unsafe on Windows and macOS HFS+).
 * That makes it easy to correlate `raw-report-<ts>.json` with `leak-summary-<ts>.txt`
 * and avoids overwriting prior runs in the same output directory.
 */
data class ReportPaths(
    val outputDir: File,
    val timestamp: String,
) {
    val rawReport: File get() = File(outputDir, "raw-report-$timestamp.json")
    val leakSummary: File get() = File(outputDir, "leak-summary-$timestamp.html")
    val threadCreations: File get() = File(outputDir, "thread-creations-$timestamp.jfr")

    fun rawReportForRun(runNumber: Int): File = File(outputDir, "raw-report-$runNumber-$timestamp.json")

    companion object {
        fun timestamp(now: Instant = Instant.now()): String = FORMATTER.format(now.atOffset(ZoneOffset.UTC).toLocalDateTime()) + "Z"

        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    }
}
