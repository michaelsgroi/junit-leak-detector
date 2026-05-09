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
                renderCandidates(sb, leak.candidateSet, leak.emptyCandidateSetDefect)
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
            renderCandidates(sb, leak.candidateSet, leak.emptyCandidateSetDefect, indent = "  ")
        }

        return sb.toString()
    }

    private fun renderCandidates(
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
