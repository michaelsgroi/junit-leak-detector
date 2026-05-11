package com.michaelsgroi.test.extensions.resourceleak

/**
 * Configuration values for the resource leak detector. Read exclusively from system
 * properties of the form `resource.leak.detector.<key>` — typically set via Surefire's
 * `<systemPropertyVariables>` block alongside the dependency injection. Each key has a
 * built-in default; consumers who don't want to override anything write nothing.
 *
 * Visible-for-test: pass a custom [systemPropertyLookup] to override.
 */
class Configuration(
    private val systemPropertyLookup: (String) -> String? = System::getProperty,
) {
    val disabled: Boolean
        get() = read("disabled")?.toBooleanStrictOrNull() ?: false

    val monitoredResourceTypes: String
        get() = read("monitored.resource.types") ?: DEFAULT_MONITORED_RESOURCE_TYPES

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
        get() = read("report.output.dir") ?: "target/resource-leak-detector"

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
        val value = systemPropertyLookup(SYSTEM_PROPERTY_PREFIX + key)
        return if (value.isNullOrBlank()) null else value
    }

    companion object {
        const val SYSTEM_PROPERTY_PREFIX = "resource.leak.detector."

        /** Default monitored resource types when the config key is unset. DDB tables omitted —
         *  it requires the AWS SDK on the test classpath, so it's opt-in. */
        const val DEFAULT_MONITORED_RESOURCE_TYPES = "ports,threads,systemprops,envvars,memory"

        @Volatile
        private var sharedInstance: Configuration? = null

        @JvmStatic
        val instance: Configuration
            get() =
                sharedInstance ?: synchronized(this) {
                    sharedInstance ?: Configuration().also { sharedInstance = it }
                }
    }
}
