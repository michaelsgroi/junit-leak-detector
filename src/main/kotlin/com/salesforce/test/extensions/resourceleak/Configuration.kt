package com.salesforce.test.extensions.resourceleak

import java.util.Properties

/**
 * Configuration values for the resource leak detector. Loaded at startup from
 * `resource-leak-detector.properties` on the test classpath, with system property
 * overrides of the form `resource.leak.detector.<key>`. If the file is absent or
 * a key is missing, built-in defaults apply.
 *
 * Visible-for-test: pass a custom loader to override file/system property lookup.
 */
class Configuration(
    propertiesLoader: () -> Properties? = ::loadFromClasspath,
    private val systemPropertyLookup: (String) -> String? = System::getProperty
) {
    private val fileProperties: Properties = propertiesLoader() ?: Properties()

    val monitoredResourceTypes: String
        get() = read("monitored.resource.types") ?: ""

    val threadGracePeriodSeconds: Long
        get() = read("thread.grace.period.seconds")?.toLong() ?: 10L

    val memoryGrowthThresholdMb: Long
        get() = read("memory.growth.threshold.mb")?.toLong() ?: 1024L

    val buildFailureResourceTypes: String
        get() = read("build.failure.resource.types") ?: ""

    val snapshotGranularity: SnapshotGranularity
        get() = read("snapshot.granularity")
            ?.let { SnapshotGranularity.fromConfigValue(it) }
            ?: SnapshotGranularity.CLASS

    private fun read(key: String): String? {
        val systemValue = systemPropertyLookup(SYSTEM_PROPERTY_PREFIX + key)
        if (!systemValue.isNullOrBlank()) {
            return systemValue
        }
        val fileValue = fileProperties.getProperty(key)
        if (!fileValue.isNullOrBlank()) {
            return fileValue
        }
        return null
    }

    companion object {
        const val PROPERTIES_FILE_NAME = "resource-leak-detector.properties"
        const val SYSTEM_PROPERTY_PREFIX = "resource.leak.detector."

        @Volatile
        private var sharedInstance: Configuration? = null

        @JvmStatic
        val instance: Configuration
            get() = sharedInstance ?: synchronized(this) {
                sharedInstance ?: Configuration().also { sharedInstance = it }
            }

        private fun loadFromClasspath(): Properties? {
            val classLoader = Thread.currentThread().contextClassLoader
                ?: Configuration::class.java.classLoader
            val stream = classLoader.getResourceAsStream(PROPERTIES_FILE_NAME) ?: return null
            return stream.use { Properties().apply { load(it) } }
        }
    }
}
