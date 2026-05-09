package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Intentionally leaks an environment variable to verify the resource leak detector reports it.
 * Run with: mvn test -pl zos -Dtest=EnvironmentVariableLeakingTest -Dresource.leak.detector.monitored.resource.types=envvars
 */
class EnvironmentVariableLeakingTest {
    @Test
    fun `test that leaks an environment variable`() {
        setEnvVar("LEAKED_BY_ENVIRONMENT_VARIABLE_LEAKING_TEST", "leaked-value")
        assertEquals("leaked-value", System.getenv("LEAKED_BY_ENVIRONMENT_VARIABLE_LEAKING_TEST"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setEnvVar(
        key: String,
        value: String,
    ) {
        val processEnvironment = Class.forName("java.lang.ProcessEnvironment")
        val theEnvironmentField = processEnvironment.getDeclaredField("theEnvironment")
        theEnvironmentField.isAccessible = true
        val env = theEnvironmentField.get(null) as MutableMap<Any, Any>

        val variableClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
        val variableValueOf = variableClass.getDeclaredMethod("valueOf", String::class.java)
        variableValueOf.isAccessible = true

        val valueClass = Class.forName("java.lang.ProcessEnvironment\$Value")
        val valueValueOf = valueClass.getDeclaredMethod("valueOf", String::class.java)
        valueValueOf.isAccessible = true

        env[variableValueOf.invoke(null, key)] = valueValueOf.invoke(null, value)
    }
}
