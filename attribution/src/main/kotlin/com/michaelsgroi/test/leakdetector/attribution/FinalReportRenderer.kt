package com.michaelsgroi.test.leakdetector.attribution

object FinalReportRenderer {
    fun renderText(report: FinalReport): String {
        val sb = StringBuilder()
        sb.appendLine("Resource Leak Detector Report")
        sb.appendLine("==============================")

        val byType = report.discreteLeaks.groupBy { it.resourceType }
        for ((type, leaks) in byType) {
            sb.appendLine()
            sb.appendLine(headerFor(type))
            for (leak in leaks) {
                sb
                    .append("  - ")
                    .append(labelFor(type))
                    .append(": ")
                    .appendLine(leak.displayValue)
                sb
                    .append("    Detection Window: [")
                    .append(leak.detectionWindow.lastAbsent ?: "n/a")
                    .append(", ")
                    .append(leak.detectionWindow.firstPresent)
                    .appendLine("]")
                renderCandidatesText(sb, leak.candidateSet, leak.emptyCandidateSetDefect)
                leak.creationStack?.takeIf { it.isNotEmpty() }?.let { stack ->
                    sb.appendLine("    Created at:")
                    for (frame in stack) {
                        sb.append("      at ").appendLine(frame)
                    }
                }
            }
        }

        if (report.memoryLeaks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Memory Leaks:")
            for (leak in report.memoryLeaks) {
                sb.append("  - Class:    ").appendLine(leak.testClass)
                sb.append("    Start:    ").append(leak.startBytes / BYTES_PER_MB).appendLine(" MB")
                sb.append("    End:      ").append(leak.endBytes / BYTES_PER_MB).appendLine(" MB")
                sb.append("    Growth:   ").append(leak.growthBytes / BYTES_PER_MB).appendLine(" MB")
                sb
                    .append("    Lifecycle: [")
                    .append(leak.classStart)
                    .append(", ")
                    .append(leak.classEnd)
                    .appendLine("]")
            }
        }

        return sb.toString()
    }

    fun renderHtml(report: FinalReport): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html lang=\"en\">")
        sb.appendLine("<head>")
        sb.appendLine("  <meta charset=\"utf-8\">")
        sb.appendLine("  <title>Resource Leak Detector Report</title>")
        sb.appendLine("  <style>")
        sb.appendLine("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 2em; color: #222; }")
        sb.appendLine("    h1 { margin-top: 0; }")
        sb.appendLine("    h2 { border-bottom: 1px solid #ccc; padding-bottom: 0.2em; margin-top: 2em; }")
        sb.appendLine("    table { border-collapse: collapse; margin: 0.5em 0; width: 100%; }")
        sb.appendLine("    th, td { padding: 0.4em 0.7em; border: 1px solid #ddd; text-align: left; vertical-align: top; }")
        sb.appendLine("    th { background: #f4f4f4; }")
        sb.appendLine("    code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.9em; }")
        sb.appendLine("    .candidate-set { margin: 0; padding-left: 1.4em; }")
        sb.appendLine("    .defect { color: #b80; font-weight: 600; }")
        sb.appendLine("    .none { color: #2a7; font-style: italic; }")
        sb.appendLine("    .leak-row td { white-space: nowrap; }")
        sb.appendLine("    .leak-row td.candidates { white-space: normal; }")
        sb.appendLine("    .creation-stack { margin-top: 0.4em; }")
        sb.appendLine("    .creation-stack pre { background: #f8f8f8; padding: 0.4em 0.7em; margin: 0.3em 0 0 0; overflow-x: auto; }")
        sb.appendLine("    .creation-stack summary { cursor: pointer; color: #555; font-size: 0.85em; }")
        sb.appendLine("  </style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("  <h1>Resource Leak Detector Report</h1>")

        renderSummary(sb, report)
        if (report.discreteLeaks.isEmpty() && report.memoryLeaks.isEmpty()) {
            sb.appendLine("  <p class=\"none\">No leaks detected.</p>")
        }

        val byType = report.discreteLeaks.groupBy { it.resourceType }
        for ((type, leaks) in byType) {
            val heading = htmlEscape(headerFor(type).removeSuffix(":"))
            sb
                .append("  <h2 id=\"")
                .append(sectionId(type))
                .append("\">")
                .append(heading)
                .appendLine("</h2>")
            sb.appendLine("  <table>")
            sb
                .append("    <tr><th>")
                .append(htmlEscape(labelFor(type)))
                .appendLine("</th><th>Detection Window</th><th>Candidate Set</th></tr>")
            for (leak in leaks) {
                sb.appendLine("    <tr class=\"leak-row\">")
                sb.append("      <td class=\"mono\">").append(htmlEscape(leak.displayValue)).appendLine("</td>")
                sb
                    .append("      <td class=\"mono\">[")
                    .append(htmlEscape(leak.detectionWindow.lastAbsent?.toString() ?: "n/a"))
                    .append(", ")
                    .append(htmlEscape(leak.detectionWindow.firstPresent.toString()))
                    .appendLine("]</td>")
                sb
                    .append("      <td class=\"candidates\">")
                    .append(renderCandidatesHtml(leak.candidateSet, leak.emptyCandidateSetDefect))
                    .append(renderCreationStackHtml(leak.creationStack))
                    .appendLine("</td>")
                sb.appendLine("    </tr>")
            }
            sb.appendLine("  </table>")
        }

        if (report.memoryLeaks.isNotEmpty()) {
            sb.append("  <h2 id=\"").append(sectionId("memory")).appendLine("\">Memory Leaks</h2>")
            sb.appendLine("  <table>")
            sb.appendLine(
                "    <tr><th>Class</th><th>Start</th><th>End</th><th>Growth</th><th>Lifecycle</th></tr>",
            )
            for (leak in report.memoryLeaks) {
                sb.appendLine("    <tr class=\"leak-row\">")
                sb.append("      <td class=\"mono\">").append(htmlEscape(leak.testClass)).appendLine("</td>")
                sb.append("      <td class=\"mono\">").append(leak.startBytes / BYTES_PER_MB).appendLine(" MB</td>")
                sb.append("      <td class=\"mono\">").append(leak.endBytes / BYTES_PER_MB).appendLine(" MB</td>")
                sb.append("      <td class=\"mono\">").append(leak.growthBytes / BYTES_PER_MB).appendLine(" MB</td>")
                sb
                    .append("      <td class=\"mono\">[")
                    .append(htmlEscape(leak.classStart.toString()))
                    .append(", ")
                    .append(htmlEscape(leak.classEnd.toString()))
                    .appendLine("]</td>")
                sb.appendLine("    </tr>")
            }
            sb.appendLine("  </table>")
        }

        sb.appendLine("</body>")
        sb.appendLine("</html>")
        return sb.toString()
    }

    private fun renderCandidatesText(
        sb: StringBuilder,
        candidates: List<CandidateClass>,
        defect: Boolean,
        indent: String = "    ",
    ) {
        if (defect) {
            sb.append(indent).appendLine("DEFECT: candidate set was empty (lifecycle/snapshot data inconsistent)")
        }
        sb.append(indent).appendLine("Candidate Set (${candidates.size} class${if (candidates.size == 1) "" else "es"}):")
        for (c in candidates) {
            sb.append(indent).append("  - ").appendLine(c.testClass)
            sb.append(indent).append("    Started: ").appendLine(c.start)
            sb.append(indent).append("    Ended:   ").appendLine(c.end)
        }
    }

    private fun renderSummary(
        sb: StringBuilder,
        report: FinalReport,
    ) {
        val monitored = report.monitoredTypes.toSet()
        val countsByType = report.discreteLeaks.groupingBy { it.resourceType }.eachCount()

        sb.appendLine("  <h2>Summary</h2>")
        sb.appendLine("  <table>")
        sb.appendLine("    <tr><th>Leak Type</th><th>Count</th></tr>")
        var monitoredTotal = 0
        for (type in ALL_RESOURCE_TYPES) {
            val label = headerFor(type).removeSuffix(":")
            val isMonitored = type in monitored
            val count =
                when {
                    !isMonitored -> null
                    type == "memory" -> report.memoryLeaks.size
                    else -> countsByType[type] ?: 0
                }
            val countCell = count?.toString() ?: "disabled"
            if (count != null) monitoredTotal += count
            val hasSection = count != null && count > 0
            val labelCell =
                if (hasSection) {
                    "<a href=\"#${sectionId(type)}\">${htmlEscape(label)}</a>"
                } else {
                    htmlEscape(label)
                }
            sb
                .append("    <tr><td>")
                .append(labelCell)
                .append("</td><td class=\"mono\">")
                .append(countCell)
                .appendLine("</td></tr>")
        }
        sb
            .append("    <tr><td><strong>Total</strong></td>")
            .append("<td class=\"mono\"><strong>")
            .append(monitoredTotal)
            .appendLine("</strong></td></tr>")
        sb.appendLine("  </table>")
    }

    private fun renderCandidatesHtml(
        candidates: List<CandidateClass>,
        defect: Boolean,
    ): String {
        val sb = StringBuilder()
        if (defect) {
            sb.append("<div class=\"defect\">DEFECT: candidate set was empty</div>")
        }
        sb.append("<ul class=\"candidate-set\">")
        for (c in candidates) {
            sb.append("<li><span class=\"mono\">").append(htmlEscape(c.testClass)).append("</span>")
            sb
                .append(" <small>(")
                .append(htmlEscape(c.start.toString()))
                .append(" — ")
                .append(htmlEscape(c.end.toString()))
                .append(")</small></li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    /**
     * Renders the thread-creation stack inside a collapsible `<details>` block. Hidden
     * by default to keep the report scannable; clicking expands to show the call site
     * that started the thread (from JFR's `jdk.ThreadStart` event).
     */
    private fun renderCreationStackHtml(stack: List<String>?): String {
        if (stack.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<details class=\"creation-stack\"><summary>Creation stack</summary><pre class=\"mono\">")
        for (frame in stack) {
            sb.append(htmlEscape(frame)).append('\n')
        }
        sb.append("</pre></details>")
        return sb.toString()
    }

    private fun htmlEscape(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun sectionId(type: String): String = "leaks-$type"

    private fun headerFor(type: String): String =
        when (type) {
            "ports" -> "Network Port Leaks:"
            "threads" -> "Thread Leaks:"
            "systemprops" -> "System Property Leaks:"
            "envvars" -> "Environment Variable Leaks:"
            "ddbtables" -> "DynamoDB Table Leaks:"
            "memory" -> "Memory Leaks:"
            else -> "$type Leaks:"
        }

    private fun labelFor(type: String): String =
        when (type) {
            "ports" -> "Port"
            "threads" -> "Thread"
            "systemprops" -> "Property"
            "envvars" -> "Variable"
            "ddbtables" -> "Table"
            else -> type
        }

    private const val BYTES_PER_MB = 1_048_576L

    // The full set of resource types the detector knows about, in display order.
    // Used by the Summary table to show every type even when it had no findings or
    // wasn't monitored.
    private val ALL_RESOURCE_TYPES = listOf("ports", "threads", "systemprops", "envvars", "ddbtables", "memory")
}
