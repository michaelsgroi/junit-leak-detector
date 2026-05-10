package com.michaelsgroi.test.extensions.resourceleak

import java.time.Instant

enum class SnapshotKind { BASELINE, BEFORE_ALL, BEFORE_EACH, AFTER_EACH, AFTER_ALL, FINAL }

data class Snapshot(
    val kind: SnapshotKind,
    val timestamp: Instant,
    val testClass: String?,
    val testMethod: String?,
    val discrete: Map<ResourceType, Set<ResourceId>>,
    val numeric: Map<String, NumericResourceMeasurement>,
)
