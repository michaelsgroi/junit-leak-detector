package com.michaelsgroi.test.leakdetector.attribution.cli

import com.michaelsgroi.test.leakdetector.attribution.Attribution
import com.michaelsgroi.test.leakdetector.attribution.FinalReport
import com.michaelsgroi.test.leakdetector.attribution.FinalReportRenderer
import com.michaelsgroi.test.leakdetector.attribution.HtmlOpener
import com.michaelsgroi.test.leakdetector.attribution.RawReportReader
import com.michaelsgroi.test.leakdetector.attribution.ThreadCreationIndex
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

object AttributionCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = run(args, System.out, System.err)
        if (exitCode != 0) exitProcess(exitCode)
    }

    fun run(
        args: Array<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val parsed = parseArgs(args, err) ?: return USAGE_EXIT_CODE

        val report =
            try {
                buildReport(parsed)
            } catch (e: Exception) {
                err.println("Error: ${e.message}")
                return ERROR_EXIT_CODE
            }

        val html = FinalReportRenderer.renderHtml(report)
        // Always write a leak summary HTML file next to the input raw report. Each
        // summary gets an ISO-8601-seconds timestamp suffix so multiple invocations
        // don't clobber.
        val outputDir = parsed.rawReport.parentFile ?: File(".")
        val summaryFile = File(outputDir, "leak-summary-${timestampNow()}.html")
        summaryFile.parentFile?.mkdirs()
        summaryFile.writeText(html)
        out.println("Wrote leak summary to ${summaryFile.absolutePath}")
        HtmlOpener.open(summaryFile)
        return 0
    }

    private fun buildReport(parsed: ParsedArgs): FinalReport {
        val report = RawReportReader.read(parsed.rawReport)
        // Auto-pair: derive `thread-creations-<ts>.jfr` from the raw report filename
        // and load it if present. Missing/unparsable files yield ThreadCreationIndex.EMPTY.
        val threadCreations =
            autoPairedJfr(parsed.rawReport)
                ?.let { ThreadCreationIndex.parse(it) }
                ?: ThreadCreationIndex.EMPTY
        // CLI flag wins; otherwise fall back to the threshold the run itself recorded
        // in the raw-report header so the standalone HTML matches the inline report.
        val thresholdBytes = parsed.memoryGrowthThresholdBytes ?: report.header.memoryGrowthThresholdBytes
        return Attribution.attributeSingleRun(
            report = report,
            memoryGrowthThresholdBytes = thresholdBytes,
            threadCreationIndex = threadCreations,
        )
    }

    /** Derives the paired JFR path from `raw-report-<ts>.json` → `thread-creations-<ts>.jfr`. */
    private fun autoPairedJfr(rawReport: File): File? {
        val name = rawReport.name
        if (!name.startsWith("raw-report-") || !name.endsWith(".json")) return null
        val ts = name.removePrefix("raw-report-").removeSuffix(".json")
        val candidate = File(rawReport.parentFile ?: File("."), "thread-creations-$ts.jfr")
        return if (candidate.isFile) candidate else null
    }

    private fun parseArgs(
        args: Array<String>,
        err: PrintStream,
    ): ParsedArgs? {
        if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
            printUsage(err)
            return null
        }

        val positional = mutableListOf<String>()
        var memoryThresholdMb: Long? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--memory-threshold-mb" -> {
                    if (i + 1 >= args.size) {
                        err.println("Error: $arg requires an argument")
                        printUsage(err)
                        return null
                    }
                    memoryThresholdMb = args[i + 1].toLongOrNull() ?: run {
                        err.println("Error: $arg must be a number, got '${args[i + 1]}'")
                        printUsage(err)
                        return null
                    }
                    i += 2
                }

                else -> {
                    if (arg.startsWith("-")) {
                        err.println("Error: unknown option '$arg'")
                        printUsage(err)
                        return null
                    }
                    positional += arg
                    i += 1
                }
            }
        }

        if (positional.size != 1) {
            err.println("Error: expected exactly 1 raw report file path, got ${positional.size}")
            printUsage(err)
            return null
        }
        val rawReport = File(positional[0])
        if (!rawReport.isFile) {
            err.println("Error: raw report file not found: ${rawReport.absolutePath}")
            return null
        }
        return ParsedArgs(
            rawReport = rawReport,
            memoryGrowthThresholdBytes = memoryThresholdMb?.let { it * BYTES_PER_MB },
        )
    }

    private fun printUsage(err: PrintStream) {
        err.println(
            """
            Usage: attribution <raw-report> [options]

            Reads a raw report produced by the test runner and writes the
            attributed leak summary as `leak-summary-<ISO-timestamp>.html` next to
            the input raw report; the file is opened in the default browser
            (suppress with JUNIT_LEAK_DETECTOR_NO_OPEN=1).

            If a `thread-creations-<ts>.jfr` file sits next to the raw report (paired by
            timestamp), thread leaks include the creation stack of each leaked thread.

            Options:
              --memory-threshold-mb <n>     Memory growth threshold in MB (default: value
                                            recorded in the raw report header, or 0 if absent)
              -h, --help                    Show this help
            """.trimIndent(),
        )
    }

    private fun timestampNow(): String = FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC).toLocalDateTime()) + "Z"

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    private data class ParsedArgs(
        val rawReport: File,
        /** `null` means "use the threshold recorded in the raw report header". */
        val memoryGrowthThresholdBytes: Long?,
    )

    private const val USAGE_EXIT_CODE = 2
    private const val ERROR_EXIT_CODE = 1
    private const val BYTES_PER_MB = 1_048_576L
}
