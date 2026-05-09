package com.salesforce.test.extensions.resourceleak

import java.time.Instant
import kotlin.reflect.KClass

enum class SnapshotKind { BASELINE, BEFORE_ALL, BEFORE_EACH, AFTER_EACH, AFTER_ALL, FINAL }

data class Snapshot(
    val kind: SnapshotKind,
    val timestamp: Instant,
    val testClass: String?,
    val testMethod: String?,
    val discrete: Map<KClass<out ResourceId>, Set<ResourceId>>,
    val numeric: Map<String, NumericResourceMeasurement>,
)
