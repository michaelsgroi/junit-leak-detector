package com.michaelsgroi.test.extensions.resourceleak

sealed class ResourceId {
    data class PropertyId(
        val name: String,
    ) : ResourceId()

    data class EnvironmentVariableId(
        val name: String,
    ) : ResourceId()

    data class ThreadId(
        val name: String,
        val id: Long,
    ) : ResourceId()

    data class PortId(
        val port: Int,
    ) : ResourceId()

    data class DynamoDbTableId(
        val name: String,
    ) : ResourceId()
}
