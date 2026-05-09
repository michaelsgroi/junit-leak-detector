package com.salesforce.test.extensions.resourceleak

enum class SnapshotGranularity(val configValue: String) {
    CLASS("class"),
    TEST("test");

    companion object {
        fun fromConfigValue(value: String): SnapshotGranularity? =
            values().find { it.configValue == value.trim() }
    }
}
