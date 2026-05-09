package com.michaelsgroi.test.extensions.resourceleak

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
    private val systemPropertyLookup: (String) -> String? = System::getProperty,
) {
    private val fileProperties: Properties = propertiesLoader() ?: Properties()

    val monitoredResourceTypes: String
        get() = read("monitored.resource.types") ?: ""

    val threadGracePeriodSeconds: Long
        get() = read("thread.grace.period.seconds")?.toLong() ?: 10L

    val memoryGrowthThresholdMb: Long
        get() = read("memory.growth.threshold.mb")?.toLong() ?: 50L

    val buildFailureResourceTypes: String
        get() = read("build.failure.resource.types") ?: ""

    val snapshotGranularity: SnapshotGranularity
        get() =
            read("snapshot.granularity")
                ?.let { SnapshotGranularity.fromConfigValue(it) }
                ?: SnapshotGranularity.CLASS

    val reportOutputDir: String
        get() = read("report.output.dir") ?: System.getProperty("user.dir") ?: "."

    val preclassSettleEnabled: Boolean
        get() = read("preclass.settle.enabled")?.toBooleanStrictOrNull() ?: false

    val preclassSettleMaxSeconds: Long
        get() = read("preclass.settle.max.seconds")?.toLong() ?: 10L

    val preclassSettlePollIntervalSeconds: Long
        get() = read("preclass.settle.poll.interval.seconds")?.toLong() ?: 1L

    val finalSettleEnabled: Boolean
        get() = read("final.settle.enabled")?.toBooleanStrictOrNull() ?: true

    val finalSettleMaxSeconds: Long
        get() = read("final.settle.max.seconds")?.toLong() ?: 90L

    val finalSettlePollIntervalSeconds: Long
        get() = read("final.settle.poll.interval.seconds")?.toLong() ?: 1L

    /**
     * Comma-separated list of system property names to exclude from leak detection. Useful for
     * framework globals that are set once during init (Spring Boot's `PID`, `APPLICATION_NAME`,
     * `CONSOLE_LOG_CHARSET`; Jetty's `jetty.git.hash`; AWS SDK's `aws.accessKeyId`; log4j2's
     * `log4j2.discardThreshold`; etc.) — these are documented framework contracts, not test bugs.
     */
    val ignoredSystemProperties: Set<String>
        get() =
            read("systemprops.ignored")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

    /**
     * Comma-separated list of `fully.qualified.ClassName#staticMethod` entries to invoke at
     * suite-end (alongside [SuiteShutdownHook] ServiceLoader entries). Use this when the project
     * already exposes a static cleanup method (e.g. an embedded server cache shutdown) and you'd
     * rather point at it from config than ship a separate `SuiteShutdownHook` class + services
     * file. Each entry must be a static, no-arg method; missing classes are logged and skipped.
     */
    val suiteShutdownStaticMethods: List<String>
        get() =
            read("suite.shutdown.static.methods")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

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
            get() =
                sharedInstance ?: synchronized(this) {
                    sharedInstance ?: Configuration().also { sharedInstance = it }
                }

        private fun loadFromClasspath(): Properties? {
            val classLoader =
                Thread.currentThread().contextClassLoader
                    ?: Configuration::class.java.classLoader
            val stream = classLoader.getResourceAsStream(PROPERTIES_FILE_NAME) ?: return null
            return stream.use { Properties().apply { load(it) } }
        }
    }
}
