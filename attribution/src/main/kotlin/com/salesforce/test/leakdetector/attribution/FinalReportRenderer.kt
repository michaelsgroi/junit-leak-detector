package com.salesforce.test.leakdetector.attribution

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
            }
        }

        for (leak in report.memoryLeaks) {
            sb.appendLine()
            sb.appendLine("Memory Leaks:")
            sb.appendLine("  - Baseline: ${leak.baselineBytes / BYTES_PER_MB} MB")
            sb.appendLine("  - Final:    ${leak.finalBytes / BYTES_PER_MB} MB")
            sb.appendLine("  - Increase: ${leak.growthBytes / BYTES_PER_MB} MB")
            sb
                .append("  - Threshold-cross window: [")
                .append(leak.detectionWindow.lastAbsent ?: "n/a")
                .append(", ")
                .append(leak.detectionWindow.firstPresent)
                .appendLine("]")
            renderCandidatesText(sb, leak.candidateSet, leak.emptyCandidateSetDefect, indent = "  ")
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
        sb.appendLine("  </style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("  <h1>Resource Leak Detector Report</h1>")

        if (report.discreteLeaks.isEmpty() && report.memoryLeaks.isEmpty()) {
            sb.appendLine("  <p class=\"none\">No leaks detected.</p>")
        }

        val byType = report.discreteLeaks.groupBy { it.resourceType }
        for ((type, leaks) in byType) {
            sb.append("  <h2>").append(htmlEscape(headerFor(type).removeSuffix(":"))).appendLine("</h2>")
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
                    .appendLine("</td>")
                sb.appendLine("    </tr>")
            }
            sb.appendLine("  </table>")
        }

        for (leak in report.memoryLeaks) {
            sb.appendLine("  <h2>Memory Leaks</h2>")
            sb.appendLine("  <table>")
            sb.appendLine(
                "    <tr><th>Baseline</th><th>Final</th><th>Increase</th>" +
                    "<th>Threshold-cross Window</th><th>Candidate Set</th></tr>",
            )
            sb.appendLine("    <tr class=\"leak-row\">")
            sb.append("      <td class=\"mono\">").append(leak.baselineBytes / BYTES_PER_MB).appendLine(" MB</td>")
            sb.append("      <td class=\"mono\">").append(leak.finalBytes / BYTES_PER_MB).appendLine(" MB</td>")
            sb.append("      <td class=\"mono\">").append(leak.growthBytes / BYTES_PER_MB).appendLine(" MB</td>")
            sb
                .append("      <td class=\"mono\">[")
                .append(htmlEscape(leak.detectionWindow.lastAbsent?.toString() ?: "n/a"))
                .append(", ")
                .append(htmlEscape(leak.detectionWindow.firstPresent.toString()))
                .appendLine("]</td>")
            sb
                .append("      <td class=\"candidates\">")
                .append(renderCandidatesHtml(leak.candidateSet, leak.emptyCandidateSetDefect))
                .appendLine("</td>")
            sb.appendLine("    </tr>")
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

    private fun headerFor(type: String): String =
        when (type) {
            "ports" -> "Network Port Leaks:"
            "threads" -> "Thread Leaks:"
            "systemprops" -> "System Property Leaks:"
            "envvars" -> "Environment Variable Leaks:"
            "ddbtables" -> "DynamoDB Table Leaks:"
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
}
