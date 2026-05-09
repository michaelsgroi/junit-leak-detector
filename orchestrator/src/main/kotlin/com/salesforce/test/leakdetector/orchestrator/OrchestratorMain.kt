package com.salesforce.test.leakdetector.orchestrator

import com.salesforce.test.leakdetector.attribution.Attribution
import com.salesforce.test.leakdetector.attribution.FinalReportRenderer
import com.salesforce.test.leakdetector.attribution.RawReportReader
import java.io.File
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Differential-ordering double-run orchestrator.
 *
 * Invokes the consuming project's test suite twice:
 *   - run 1: runOrder=alphabetical
 *   - run 2: runOrder=random with a recorded seed
 *
 * Each run produces its own raw report. After both runs complete the orchestrator
 * intersects the candidate sets via the attribution module and writes the final
 * leak report.
 *
 * In both runs the orchestrator forces the Surefire isolation prerequisites
 * (forkCount=1, reuseForks=true, junit.jupiter.extensions.autodetection.enabled=true)
 * via -D system properties, so the consuming project's pom configuration is overridden.
 */
object OrchestratorMain {
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

        val outputDir = parsed.outputDir
        outputDir.mkdirs()
        val rawReport1 = File(outputDir, "raw-report-1.json")
        val rawReport2 = File(outputDir, "raw-report-2.json")
        val finalReportFile = File(outputDir, "leak-report.txt")

        val seed = parsed.seed ?: System.currentTimeMillis()
        out.println("Orchestrator: project=${parsed.projectRoot.absolutePath}, runs=${parsed.runs}, seed=$seed")

        // We don't propagate sub-process exit codes. A surefire test failure during
        // a run is exactly the situation we want a report for — failing the
        // investigation tool because the suite had a flaky test would defeat the
        // purpose. The orchestrator only fails on internal errors (missing reports,
        // unparseable raw output) below.
        runSuite(
            projectRoot = parsed.projectRoot,
            rawReportFile = rawReport1,
            runOrder = "alphabetical",
            runOrderRandomSeed = null,
            out = out,
            runLabel = "run 1 (alphabetical)",
        )

        if (parsed.runs == 2) {
            runSuite(
                projectRoot = parsed.projectRoot,
                rawReportFile = rawReport2,
                runOrder = "random",
                runOrderRandomSeed = seed,
                out = out,
                runLabel = "run 2 (random, seed=$seed)",
            )
        }

        // Build the final report regardless of run exit codes — a non-zero exit from a
        // run is typically the build-failure mechanism the user enabled, and we still
        // want to emit the attributed report.
        val finalReport =
            try {
                buildFinalReport(rawReport1, rawReport2.takeIf { parsed.runs == 2 }, parsed.memoryGrowthThresholdBytes)
            } catch (e: Exception) {
                err.println("Error building final report: ${e.message}")
                return ERROR_EXIT_CODE
            }
        val text = FinalReportRenderer.renderText(finalReport)
        finalReportFile.writeText(text)
        out.println("Wrote final report to ${finalReportFile.absolutePath}")
        return 0
    }

    private fun runSuite(
        projectRoot: File,
        rawReportFile: File,
        runOrder: String,
        runOrderRandomSeed: Long?,
        out: PrintStream,
        runLabel: String,
    ) {
        out.println("==> $runLabel")
        val args =
            buildList {
                add(mvnExecutable())
                add("-f")
                add(projectRoot.absolutePath)
                add("test")
                add("-DforkCount=1")
                add("-DreuseForks=true")
                add("-Djunit.jupiter.extensions.autodetection.enabled=true")
                add("-Dsurefire.runOrder=$runOrder")
                if (runOrderRandomSeed != null) add("-Dsurefire.runOrder.random.seed=$runOrderRandomSeed")
                add("-Dresource.leak.detector.raw.report.output.path=${rawReportFile.absolutePath}")
                // Suppress the library's per-run build failure: the orchestrator owns
                // the build-failure decision and applies it once after intersection.
                add("-Dresource.leak.detector.build.failure.resource.types=")
            }
        val process =
            ProcessBuilder(args)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { out.println(it) }
        }
        val finished = process.waitFor(30, TimeUnit.MINUTES)
        check(finished) { "$runLabel timed out" }
    }

    private fun buildFinalReport(
        rawReport1: File,
        rawReport2: File?,
        memoryGrowthThresholdBytes: Long,
    ): com.salesforce.test.leakdetector.attribution.FinalReport {
        require(rawReport1.isFile) { "raw report 1 missing: ${rawReport1.absolutePath}" }
        val attribution1 =
            Attribution.attributeSingleRun(RawReportReader.read(rawReport1), memoryGrowthThresholdBytes)
        if (rawReport2 == null) return attribution1
        require(rawReport2.isFile) { "raw report 2 missing: ${rawReport2.absolutePath}" }
        val attribution2 =
            Attribution.attributeSingleRun(RawReportReader.read(rawReport2), memoryGrowthThresholdBytes)
        return Attribution.intersectAcrossRuns(attribution1, attribution2)
    }

    private fun parseArgs(
        args: Array<String>,
        err: PrintStream,
    ): ParsedArgs? {
        if (args.contains("-h") || args.contains("--help")) {
            printUsage(err)
            return null
        }

        var projectRoot: File? = null
        var outputDir: File? = null
        var runs = 2
        var seed: Long? = null
        var memoryThresholdMb: Long = 0L
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--project-root" -> {
                    if (i + 1 >= args.size) return missing(arg, err)
                    projectRoot = File(args[i + 1])
                    i += 2
                }

                "--output-dir" -> {
                    if (i + 1 >= args.size) return missing(arg, err)
                    outputDir = File(args[i + 1])
                    i += 2
                }

                "--runs" -> {
                    if (i + 1 >= args.size) return missing(arg, err)
                    runs = args[i + 1].toIntOrNull() ?: run {
                        err.println("Error: --runs must be 1 or 2, got '${args[i + 1]}'")
                        printUsage(err)
                        return null
                    }
                    if (runs !in 1..2) {
                        err.println("Error: --runs must be 1 or 2, got $runs")
                        printUsage(err)
                        return null
                    }
                    i += 2
                }

                "--seed" -> {
                    if (i + 1 >= args.size) return missing(arg, err)
                    seed = args[i + 1].toLongOrNull() ?: run {
                        err.println("Error: --seed must be a number, got '${args[i + 1]}'")
                        printUsage(err)
                        return null
                    }
                    i += 2
                }

                "--memory-threshold-mb" -> {
                    if (i + 1 >= args.size) return missing(arg, err)
                    memoryThresholdMb = args[i + 1].toLongOrNull() ?: run {
                        err.println("Error: --memory-threshold-mb must be a number, got '${args[i + 1]}'")
                        printUsage(err)
                        return null
                    }
                    i += 2
                }

                else -> {
                    err.println("Error: unknown option '$arg'")
                    printUsage(err)
                    return null
                }
            }
        }

        if (projectRoot == null) {
            err.println("Error: --project-root is required")
            printUsage(err)
            return null
        }
        if (!projectRoot.isDirectory || !File(projectRoot, "pom.xml").isFile) {
            err.println("Error: project-root must be a Maven module directory: ${projectRoot.absolutePath}")
            return null
        }
        val resolvedOutputDir =
            outputDir ?: File(projectRoot, "target/resource-leak-detector/orchestrator-${UUID.randomUUID()}")
        return ParsedArgs(
            projectRoot = projectRoot,
            outputDir = resolvedOutputDir,
            runs = runs,
            seed = seed,
            memoryGrowthThresholdBytes = memoryThresholdMb * BYTES_PER_MB,
        )
    }

    private fun missing(
        arg: String,
        err: PrintStream,
    ): ParsedArgs? {
        err.println("Error: $arg requires an argument")
        printUsage(err)
        return null
    }

    private fun printUsage(err: PrintStream) {
        err.println(
            """
            Usage: orchestrator --project-root <dir> [options]

            Invokes `mvn test` against the consuming module twice with different
            Surefire runOrder, then intersects the candidate sets via the attribution
            module and writes the final leak report.

            Required:
              --project-root <dir>        Maven module to test against (must contain pom.xml)

            Options:
              --runs <1|2>                Number of runs (default: 2). 1 runs only run 1.
              --seed <long>               Seed for run 2's random order (default: current time millis)
              --output-dir <dir>          Where to put raw-report-1.json, raw-report-2.json,
                                          leak-report.txt (default: target/resource-leak-detector/orchestrator-<uuid>
                                          inside the project)
              --memory-threshold-mb <n>   Memory growth threshold in MB for attribution (default: 0)
              -h, --help                  Show this help

            Both runs are invoked with these Surefire flags forced via -D properties:
              forkCount=1, reuseForks=true, junit.jupiter.extensions.autodetection.enabled=true
            """.trimIndent(),
        )
    }

    private fun mvnExecutable(): String = System.getenv("MAVEN_BIN") ?: "mvn"

    private data class ParsedArgs(
        val projectRoot: File,
        val outputDir: File,
        val runs: Int,
        val seed: Long?,
        val memoryGrowthThresholdBytes: Long,
    )

    private const val USAGE_EXIT_CODE = 2
    private const val ERROR_EXIT_CODE = 1
    private const val BYTES_PER_MB = 1_048_576L
}
