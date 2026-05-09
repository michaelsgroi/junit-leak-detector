package com.salesforce.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Exercises the double-run orchestrator end-to-end against the basic suite.
 *
 * Builds the orchestrator classpath from the local Maven repo, invokes
 * OrchestratorMain in a sub-process pointed at integration-tests/basic, and
 * verifies that two raw reports plus a final intersected leak report were
 * produced, that the seed used for run 2 was recorded in the orchestrator's
 * own stdout, and that the final report includes the expected leaks.
 */
class OrchestratorScenarioIT {
    @Test
    fun `runs two orderings against the basic suite and produces an intersected final report`() {
        val repoRoot = repoRoot()
        val basicModule = File(repoRoot, "integration-tests/basic")
        val outputDir = File(basicModule, "target/resource-leak-detector/orchestrator-it")
        outputDir.deleteRecursively()

        val classpath = orchestratorClasspath(repoRoot)
        val process =
            ProcessBuilder(
                javaExecutable(),
                "-cp",
                classpath,
                "com.salesforce.test.leakdetector.orchestrator.OrchestratorMain",
                "--project-root",
                basicModule.absolutePath,
                "--output-dir",
                outputDir.absolutePath,
                "--runs",
                "2",
                "--seed",
                "42",
                "--memory-threshold-mb",
                "5",
            ).redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(15, TimeUnit.MINUTES)
        check(finished) { "orchestrator timed out" }
        assertEquals(0, process.exitValue(), "orchestrator should exit 0; output tail:\n${output.takeLast(2000)}")
        // The orchestrator does not propagate sub-process failures, but for our own
        // ITs we assert that no sub-process failed — we control the suite and a
        // BUILD FAILURE here would indicate a real regression in our test scaffolding.
        assertFalse(
            output.contains("BUILD FAILURE"),
            "expected NO 'BUILD FAILURE' in sub-process output; got tail:\n${output.takeLast(4000)}",
        )

        // Both raw reports + the final report exist.
        val rawReport1 = File(outputDir, "raw-report-1.json")
        val rawReport2 = File(outputDir, "raw-report-2.json")
        val finalReport = File(outputDir, "leak-report.txt")
        assertTrue(rawReport1.isFile, "expected raw-report-1.json at ${rawReport1.absolutePath}")
        assertTrue(rawReport2.isFile, "expected raw-report-2.json at ${rawReport2.absolutePath}")
        assertTrue(finalReport.isFile, "expected leak-report.txt at ${finalReport.absolutePath}")

        // Seed used for run 2 was reported.
        assertTrue(output.contains("seed=42"), "expected orchestrator to log seed=42; got:\n$output")

        // The final intersected report includes leak categories whose resource
        // identity is stable across runs: system props and env vars match by name;
        // thread name (sans id) is stable; memory is one-shot. Port numbers and
        // thread IDs are different per JVM run, so port leaks may or may not appear
        // in an intersection — we don't assert on them here.
        val text = finalReport.readText()
        listOf(
            "System Property Leaks",
            "Environment Variable Leaks",
        ).forEach { header ->
            assertTrue(text.contains(header), "expected final report to include '$header'; got:\n$text")
        }
    }

    private fun orchestratorClasspath(repoRoot: File): String {
        val orchJar = pickJar(File(repoRoot, "orchestrator/target"), "junit-leak-detector-orchestrator-")
        val attrJar = pickJar(File(repoRoot, "attribution/target"), "junit-leak-detector-attribution-")
        val mavenRepo = File(System.getProperty("user.home"), ".m2/repository")
        val stdlibFilter = { _: File, name: String -> name.startsWith("kotlin-stdlib-") && name.endsWith(".jar") }
        val stdlib =
            mavenRepo
                .resolve("org/jetbrains/kotlin/kotlin-stdlib")
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .flatMap { dir -> dir.listFiles(stdlibFilter).orEmpty().toList() }
                .firstOrNull()
                ?: error("kotlin-stdlib not found under $mavenRepo")
        return listOf(orchJar, attrJar, stdlib).joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun pickJar(
        targetDir: File,
        prefix: String,
    ): File =
        targetDir
            .listFiles { _, name ->
                name.startsWith(prefix) &&
                    name.endsWith(".jar") &&
                    !name.endsWith("-sources.jar") &&
                    !name.endsWith("-javadoc.jar")
            }.orEmpty()
            .firstOrNull()
            ?: error("No jar found in $targetDir matching $prefix*. Run `mvn install` first.")

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
