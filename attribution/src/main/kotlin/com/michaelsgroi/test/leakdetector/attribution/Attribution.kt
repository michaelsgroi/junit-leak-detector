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
    val baselineBytes: Long,
    val finalBytes: Long,
    val growthBytes: Long,
    val thresholdBytes: Long,
    val detectionWindow: DetectionWindow,
    val candidateSet: List<CandidateClass>,
    val emptyCandidateSetDefect: Boolean,
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
        val memory = computeMemoryLeaks(report.snapshots, baseline, final, lifecycles, memoryGrowthThresholdBytes)
        return FinalReport(discrete, memory, monitoredTypes = report.header.monitors)
    }

    fun intersectAcrossRuns(
        run1: FinalReport,
        run2: FinalReport,
    ): FinalReport {
        val discrete = intersectDiscrete(run1.discreteLeaks, run2.discreteLeaks)
        val memory = intersectMemory(run1.memoryLeaks, run2.memoryLeaks)
        // Union the monitored types: a type counts as "monitored in this investigation"
        // if either run was monitoring it. In practice the orchestrator drives both runs
        // with the same config, so the union and intersection match.
        val monitored = (run1.monitoredTypes + run2.monitoredTypes).distinct()
        return FinalReport(discrete, memory, monitoredTypes = monitored)
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

    private fun computeMemoryLeaks(
        snapshots: List<RawSnapshot>,
        baseline: RawSnapshot,
        final: RawSnapshot,
        lifecycles: List<TestClassLifecycleRecord>,
        thresholdBytes: Long,
    ): List<MemoryLeak> {
        val baselineMem = baseline.numeric["memory"] ?: return emptyList()
        val finalMem = final.numeric["memory"] ?: return emptyList()
        val growth = finalMem.value - baselineMem.value
        if (growth <= thresholdBytes) return emptyList()
        val window = memoryDetectionWindow(snapshots, baselineMem.value, thresholdBytes, baseline.timestamp)
        val candidates = candidateSet(window, lifecycles)
        return listOf(
            MemoryLeak(
                baselineBytes = baselineMem.value,
                finalBytes = finalMem.value,
                growthBytes = growth,
                thresholdBytes = thresholdBytes,
                detectionWindow = window,
                candidateSet = candidates,
                emptyCandidateSetDefect = candidates.isEmpty(),
            ),
        )
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

    private fun memoryDetectionWindow(
        snapshots: List<RawSnapshot>,
        baselineBytes: Long,
        thresholdBytes: Long,
        baselineTimestamp: Instant,
    ): DetectionWindow {
        var lastAbsent: Instant? = baselineTimestamp
        for (snap in snapshots) {
            if (snap.kind == "BASELINE") continue
            val measurement = snap.numeric["memory"] ?: continue
            if (measurement.value - baselineBytes > thresholdBytes) {
                return DetectionWindow(lastAbsent = lastAbsent, firstPresent = snap.timestamp)
            }
            lastAbsent = snap.timestamp
        }
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

    private fun intersectDiscrete(
        run1: List<DiscreteLeak>,
        run2: List<DiscreteLeak>,
    ): List<DiscreteLeak> {
        val run2Index = run2.associateBy { it.resourceType to it.resourceKey }
        return run1.mapNotNull { leak ->
            val other = run2Index[leak.resourceType to leak.resourceKey] ?: return@mapNotNull null
            mergeCandidateSet(leak, other)
        }
    }

    private fun mergeCandidateSet(
        a: DiscreteLeak,
        b: DiscreteLeak,
    ): DiscreteLeak {
        val intersected = intersectCandidates(a.candidateSet, b.candidateSet)
        val (final, defect) =
            if (intersected.isNotEmpty()) {
                intersected to false
            } else {
                // Empty intersection: fall back to union and flag as defect for diagnosis.
                unionCandidates(a.candidateSet, b.candidateSet) to true
            }
        return a.copy(candidateSet = final, emptyCandidateSetDefect = defect)
    }

    private fun intersectMemory(
        run1: List<MemoryLeak>,
        run2: List<MemoryLeak>,
    ): List<MemoryLeak> {
        if (run1.isEmpty() || run2.isEmpty()) return emptyList()
        val a = run1.first()
        val b = run2.first()
        val intersected = intersectCandidates(a.candidateSet, b.candidateSet)
        val (final, defect) =
            if (intersected.isNotEmpty()) intersected to false else unionCandidates(a.candidateSet, b.candidateSet) to true
        return listOf(a.copy(candidateSet = final, emptyCandidateSetDefect = defect))
    }

    private fun intersectCandidates(
        a: List<CandidateClass>,
        b: List<CandidateClass>,
    ): List<CandidateClass> {
        val bNames = b.map { it.testClass }.toSet()
        return a.filter { it.testClass in bNames }
    }

    private fun unionCandidates(
        a: List<CandidateClass>,
        b: List<CandidateClass>,
    ): List<CandidateClass> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<CandidateClass>()
        for (c in a + b) {
            if (seen.add(c.testClass)) result += c
        }
        return result
    }
}
