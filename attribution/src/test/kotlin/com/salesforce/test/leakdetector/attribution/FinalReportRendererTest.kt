package com.salesforce.test.leakdetector.attribution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FinalReportRendererTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    @Test
    fun `renders discrete leak with detection window and candidate set`() {
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak(
                            resourceType = "ports",
                            resourceKey = "8080",
                            displayValue = "8080",
                            detectionWindow = DetectionWindow(t0, t0.plusSeconds(1)),
                            candidateSet = listOf(CandidateClass("com.A", t0, t0.plusSeconds(1))),
                            emptyCandidateSetDefect = false,
                        ),
                    ),
                memoryLeaks = emptyList(),
            )
        val text = FinalReportRenderer.renderText(report)
        assertTrue(text.contains("Network Port Leaks:"))
        assertTrue(text.contains("Port: 8080"))
        assertTrue(text.contains("Detection Window: ["))
        assertTrue(text.contains("com.A"))
        assertTrue(text.contains("Candidate Set (1 class)"))
    }

    @Test
    fun `renders defect indicator when candidate set was empty`() {
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak(
                            resourceType = "threads",
                            resourceKey = "x#1",
                            displayValue = "x (ID: 1)",
                            detectionWindow = DetectionWindow(null, t0),
                            candidateSet = emptyList(),
                            emptyCandidateSetDefect = true,
                        ),
                    ),
                memoryLeaks = emptyList(),
            )
        val text = FinalReportRenderer.renderText(report)
        assertTrue(text.contains("DEFECT"))
        assertTrue(text.contains("Candidate Set (0 classes)"))
    }

    @Test
    fun `renders memory leak`() {
        val mb = 1_048_576L
        val report =
            FinalReport(
                discreteLeaks = emptyList(),
                memoryLeaks =
                    listOf(
                        MemoryLeak(
                            baselineBytes = 100 * mb,
                            finalBytes = 500 * mb,
                            growthBytes = 400 * mb,
                            thresholdBytes = 100 * mb,
                            detectionWindow = DetectionWindow(t0, t0.plusSeconds(1)),
                            candidateSet = listOf(CandidateClass("com.B", t0, t0.plusSeconds(1))),
                            emptyCandidateSetDefect = false,
                        ),
                    ),
            )
        val text = FinalReportRenderer.renderText(report)
        assertTrue(text.contains("Memory Leaks:"))
        assertTrue(text.contains("Baseline: 100 MB"))
        assertTrue(text.contains("Increase: 400 MB"))
        assertTrue(text.contains("com.B"))
    }

    @Test
    fun `renderHtml emits a self-contained document with the same data`() {
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak(
                            resourceType = "ports",
                            resourceKey = "8080",
                            displayValue = "8080",
                            detectionWindow = DetectionWindow(t0, t0.plusSeconds(1)),
                            candidateSet = listOf(CandidateClass("com.A", t0, t0.plusSeconds(1))),
                            emptyCandidateSetDefect = false,
                        ),
                    ),
                memoryLeaks = emptyList(),
            )
        val html = FinalReportRenderer.renderHtml(report)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Resource Leak Detector Report</title>"))
        assertTrue(html.contains("<h2>Network Port Leaks</h2>"))
        assertTrue(html.contains("8080"))
        assertTrue(html.contains("com.A"))
        assertTrue(html.endsWith("</html>\n"))
    }

    @Test
    fun `renderHtml escapes HTML metacharacters`() {
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak(
                            resourceType = "systemprops",
                            resourceKey = "<script>alert(1)</script>",
                            displayValue = "<script>alert(1)</script>",
                            detectionWindow = DetectionWindow(null, t0),
                            candidateSet = listOf(CandidateClass("com.\"Tricky\"", t0, t0)),
                            emptyCandidateSetDefect = false,
                        ),
                    ),
                memoryLeaks = emptyList(),
            )
        val html = FinalReportRenderer.renderHtml(report)
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("com.&quot;Tricky&quot;"))
    }

    @Test
    fun `renderHtml shows defect indicator when candidate set was empty`() {
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak(
                            resourceType = "threads",
                            resourceKey = "x#1",
                            displayValue = "x (ID: 1)",
                            detectionWindow = DetectionWindow(null, t0),
                            candidateSet = emptyList(),
                            emptyCandidateSetDefect = true,
                        ),
                    ),
                memoryLeaks = emptyList(),
            )
        val html = FinalReportRenderer.renderHtml(report)
        assertTrue(html.contains("DEFECT"))
    }

    @Test
    fun `renderHtml says no leaks detected when both lists are empty`() {
        val html = FinalReportRenderer.renderHtml(FinalReport(emptyList(), emptyList()))
        assertTrue(html.contains("No leaks detected"))
    }
}
