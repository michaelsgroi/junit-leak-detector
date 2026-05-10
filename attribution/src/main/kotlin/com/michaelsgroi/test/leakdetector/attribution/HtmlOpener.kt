package com.michaelsgroi.test.leakdetector.attribution

import java.io.File

/**
 * Best-effort: open the HTML report in the user's default viewer.
 * Silent on failure — never fail the run because the OS opener didn't work.
 *
 * Set the JUNIT_LEAK_DETECTOR_NO_OPEN env var (any non-empty value) to suppress.
 */
object HtmlOpener {
    fun open(htmlFile: File) {
        if (!htmlFile.isFile) return
        if (!System.getenv("JUNIT_LEAK_DETECTOR_NO_OPEN").isNullOrEmpty()) return
        val osName = System.getProperty("os.name", "").lowercase()
        val opener =
            when {
                osName.contains("mac") -> "open"
                osName.contains("win") -> "explorer"
                else -> "xdg-open"
            }
        try {
            ProcessBuilder(opener, htmlFile.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            // Nothing to do — the user can open the file manually.
        }
    }
}
