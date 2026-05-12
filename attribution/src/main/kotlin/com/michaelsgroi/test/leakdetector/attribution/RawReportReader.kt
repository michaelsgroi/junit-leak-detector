package com.michaelsgroi.test.leakdetector.attribution

import java.io.BufferedReader
import java.io.File
import java.io.Reader
import java.time.Instant

object RawReportReader {
    fun read(file: File): RawReport = file.bufferedReader().use { read(it) }

    fun read(reader: Reader): RawReport {
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader)
        var header: RawReportHeader? = null
        var footer: RawReportFooter? = null
        val snapshots = mutableListOf<RawSnapshot>()

        buffered
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val obj = parseJson(line).asObject()
                when (obj.string("type")) {
                    "header" -> header = parseHeader(obj)
                    "snapshot" -> snapshots += parseSnapshot(obj)
                    "footer" -> footer = parseFooter(obj)
                    else -> error("Unknown record type in raw report: ${obj.string("type")}")
                }
            }
        require(header != null) { "Raw report missing header line" }
        require(footer != null) { "Raw report missing footer line" }
        return RawReport(header!!, snapshots, footer!!)
    }

    private fun parseHeader(obj: JsonValue.JsonObject) =
        RawReportHeader(
            runId = obj.string("runId"),
            startedAt = Instant.parse(obj.string("startedAt")),
            monitors = obj.array("monitors").items.map { it.asString() },
            snapshotGranularity = obj.string("snapshotGranularity"),
            memoryGrowthThresholdBytes = obj.stringOrNull("memoryGrowthThresholdBytes")?.toLong() ?: 0L,
        )

    private fun parseFooter(obj: JsonValue.JsonObject) =
        RawReportFooter(
            finishedAt = Instant.parse(obj.string("finishedAt")),
            lifecycles =
                obj.array("lifecycles").items.map { it.asObject() }.map { lc ->
                    TestClassLifecycleRecord(
                        testClass = lc.string("testClass"),
                        start = Instant.parse(lc.string("start")),
                        end = Instant.parse(lc.string("end")),
                    )
                },
        )

    private fun parseSnapshot(obj: JsonValue.JsonObject): RawSnapshot {
        val discrete =
            obj.objectOrNull("discrete")?.members?.entries?.associate { (typeName, value) ->
                typeName to value.asArray().items.map { parseDiscrete(typeName, it) }
            } ?: emptyMap()
        val numeric =
            obj.objectOrNull("numeric")?.members?.entries?.associate { (name, value) ->
                val o = value.asObject()
                name to
                    NumericMeasurement(
                        value = o.string("value").toLong(),
                        timestamp = Instant.parse(o.string("timestamp")),
                    )
            } ?: emptyMap()
        return RawSnapshot(
            kind = obj.string("kind"),
            timestamp = Instant.parse(obj.string("timestamp")),
            testClass = obj.stringOrNull("testClass"),
            testMethod = obj.stringOrNull("testMethod"),
            discrete = discrete,
            numeric = numeric,
        )
    }

    private fun parseDiscrete(
        typeName: String,
        value: JsonValue,
    ): DiscreteResource =
        when (typeName) {
            "ports" -> {
                DiscreteResource.Port((value as JsonValue.JsonNumber).value.toInt())
            }

            "threads" -> {
                val o = value.asObject()
                DiscreteResource.ThreadResource(name = o.string("name"), id = o.string("id"))
            }

            else -> {
                DiscreteResource.Simple(value.asString())
            }
        }
}
