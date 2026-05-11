package com.michaelsgroi.test.leakdetector.attribution

import java.time.Instant

data class DetectionWindow(
    val lastAbsent: Instant?,
    val firstPresent: Instant,
)

data class CandidateClass(
    val testClass: String,
    val start: Instant,
    val end: Instant,
)

data class DiscreteLeak(
    val resourceType: String,
    val resourceKey: String,
    val displayValue: String,
    val detectionWindow: DetectionWindow,
    val candidateSet: List<CandidateClass>,
    val emptyCandidateSetDefect: Boolean,
)

data class MemoryLeak(
    /** Class blamed for the growth: the one between whose BEFORE_ALL and AFTER_ALL snapshots
     *  the growth exceeded the threshold. */
    val testClass: String,
    val classStart: Instant,
    val classEnd: Instant,
    val startBytes: Long,
    val endBytes: Long,
    val growthBytes: Long,
    val thresholdBytes: Long,
)

data class FinalReport(
    val discreteLeaks: List<DiscreteLeak>,
    val memoryLeaks: List<MemoryLeak>,
    /** Resource types that were monitored during the run (e.g., "ports", "threads"). */
    val monitoredTypes: List<String> = emptyList(),
)

object Attribution {
    fun attributeSingleRun(
        report: RawReport,
        memoryGrowthThresholdBytes: Long = 0L,
    ): FinalReport {
        val baseline =
            report.snapshots.firstOrNull { it.kind == "BASELINE" }
                ?: error("Raw report has no BASELINE snapshot")
        val final =
            report.snapshots.lastOrNull { it.kind == "FINAL" }
                ?: error("Raw report has no FINAL snapshot")
        val lifecycles = report.footer.lifecycles

        val discrete = computeDiscreteLeaks(report.snapshots, baseline, final, lifecycles)
        val memory = computePerClassMemoryLeaks(report.snapshots, lifecycles, memoryGrowthThresholdBytes)
        return FinalReport(discrete, memory, monitoredTypes = report.header.monitors)
    }

    private fun computeDiscreteLeaks(
        snapshots: List<RawSnapshot>,
        baseline: RawSnapshot,
        final: RawSnapshot,
        lifecycles: List<TestClassLifecycleRecord>,
    ): List<DiscreteLeak> {
        val results = mutableListOf<DiscreteLeak>()
        val resourceTypes = baseline.discrete.keys + final.discrete.keys
        for (type in resourceTypes) {
            val baselineKeys = baseline.discrete[type].orEmpty().associateBy { it.key }
            val finalResources = final.discrete[type].orEmpty()
            for (resource in finalResources) {
                if (resource.key in baselineKeys) continue
                val window = detectionWindow(snapshots, type, resource.key, baseline.timestamp)
                val candidates = candidateSet(window, lifecycles)
                results +=
                    DiscreteLeak(
                        resourceType = type,
                        resourceKey = resource.key,
                        displayValue = displayValue(resource),
                        detectionWindow = window,
                        candidateSet = candidates,
                        emptyCandidateSetDefect = candidates.isEmpty(),
                    )
            }
        }
        return results
    }

    /**
     * Per-class memory attribution: for each test class, find its BEFORE_ALL and AFTER_ALL
     * snapshots and compute the heap delta. Classes whose net growth exceeds [thresholdBytes]
     * are reported as memory leaks attributed to that specific class.
     */
    private fun computePerClassMemoryLeaks(
        snapshots: List<RawSnapshot>,
        lifecycles: List<TestClassLifecycleRecord>,
        thresholdBytes: Long,
    ): List<MemoryLeak> {
        val byClass = lifecycles.associateBy { it.testClass }
        // Pair each class with its BEFORE_ALL/AFTER_ALL snapshots.
        val befores = mutableMapOf<String, RawSnapshot>()
        val afters = mutableMapOf<String, RawSnapshot>()
        for (snap in snapshots) {
            val cls = snap.testClass ?: continue
            when (snap.kind) {
                "BEFORE_ALL" -> befores[cls] = snap
                "AFTER_ALL" -> afters[cls] = snap
            }
        }
        val out = mutableListOf<MemoryLeak>()
        for ((cls, lc) in byClass) {
            val before = befores[cls]?.numeric?.get("memory") ?: continue
            val after = afters[cls]?.numeric?.get("memory") ?: continue
            val growth = after.value - before.value
            if (growth <= thresholdBytes) continue
            out +=
                MemoryLeak(
                    testClass = cls,
                    classStart = lc.start,
                    classEnd = lc.end,
                    startBytes = before.value,
                    endBytes = after.value,
                    growthBytes = growth,
                    thresholdBytes = thresholdBytes,
                )
        }
        // Sort largest-growth first so the most egregious offenders surface at the top.
        return out.sortedByDescending { it.growthBytes }
    }

    private fun detectionWindow(
        snapshots: List<RawSnapshot>,
        resourceType: String,
        resourceKey: String,
        baselineTimestamp: Instant,
    ): DetectionWindow {
        var lastAbsent: Instant? = baselineTimestamp
        for (snap in snapshots) {
            if (snap.kind == "BASELINE") continue
            val present = snap.discrete[resourceType].orEmpty().any { it.key == resourceKey }
            if (present) {
                return DetectionWindow(lastAbsent = lastAbsent, firstPresent = snap.timestamp)
            }
            lastAbsent = snap.timestamp
        }
        // Resource never present in any snapshot - shouldn't happen if final shows it
        return DetectionWindow(lastAbsent = lastAbsent, firstPresent = lastAbsent ?: baselineTimestamp)
    }

    private fun candidateSet(
        window: DetectionWindow,
        lifecycles: List<TestClassLifecycleRecord>,
    ): List<CandidateClass> {
        val firstPresent = window.firstPresent
        val lastAbsent = window.lastAbsent
        return lifecycles
            .filter { lc ->
                // class ended before window: end < lastAbsent → exclude
                val endsBeforeWindow = lastAbsent != null && lc.end.isBefore(lastAbsent)
                // class started after window: start > firstPresent → exclude
                val startsAfterWindow = lc.start.isAfter(firstPresent)
                !endsBeforeWindow && !startsAfterWindow
            }.map { CandidateClass(it.testClass, it.start, it.end) }
    }

    private fun displayValue(resource: DiscreteResource): String =
        when (resource) {
            is DiscreteResource.Port -> resource.port.toString()
            is DiscreteResource.ThreadResource -> "${resource.name} (ID: ${resource.id})"
            is DiscreteResource.Simple -> resource.value
        }
}
