package com.michaelsgroi.test.extensions.resourceleak

@Suppress("UNCHECKED_CAST")
internal object EnvVarReflection {
    fun set(
        key: String,
        value: String,
    ) {
        val env = environmentMap()
        env[variableValueOf(key)] = valueValueOf(value)
    }

    fun remove(key: String) {
        val env = environmentMap()
        env.remove(variableValueOf(key))
    }

    private fun environmentMap(): MutableMap<Any, Any> {
        val processEnvironment = Class.forName("java.lang.ProcessEnvironment")
        val theEnvironmentField = processEnvironment.getDeclaredField("theEnvironment")
        theEnvironmentField.isAccessible = true
        return theEnvironmentField.get(null) as MutableMap<Any, Any>
    }

    private fun variableValueOf(key: String): Any {
        val variableClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
        val variableValueOf = variableClass.getDeclaredMethod("valueOf", String::class.java)
        variableValueOf.isAccessible = true
        return variableValueOf.invoke(null, key)!!
    }

    private fun valueValueOf(value: String): Any {
        val valueClass = Class.forName("java.lang.ProcessEnvironment\$Value")
        val valueValueOf = valueClass.getDeclaredMethod("valueOf", String::class.java)
        valueValueOf.isAccessible = true
        return valueValueOf.invoke(null, value)!!
    }
}
