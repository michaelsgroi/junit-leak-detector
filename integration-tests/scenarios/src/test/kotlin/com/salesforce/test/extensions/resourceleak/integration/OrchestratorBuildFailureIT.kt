package com.salesforce.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verifies that the orchestrator suppresses the library's per-run build-failure
 * trigger and does NOT impose its own.
 *
 * The orchestrator is an investigation/isolation tool. Build failure on detected
 * leaks is the library's job during a normal `mvn test`; the orchestrator just
 * produces sharper attribution. So:
 *
 *   - Run 1 must always complete (no short-circuit), even when the consumer's
 *     build-failure config would normally trigger a failure.
 *   - Run 2 must always run.
 *   - The orchestrator itself does NOT emit a "Build failure triggered" line and
 *     exits 0 on a clean Maven run.
 *
 * We invoke the orchestrator with -Dresource.leak.detector.build.failure.resource.types=memory
 * set on its JVM. The basic suite has memory leaks, so under normal `mvn test`
 * the library's AttributionRunner would fail the build. We verify that the
 * orchestrator suppresses this and exits cleanly.
 */
class OrchestratorBuildFailureIT {
    @Test
    fun `orchestrator suppresses library per-run build failure and runs both runs to completion`() {
        val repoRoot = repoRoot()
        val basicModule = File(repoRoot, "integration-tests/basic")
        val outputDir = File(basicModule, "target/resource-leak-detector/orchestrator-build-failure-it")
        outputDir.deleteRecursively()

        val classpath = orchestratorClasspath(repoRoot)
        val process =
            ProcessBuilder(
                javaExecutable(),
                // Even though this property is set on the orchestrator's JVM, the
                // orchestrator passes an empty override down to each sub-process
                // so the library's AttributionRunner never triggers per-run failure.
                "-Dresource.leak.detector.build.failure.resource.types=memory",
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

        // Both runs ran to completion (filenames carry an ISO-8601 suffix).
        val rawReport1 =
            outputDir
                .listFiles { _, name -> name.startsWith("raw-report-1-") && name.endsWith(".json") }
                ?.firstOrNull()
        val rawReport2 =
            outputDir
                .listFiles { _, name -> name.startsWith("raw-report-2-") && name.endsWith(".json") }
                ?.firstOrNull()
        val finalReport =
            outputDir
                .listFiles { _, name -> name.startsWith("leak-summary-") && name.endsWith(".txt") }
                ?.firstOrNull()
        assertTrue(rawReport1?.isFile == true, "expected raw-report-1-*.json (run 1 must complete)")
        assertTrue(rawReport2?.isFile == true, "expected raw-report-2-*.json (run 2 must complete)")
        assertTrue(finalReport?.isFile == true, "expected leak-summary-*.txt")

        // Neither sub-process nor the orchestrator emitted "Build failure triggered".
        // The library's per-run trigger was suppressed by the empty override; the
        // orchestrator does not impose its own build-failure decision.
        assertFalse(
            output.contains("Build failure triggered"),
            "expected NO 'Build failure triggered' line; got:\n$output",
        )

        // The orchestrator does not propagate sub-process failures, but for our own
        // ITs we assert that no sub-process failed — we control the suite and a
        // BUILD FAILURE here would indicate a real regression in our test scaffolding.
        assertFalse(
            output.contains("BUILD FAILURE"),
            "expected NO 'BUILD FAILURE' in sub-process output; got tail:\n${output.takeLast(4000)}",
        )

        // Orchestrator exits 0.
        assertEquals(0, process.exitValue(), "orchestrator should exit 0; output:\n$output")
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
