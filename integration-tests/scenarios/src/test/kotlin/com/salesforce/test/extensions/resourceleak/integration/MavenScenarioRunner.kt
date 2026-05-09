package com.salesforce.test.extensions.resourceleak.integration

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs an `mvn` sub-build of one of the integration-tests modules and captures the
 * full combined stdout/stderr. Used by the *IT.kt scenario tests to invoke a
 * deliberately-configured build and assert on its output.
 */
object MavenScenarioRunner {
    data class Result(
        val exitCode: Int,
        val output: String,
        val moduleDirectory: File,
    ) {
        val succeeded: Boolean get() = exitCode == 0

        fun containsLine(needle: String): Boolean = output.contains(needle)
    }

    fun run(
        module: String,
        profiles: List<String> = emptyList(),
        extraArgs: List<String> = emptyList(),
    ): Result {
        val repoRoot = repoRoot()
        val moduleDir = File(repoRoot, module)
        require(moduleDir.isDirectory) { "Module directory not found: $moduleDir" }

        // Always build offline against the locally-installed library so we don't refetch.
        val args =
            buildList {
                add(mvnExecutable())
                add("-o")
                add("test")
                if (profiles.isNotEmpty()) add("-P${profiles.joinToString(",")}")
                addAll(extraArgs)
            }

        val process =
            ProcessBuilder(args)
                .directory(moduleDir)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        check(finished) { "mvn invocation timed out for $module" }
        return Result(exitCode = process.exitValue(), output = output, moduleDirectory = moduleDir)
    }

    private fun repoRoot(): File {
        // Tests run from `library/`; the repo root is the parent.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "integration-tests").isDirectory && File(dir, "pom.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root (no integration-tests sibling found)")
    }

    private fun mvnExecutable(): String = System.getenv("MAVEN_BIN") ?: "mvn"
}
