package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.leakdetector.attribution.Attribution
import com.salesforce.test.leakdetector.attribution.FinalReport
import com.salesforce.test.leakdetector.attribution.FinalReportRenderer
import com.salesforce.test.leakdetector.attribution.RawReportReader
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class AttributionRunner(
    private val reportPaths: ReportPaths,
    private val configuration: Configuration = Configuration.instance,
    private val buildFailureAction: () -> Unit = { exitProcess(1) },
) {
    fun runInline() {
        val rawReportFile = reportPaths.rawReport
        if (!rawReportFile.exists()) {
            LOG.warn("Raw report missing at ${rawReportFile.absolutePath}; skipping attribution")
            return
        }

        val rawReport = RawReportReader.read(rawReportFile)
        val thresholdBytes = configuration.memoryGrowthThresholdMb * BYTES_PER_MB
        val finalReport = Attribution.attributeSingleRun(rawReport, memoryGrowthThresholdBytes = thresholdBytes)

        val text = FinalReportRenderer.renderText(finalReport)
        text.lineSequence().forEach { LOG.info(it) }

        val summaryFile = reportPaths.leakSummary
        summaryFile.parentFile?.mkdirs()
        summaryFile.writeText(text)

        checkBuildFailure(finalReport)
    }

    private fun checkBuildFailure(report: FinalReport) {
        val property = configuration.buildFailureResourceTypes
        if (property.isBlank()) return

        val failingTypes =
            property
                .split(",")
                .map { it.trim() }
                .mapNotNull { ResourceType.fromConfigValue(it) }
                .filter { hasLeak(report, it) }

        if (failingTypes.isNotEmpty()) {
            LOG.error(
                "Build failure triggered - leaks detected for resource types: " +
                    failingTypes.joinToString(", ") { it.configValue },
            )
            buildFailureAction()
        }
    }

    private fun hasLeak(
        report: FinalReport,
        type: ResourceType,
    ): Boolean =
        when (type) {
            ResourceType.MEMORY -> report.memoryLeaks.isNotEmpty()
            else -> report.discreteLeaks.any { it.resourceType == type.configValue }
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(AttributionRunner::class.java)
        private const val BYTES_PER_MB = 1_048_576L
    }
}
