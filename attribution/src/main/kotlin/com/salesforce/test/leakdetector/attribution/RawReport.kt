package com.salesforce.test.leakdetector.attribution

import java.time.Instant

data class RawReportHeader(
    val runId: String,
    val startedAt: Instant,
    val monitors: List<String>,
    val snapshotGranularity: String,
)

data class RawReportFooter(
    val finishedAt: Instant,
    val lifecycles: List<TestClassLifecycleRecord>,
)

data class TestClassLifecycleRecord(
    val testClass: String,
    val start: Instant,
    val end: Instant,
)

data class RawSnapshot(
    val kind: String,
    val timestamp: Instant,
    val testClass: String?,
    val testMethod: String?,
    val discrete: Map<String, List<DiscreteResource>>,
    val numeric: Map<String, NumericMeasurement>,
)

sealed class DiscreteResource {
    abstract val key: String

    data class Simple(
        val value: String,
    ) : DiscreteResource() {
        override val key: String get() = value
    }

    data class ThreadResource(
        val name: String,
        val id: String,
    ) : DiscreteResource() {
        override val key: String get() = "$name#$id"
    }

    data class Port(
        val port: Int,
    ) : DiscreteResource() {
        override val key: String get() = port.toString()
    }
}

data class NumericMeasurement(
    val value: Long,
    val timestamp: Instant,
)

data class RawReport(
    val header: RawReportHeader,
    val snapshots: List<RawSnapshot>,
    val footer: RawReportFooter,
)
