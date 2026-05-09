package com.salesforce.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verifies the standalone attribution CLI produces the same output as the inline
 * AttributionRunner (which the library invokes at testPlanExecutionFinished). We
 * run the basic suite once to produce raw-report-<ts>.json + leak-summary-<ts>.txt,
 * then invoke the CLI against the raw report — the CLI writes its own
 * leak-summary-<ts2>.txt, which we compare line-for-line against the inline one.
 */
class CliScenarioIT {
    @Test
    fun `cli output matches inline leak-summary for the basic suite`() {
        // Clear prior output before running so we deterministically pick up the
        // raw report and inline summary produced by this run.
        val basicModule = File(repoRoot(), "integration-tests/basic")
        val outputDir = File(basicModule, "target/resource-leak-detector")
        outputDir.deleteRecursively()

        val basic = MavenScenarioRunner.run(module = "integration-tests/basic")
        assertTrue(basic.succeeded, "basic suite should succeed; output tail:\n${basic.output.takeLast(2000)}")

        // The basic suite's resource-leak-detector.properties points report.output.dir
        // at target/resource-leak-detector so output files don't accumulate in the
        // module root.
        val rawReport = pickFirstByPrefix(outputDir, "raw-report-", ".json")
        val inlineSummary = pickFirstByPrefix(outputDir, "leak-summary-", ".txt")
        assertTrue(rawReport != null, "expected raw-report-*.json under ${outputDir.absolutePath}")
        assertTrue(inlineSummary != null, "expected leak-summary-*.txt under ${outputDir.absolutePath}")

        // Run the CLI against the raw report. It writes a new leak-summary-<ts2>.txt
        // next to the input. Capture the path from stdout so we don't have to hunt.
        val classpath = cliClasspath()
        val process =
            ProcessBuilder(
                javaExecutable(),
                "-cp",
                classpath,
                "com.salesforce.test.leakdetector.attribution.cli.AttributionCli",
                rawReport!!.absolutePath,
                "--memory-threshold-mb",
                "5", // matches the basic suite's resource-leak-detector.properties
            ).redirectErrorStream(true)
                .start()
        val cliOutput = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(2, TimeUnit.MINUTES)
        check(finished) { "CLI invocation timed out" }
        assertEquals(0, process.exitValue(), "CLI exit non-zero; output:\n$cliOutput")

        val cliSummaryPath =
            cliOutput
                .lineSequence()
                .firstOrNull { it.startsWith("Wrote leak summary to ") }
                ?.removePrefix("Wrote leak summary to ")
                ?.trim()
                ?: error("CLI did not log the path of its leak summary; output:\n$cliOutput")
        val cliSummary = File(cliSummaryPath)
        assertTrue(cliSummary.isFile, "expected CLI to produce $cliSummaryPath")

        // Compare CLI output to the inline summary. They should match line-for-line.
        assertEquals(
            inlineSummary!!.readText().trim().lines(),
            cliSummary.readText().trim().lines(),
            "CLI summary should match inline summary",
        )
    }

    private fun pickFirstByPrefix(
        dir: File,
        prefix: String,
        suffix: String,
    ): File? = dir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(suffix) }?.firstOrNull()

    private fun cliClasspath(): String {
        val repoRoot = repoRoot()
        val attributionJar =
            File(repoRoot, "attribution/target")
                .listFiles { _, name ->
                    name.startsWith("junit-leak-detector-attribution-") &&
                        name.endsWith(".jar") &&
                        !name.endsWith("-sources.jar") &&
                        !name.endsWith("-javadoc.jar")
                }.orEmpty()
                .firstOrNull()
                ?: error("No attribution jar found. Run `mvn install` first.")
        val mavenRepo = File(System.getProperty("user.home"), ".m2/repository")
        val stdlibFilter = { _: File, name: String -> name.startsWith("kotlin-stdlib-") && name.endsWith(".jar") }
        val stdlib =
            mavenRepo
                .resolve("org/jetbrains/kotlin/kotlin-stdlib")
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .flatMap { dir -> dir.listFiles(stdlibFilter).orEmpty().toList() }
                .firstOrNull()
                ?: error("kotlin-stdlib not found in local Maven repo at $mavenRepo")
        return listOf(attributionJar, stdlib).joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "integration-tests").isDirectory && File(dir, "pom.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root")
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home") ?: error("java.home not set")
        return File(javaHome, "bin/java").absolutePath
    }
}
