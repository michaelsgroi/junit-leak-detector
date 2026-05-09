package com.salesforce.test.extensions.resourceleak

enum class ResourceType(
    val configValue: String,
) {
    SYSTEM_PROPS("systemprops"),
    MEMORY("memory"),
    ENV_VARS("envvars"),
    THREADS("threads"),
    PORTS("ports"),
    DDBTABLES("ddbtables"),
    ;

    companion object {
        fun fromConfigValue(value: String): ResourceType? = values().find { it.configValue == value }
    }
}
