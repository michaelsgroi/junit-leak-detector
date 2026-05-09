package com.salesforce.test.extensions.resourceleak

import kotlin.reflect.KClass

internal object RawReportJson {

    fun resourceTypeName(resourceIdClass: KClass<out ResourceId>): String = when (resourceIdClass) {
        ResourceId.PortId::class -> ResourceType.PORTS.configValue
        ResourceId.ThreadId::class -> ResourceType.THREADS.configValue
        ResourceId.PropertyId::class -> ResourceType.SYSTEM_PROPS.configValue
        ResourceId.EnvironmentVariableId::class -> ResourceType.ENV_VARS.configValue
        ResourceId.DynamoDbTableId::class -> ResourceType.DDBTABLES.configValue
        else -> resourceIdClass.simpleName ?: "unknown"
    }

    fun encodeResourceId(id: ResourceId): String = when (id) {
        is ResourceId.PortId -> id.port.toString()
        is ResourceId.ThreadId -> obj("name" to str(id.name), "id" to str(id.id.toString()))
        is ResourceId.PropertyId -> str(id.name)
        is ResourceId.EnvironmentVariableId -> str(id.name)
        is ResourceId.DynamoDbTableId -> str(id.name)
    }

    fun encodeSnapshot(snapshot: Snapshot): String = obj(
        "type" to str("snapshot"),
        "kind" to str(snapshot.kind.name),
        "timestamp" to str(snapshot.timestamp.toString()),
        "testClass" to (snapshot.testClass?.let { str(it) } ?: "null"),
        "testMethod" to (snapshot.testMethod?.let { str(it) } ?: "null"),
        "discrete" to encodeDiscrete(snapshot.discrete),
        "numeric" to encodeNumericMap(snapshot.numeric)
    )

    fun encodeHeader(
        runId: String,
        startedAt: String,
        monitors: List<String>,
        snapshotGranularity: String
    ): String = obj(
        "type" to str("header"),
        "runId" to str(runId),
        "startedAt" to str(startedAt),
        "monitors" to array(monitors.map { str(it) }),
        "snapshotGranularity" to str(snapshotGranularity)
    )

    fun encodeFooter(
        finishedAt: String,
        lifecycles: Map<TestClassName, TestClassLifecycle>
    ): String = obj(
        "type" to str("footer"),
        "finishedAt" to str(finishedAt),
        "lifecycles" to array(
            lifecycles.entries.map { (name, lc) ->
                obj(
                    "testClass" to str(name),
                    "start" to str(lc.start.toString()),
                    "end" to str(lc.end.toString())
                )
            }
        )
    )

    private fun encodeDiscrete(discrete: Map<KClass<out ResourceId>, Set<ResourceId>>): String {
        val pairs = discrete.entries.map { (resourceIdClass, ids) ->
            resourceTypeName(resourceIdClass) to array(ids.map { encodeResourceId(it) })
        }
        return obj(*pairs.toTypedArray())
    }

    private fun encodeNumericMap(numeric: Map<String, NumericResourceMeasurement>): String {
        val pairs = numeric.entries.map { (name, m) ->
            name to obj(
                "value" to str(m.value.toString()),
                "timestamp" to str(m.timestamp.toString())
            )
        }
        return obj(*pairs.toTypedArray())
    }

    private fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "${str(k)}:$v" }

    private fun array(elements: List<String>): String =
        elements.joinToString(",", prefix = "[", postfix = "]")

    private fun str(value: String): String {
        val escaped = StringBuilder(value.length + 2)
        escaped.append('"')
        for (c in value) {
            when (c) {
                '\\' -> escaped.append("\\\\")
                '"' -> escaped.append("\\\"")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                '\b' -> escaped.append("\\b")
                else -> if (c.code < 0x20) {
                    escaped.append("\\u%04x".format(c.code))
                } else {
                    escaped.append(c)
                }
            }
        }
        escaped.append('"')
        return escaped.toString()
    }
}
