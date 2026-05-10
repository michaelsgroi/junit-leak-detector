package com.michaelsgroi.test.leakdetector.attribution

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
        // Summary is always rendered; with no monitored types every count cell is "disabled".
        assertTrue(html.contains("<h2>Summary</h2>"))
        assertTrue(html.contains(">disabled</td>"))
        // No row shows a numeric count.
        assertTrue(!Regex("class=\"mono\">[0-9]+</td>").containsMatchIn(html))
        assertTrue(Regex("<strong>Total</strong></td>\\s*<td[^>]*><strong>0</strong>").containsMatchIn(html))
    }

    @Test
    fun `renderHtml summary section shows all known leak types with counts and status`() {
        val window = DetectionWindow(null, t0)
        val report =
            FinalReport(
                discreteLeaks =
                    listOf(
                        DiscreteLeak("ports", "8080", "8080", window, listOf(CandidateClass("com.A", t0, t0)), false),
                        DiscreteLeak("ports", "8081", "8081", window, listOf(CandidateClass("com.A", t0, t0)), false),
                        DiscreteLeak("threads", "x#1", "x (ID: 1)", window, listOf(CandidateClass("com.B", t0, t0)), false),
                    ),
                memoryLeaks =
                    listOf(
                        MemoryLeak(0L, 1L, 1L, 0L, window, listOf(CandidateClass("com.C", t0, t0)), false),
                    ),
                // ports, threads, memory monitored; others not monitored.
                monitoredTypes = listOf("ports", "threads", "memory"),
            )
        val html = FinalReportRenderer.renderHtml(report)
        assertTrue(html.contains("<h2>Summary</h2>"))

        // Monitored types: count cell shows the actual leak count (including 0).
        assertTrue(Regex("Network Port Leaks</td><td[^>]*>2</td>").containsMatchIn(html))
        assertTrue(Regex("Thread Leaks</td><td[^>]*>1</td>").containsMatchIn(html))
        assertTrue(Regex("Memory Leaks</td><td[^>]*>1</td>").containsMatchIn(html))

        // Not-monitored types: count cell shows "disabled".
        assertTrue(Regex("System Property Leaks</td><td[^>]*>disabled</td>").containsMatchIn(html))
        assertTrue(Regex("Environment Variable Leaks</td><td[^>]*>disabled</td>").containsMatchIn(html))
        assertTrue(Regex("DynamoDB Table Leaks</td><td[^>]*>disabled</td>").containsMatchIn(html))

        // Total counts only monitored types.
        assertTrue(Regex("<strong>Total</strong></td>\\s*<td[^>]*><strong>4</strong>").containsMatchIn(html))

        // Summary appears before the per-type sections.
        val summaryIdx = html.indexOf("<h2>Summary</h2>")
        val firstTypeIdx = html.indexOf("<h2>Network Port Leaks</h2>")
        assertTrue(summaryIdx in 0 until firstTypeIdx, "summary should appear before per-type sections")
    }

    @Test
    fun `renderHtml summary shows zero for monitored types with no leaks`() {
        val report =
            FinalReport(
                discreteLeaks = emptyList(),
                memoryLeaks = emptyList(),
                monitoredTypes = listOf("ports", "threads"),
            )
        val html = FinalReportRenderer.renderHtml(report)
        assertTrue(Regex("Network Port Leaks</td><td[^>]*>0</td>").containsMatchIn(html))
        assertTrue(Regex("Thread Leaks</td><td[^>]*>0</td>").containsMatchIn(html))
        assertTrue(Regex("System Property Leaks</td><td[^>]*>disabled</td>").containsMatchIn(html))
    }
}
