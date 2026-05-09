package com.salesforce.test.extensions.resourceleak.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verifies the standalone attribution CLI produces the same output as the inline
 * AttributionRunner (which the library invokes at testPlanExecutionFinished). We
 * do this by running the basic suite once to produce raw-report.json + leak-report.txt,
 * then running the CLI against the same raw-report.json and diffing.
 */
class CliScenarioIT {
    @Test
    fun `cli output matches inline leak-report dot txt for the basic suite`() {
        // First, run the basic integration-tests module to produce raw-report.json + leak-report.txt.
        val basic = MavenScenarioRunner.run(module = "integration-tests/basic")
        assertTrue(basic.succeeded, "basic suite should succeed; output tail:\n${basic.output.takeLast(2000)}")

        val outputDir = File(basic.moduleDirectory, "target/resource-leak-detector")
        val rawReport = File(outputDir, "raw-report.json")
        val inlineReport = File(outputDir, "leak-report.txt")
        assertTrue(rawReport.isFile, "expected raw report at ${rawReport.absolutePath}")
        assertTrue(inlineReport.isFile, "expected inline report at ${inlineReport.absolutePath}")

        // Now run the CLI against the same raw report. Because the attribution module
        // ships only as a regular jar (no fat-jar), invoke via -cp with the attribution
        // jar plus its single transitive dep (kotlin-stdlib).
        val classpath = cliClasspath()
        val cliOutFile = File(outputDir, "leak-report-cli.txt")
        val process =
            ProcessBuilder(
                javaExecutable(),
                "-cp",
                classpath,
                "com.salesforce.test.leakdetector.attribution.cli.AttributionCli",
                rawReport.absolutePath,
                "-o",
                cliOutFile.absolutePath,
                "--memory-threshold-mb",
                "5", // matches the basic suite's resource-leak-detector.properties
            ).redirectErrorStream(true)
                .start()
        val cliOutput = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(2, TimeUnit.MINUTES)
        check(finished) { "CLI invocation timed out" }
        assertEquals(0, process.exitValue(), "CLI exit non-zero; output:\n$cliOutput")

        // Compare CLI output to the inline report. They should match line-for-line.
        val cliText = cliOutFile.readText()
        val inlineText = inlineReport.readText()
        assertEquals(
            inlineText.trim().lines(),
            cliText.trim().lines(),
            "CLI output should match inline report",
        )
    }

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
        // Resolve kotlin-stdlib from the local Maven repo using the same version the
        // parent pom pins. This avoids hard-coding a path.
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
