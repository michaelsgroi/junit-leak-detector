package com.salesforce.test.extensions.resourceleak

import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.util.UUID

class RawReportWriter(
    private val outputFile: File,
    private val runId: String = UUID.randomUUID().toString()
) {
    private var writer: BufferedWriter? = null

    fun open(startedAt: Instant, monitors: List<String>, snapshotGranularity: SnapshotGranularity) {
        outputFile.parentFile?.mkdirs()
        val w = outputFile.bufferedWriter()
        writer = w
        w.appendLine(
            RawReportJson.encodeHeader(
                runId = runId,
                startedAt = startedAt.toString(),
                monitors = monitors,
                snapshotGranularity = snapshotGranularity.configValue
            )
        )
        w.flush()
    }

    fun appendSnapshot(snapshot: Snapshot) {
        val w = writer ?: return
        w.appendLine(RawReportJson.encodeSnapshot(snapshot))
        w.flush()
    }

    fun closeWith(finishedAt: Instant, lifecycles: Map<TestClassName, TestClassLifecycle>) {
        val w = writer ?: return
        w.appendLine(RawReportJson.encodeFooter(finishedAt.toString(), lifecycles))
        w.flush()
        w.close()
        writer = null
    }
}
