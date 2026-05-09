package com.salesforce.test.leakdetector.attribution.cli

import com.salesforce.test.leakdetector.attribution.Attribution
import com.salesforce.test.leakdetector.attribution.FinalReport
import com.salesforce.test.leakdetector.attribution.FinalReportRenderer
import com.salesforce.test.leakdetector.attribution.RawReportReader
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

        val text = FinalReportRenderer.renderText(report)
        // Always write a leak summary file next to the input raw report. Each summary
        // gets an ISO-8601-seconds timestamp suffix so multiple invocations don't clobber.
        val outputDir = parsed.firstReport.parentFile ?: File(".")
        val summaryFile = File(outputDir, "leak-summary-${timestampNow()}.txt")
        summaryFile.parentFile?.mkdirs()
        summaryFile.writeText(text)
        out.println("Wrote leak summary to ${summaryFile.absolutePath}")
        return 0
    }

    private fun buildReport(parsed: ParsedArgs): FinalReport {
        val first = RawReportReader.read(parsed.firstReport)
        val firstAttribution = Attribution.attributeSingleRun(first, parsed.memoryGrowthThresholdBytes)
        if (parsed.secondReport == null) return firstAttribution

        val second = RawReportReader.read(parsed.secondReport)
        val secondAttribution = Attribution.attributeSingleRun(second, parsed.memoryGrowthThresholdBytes)
        return Attribution.intersectAcrossRuns(firstAttribution, secondAttribution)
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
        var memoryThresholdMb: Long = 0L
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

        if (positional.isEmpty() || positional.size > 2) {
            err.println("Error: expected 1 or 2 raw report file paths, got ${positional.size}")
            printUsage(err)
            return null
        }
        val first = File(positional[0])
        val second = positional.getOrNull(1)?.let { File(it) }
        if (!first.isFile) {
            err.println("Error: raw report file not found: ${first.absolutePath}")
            return null
        }
        if (second != null && !second.isFile) {
            err.println("Error: raw report file not found: ${second.absolutePath}")
            return null
        }
        return ParsedArgs(
            firstReport = first,
            secondReport = second,
            memoryGrowthThresholdBytes = memoryThresholdMb * BYTES_PER_MB,
        )
    }

    private fun printUsage(err: PrintStream) {
        err.println(
            """
            Usage: attribution <raw-report-1> [raw-report-2] [options]

            Reads one or two raw reports produced by the test runner and writes the
            attributed leak summary as `leak-summary-<ISO-timestamp>.txt` next to
            the input raw report. With two reports, the candidate sets are
            intersected across runs to produce sharper attribution.

            Options:
              --memory-threshold-mb <n>     Memory growth threshold in MB (default: 0)
              -h, --help                    Show this help
            """.trimIndent(),
        )
    }

    private fun timestampNow(): String = FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC).toLocalDateTime()) + "Z"

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    private data class ParsedArgs(
        val firstReport: File,
        val secondReport: File?,
        val memoryGrowthThresholdBytes: Long,
    )

    private const val USAGE_EXIT_CODE = 2
    private const val ERROR_EXIT_CODE = 1
    private const val BYTES_PER_MB = 1_048_576L
}
