package com.michaelsgroi.test.leakdetector.attribution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AttributionTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun t(seconds: Long) = t0.plusSeconds(seconds)

    private fun snapshot(
        kind: String,
        ts: Instant,
        testClass: String? = null,
        ports: Set<Int> = emptySet(),
        memory: Long? = null,
    ) = RawSnapshot(
        kind = kind,
        timestamp = ts,
        testClass = testClass,
        testMethod = null,
        discrete = if (ports.isEmpty()) emptyMap() else mapOf("ports" to ports.map { DiscreteResource.Port(it) }),
        numeric = if (memory != null) mapOf("memory" to NumericMeasurement(memory, ts)) else emptyMap(),
    )

    private fun reportWith(
        snapshots: List<RawSnapshot>,
        lifecycles: List<TestClassLifecycleRecord>,
    ) = RawReport(
        header = RawReportHeader("r", t0, listOf("ports"), "class"),
        snapshots = snapshots,
        footer = RawReportFooter(snapshots.last().timestamp, lifecycles),
    )

    @Test
    fun `single-run attribution narrows candidate to class whose interval intersects detection window`() {
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0)),
                snapshot("BEFORE_ALL", t(1), testClass = "com.A"),
                snapshot("AFTER_ALL", t(2), testClass = "com.A"),
                snapshot("BEFORE_ALL", t(3), testClass = "com.B"),
                snapshot("AFTER_ALL", t(4), testClass = "com.B", ports = setOf(8080)),
                snapshot("FINAL", t(5), ports = setOf(8080)),
            )
        val lifecycles =
            listOf(
                TestClassLifecycleRecord("com.A", t(1), t(2)),
                TestClassLifecycleRecord("com.B", t(3), t(4)),
            )
        val result = Attribution.attributeSingleRun(reportWith(snapshots, lifecycles))

        assertEquals(1, result.discreteLeaks.size)
        val leak = result.discreteLeaks.single()
        assertEquals("ports", leak.resourceType)
        assertEquals("8080", leak.resourceKey)
        // detection window is [t(3), t(4)] — last absent at BEFORE_ALL B, first present at AFTER_ALL B.
        assertEquals(t(3), leak.detectionWindow.lastAbsent)
        assertEquals(t(4), leak.detectionWindow.firstPresent)
        assertEquals(listOf("com.B"), leak.candidateSet.map { it.testClass })
        assertFalse(leak.emptyCandidateSetDefect)
    }

    @Test
    fun `class that ended before window is excluded from candidate set`() {
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0)),
                snapshot("BEFORE_ALL", t(1), testClass = "com.A"),
                snapshot("AFTER_ALL", t(2), testClass = "com.A"),
                snapshot("BEFORE_ALL", t(10), testClass = "com.B"),
                snapshot("AFTER_ALL", t(11), testClass = "com.B", ports = setOf(8080)),
                snapshot("FINAL", t(12), ports = setOf(8080)),
            )
        val lifecycles =
            listOf(
                TestClassLifecycleRecord("com.A", t(1), t(2)),
                TestClassLifecycleRecord("com.B", t(10), t(11)),
            )
        val result = Attribution.attributeSingleRun(reportWith(snapshots, lifecycles))
        val leak = result.discreteLeaks.single()
        assertEquals(listOf("com.B"), leak.candidateSet.map { it.testClass })
    }

    @Test
    fun `class that started after window is excluded from candidate set`() {
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0)),
                snapshot("BEFORE_ALL", t(1), testClass = "com.A"),
                snapshot("AFTER_ALL", t(2), testClass = "com.A", ports = setOf(8080)),
                snapshot("BEFORE_ALL", t(5), testClass = "com.B"),
                snapshot("AFTER_ALL", t(6), testClass = "com.B"),
                snapshot("FINAL", t(7), ports = setOf(8080)),
            )
        val lifecycles =
            listOf(
                TestClassLifecycleRecord("com.A", t(1), t(2)),
                TestClassLifecycleRecord("com.B", t(5), t(6)),
            )
        val result = Attribution.attributeSingleRun(reportWith(snapshots, lifecycles))
        val leak = result.discreteLeaks.single()
        assertEquals(listOf("com.A"), leak.candidateSet.map { it.testClass })
    }

    @Test
    fun `baseline resources are excluded from leak detection`() {
        val baseline =
            RawSnapshot(
                "BASELINE",
                t(0),
                null,
                null,
                discrete = mapOf("ports" to listOf(DiscreteResource.Port(7000))),
                numeric = emptyMap(),
            )
        val final =
            RawSnapshot(
                "FINAL",
                t(5),
                null,
                null,
                discrete = mapOf("ports" to listOf(DiscreteResource.Port(7000))),
                numeric = emptyMap(),
            )
        val result = Attribution.attributeSingleRun(reportWith(listOf(baseline, final), emptyList()))
        assertTrue(result.discreteLeaks.isEmpty())
    }

    @Test
    fun `defect flagged when candidate set is empty for a discrete leak`() {
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0)),
                // No lifecycles between baseline and FINAL - leak appears in FINAL only
                snapshot("FINAL", t(5), ports = setOf(8080)),
            )
        val result = Attribution.attributeSingleRun(reportWith(snapshots, emptyList()))
        val leak = result.discreteLeaks.single()
        assertTrue(leak.emptyCandidateSetDefect)
        assertTrue(leak.candidateSet.isEmpty())
    }

    @Test
    fun `memory leak detected when growth exceeds threshold and attributed to crossing class`() {
        val mb = 1_048_576L
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0), memory = 100 * mb),
                snapshot("BEFORE_ALL", t(1), testClass = "com.A", memory = 100 * mb),
                snapshot("AFTER_ALL", t(2), testClass = "com.A", memory = 100 * mb),
                snapshot("BEFORE_ALL", t(3), testClass = "com.B", memory = 100 * mb),
                snapshot("AFTER_ALL", t(4), testClass = "com.B", memory = 500 * mb),
                snapshot("FINAL", t(5), memory = 500 * mb),
            )
        val lifecycles =
            listOf(
                TestClassLifecycleRecord("com.A", t(1), t(2)),
                TestClassLifecycleRecord("com.B", t(3), t(4)),
            )
        val result =
            Attribution.attributeSingleRun(
                reportWith(snapshots, lifecycles),
                memoryGrowthThresholdBytes = 100 * mb,
            )
        val mem = result.memoryLeaks.single()
        assertEquals(400 * mb, mem.growthBytes)
        assertEquals(listOf("com.B"), mem.candidateSet.map { it.testClass })
    }

    @Test
    fun `cross-run intersection narrows candidate set`() {
        val window = DetectionWindow(t(0), t(1))
        val a =
            DiscreteLeak(
                "ports",
                "8080",
                "8080",
                window,
                listOf(CandidateClass("com.A", t(0), t(1)), CandidateClass("com.B", t(0), t(1))),
                false,
            )
        val b =
            DiscreteLeak(
                "ports",
                "8080",
                "8080",
                window,
                listOf(CandidateClass("com.B", t(0), t(1)), CandidateClass("com.C", t(0), t(1))),
                false,
            )

        val intersected =
            Attribution.intersectAcrossRuns(
                FinalReport(listOf(a), emptyList()),
                FinalReport(listOf(b), emptyList()),
            )
        val leak = intersected.discreteLeaks.single()
        assertEquals(listOf("com.B"), leak.candidateSet.map { it.testClass })
        assertFalse(leak.emptyCandidateSetDefect)
    }

    @Test
    fun `cross-run intersection falls back to union when intersection empty and flags defect`() {
        val window = DetectionWindow(t(0), t(1))
        val a =
            DiscreteLeak(
                "ports",
                "8080",
                "8080",
                window,
                listOf(CandidateClass("com.A", t(0), t(1))),
                false,
            )
        val b =
            DiscreteLeak(
                "ports",
                "8080",
                "8080",
                window,
                listOf(CandidateClass("com.B", t(0), t(1))),
                false,
            )

        val merged =
            Attribution
                .intersectAcrossRuns(
                    FinalReport(listOf(a), emptyList()),
                    FinalReport(listOf(b), emptyList()),
                ).discreteLeaks
                .single()
        assertEquals(setOf("com.A", "com.B"), merged.candidateSet.map { it.testClass }.toSet())
        assertTrue(merged.emptyCandidateSetDefect)
    }

    @Test
    fun `cross-run drops leaks not present in both runs`() {
        val window = DetectionWindow(t(0), t(1))
        val onlyInRun1 =
            DiscreteLeak(
                "ports",
                "8080",
                "8080",
                window,
                listOf(CandidateClass("com.A", t(0), t(1))),
                false,
            )
        val merged =
            Attribution.intersectAcrossRuns(
                FinalReport(listOf(onlyInRun1), emptyList()),
                FinalReport(emptyList(), emptyList()),
            )
        assertTrue(merged.discreteLeaks.isEmpty())
    }

    @Test
    fun `lastAbsent before any class is the baseline timestamp`() {
        val snapshots =
            listOf(
                snapshot("BASELINE", t(0)),
                snapshot("BEFORE_ALL", t(1), testClass = "com.A", ports = setOf(8080)),
                snapshot("AFTER_ALL", t(2), testClass = "com.A", ports = setOf(8080)),
                snapshot("FINAL", t(3), ports = setOf(8080)),
            )
        val lifecycles = listOf(TestClassLifecycleRecord("com.A", t(1), t(2)))
        val leak = Attribution.attributeSingleRun(reportWith(snapshots, lifecycles)).discreteLeaks.single()
        assertEquals(t(0), leak.detectionWindow.lastAbsent)
        assertEquals(t(1), leak.detectionWindow.firstPresent)
    }

    @Test
    fun `single-run attribution requires baseline and final snapshots`() {
        val ex =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                Attribution.attributeSingleRun(
                    RawReport(
                        header = RawReportHeader("r", t0, emptyList(), "class"),
                        snapshots = emptyList(),
                        footer = RawReportFooter(t0, emptyList()),
                    ),
                )
            }
        assertTrue(ex.message!!.contains("BASELINE"))
        assertNull(null) // satisfies unused import warning safety
    }
}
