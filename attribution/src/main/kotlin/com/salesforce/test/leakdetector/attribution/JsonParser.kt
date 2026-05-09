package com.salesforce.test.leakdetector.attribution

internal sealed class JsonValue {
    data class JsonString(
        val value: String,
    ) : JsonValue()

    data class JsonNumber(
        val value: Double,
    ) : JsonValue()

    data class JsonBool(
        val value: Boolean,
    ) : JsonValue()

    object JsonNull : JsonValue()

    data class JsonArray(
        val items: List<JsonValue>,
    ) : JsonValue()

    data class JsonObject(
        val members: Map<String, JsonValue>,
    ) : JsonValue()
}

internal class JsonParser(
    private val text: String,
) {
    private var pos = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val result = parseValue()
        skipWhitespace()
        require(pos == text.length) { "Unexpected trailing input at position $pos" }
        return result
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (val c = peek()) {
            '"' -> JsonValue.JsonString(parseString())
            '{' -> parseObject()
            '[' -> parseArray()
            't', 'f' -> parseBool()
            'n' -> parseNull()
            else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected char '$c' at $pos")
        }
    }

    private fun parseObject(): JsonValue.JsonObject {
        expect('{')
        val members = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonValue.JsonObject(members)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            members[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }

                '}' -> {
                    pos++
                    return JsonValue.JsonObject(members)
                }

                else -> {
                    error("Expected ',' or '}' at $pos")
                }
            }
        }
    }

    private fun parseArray(): JsonValue.JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }

                ']' -> {
                    pos++
                    return JsonValue.JsonArray(items)
                }

                else -> {
                    error("Expected ',' or ']' at $pos")
                }
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                require(pos < text.length) { "Unterminated escape at $pos" }
                when (val esc = text[pos++]) {
                    '"' -> {
                        sb.append('"')
                    }

                    '\\' -> {
                        sb.append('\\')
                    }

                    '/' -> {
                        sb.append('/')
                    }

                    'n' -> {
                        sb.append('\n')
                    }

                    'r' -> {
                        sb.append('\r')
                    }

                    't' -> {
                        sb.append('\t')
                    }

                    'b' -> {
                        sb.append('\b')
                    }

                    'f' -> {
                        sb.append('')
                    }

                    'u' -> {
                        require(pos + 4 <= text.length) { "Truncated unicode escape at $pos" }
                        val hex = text.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }

                    else -> {
                        error("Unknown escape '\\$esc' at $pos")
                    }
                }
            } else {
                sb.append(c)
            }
        }
        error("Unterminated string")
    }

    private fun parseNumber(): JsonValue.JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length &&
            (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')
        ) {
            pos++
        }
        return JsonValue.JsonNumber(text.substring(start, pos).toDouble())
    }

    private fun parseBool(): JsonValue.JsonBool {
        if (text.startsWith("true", pos)) {
            pos += 4
            return JsonValue.JsonBool(true)
        }
        if (text.startsWith("false", pos)) {
            pos += 5
            return JsonValue.JsonBool(false)
        }
        error("Expected bool at $pos")
    }

    private fun parseNull(): JsonValue.JsonNull {
        if (text.startsWith("null", pos)) {
            pos += 4
            return JsonValue.JsonNull
        }
        error("Expected null at $pos")
    }

    private fun expect(c: Char) {
        if (peek() != c) error("Expected '$c' at $pos but got '${peek()}'")
        pos++
    }

    private fun peek(): Char {
        require(pos < text.length) { "Unexpected end of input at $pos" }
        return text[pos]
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}

internal fun parseJson(text: String): JsonValue = JsonParser(text).parse()

internal fun JsonValue.asObject(): JsonValue.JsonObject = this as JsonValue.JsonObject

internal fun JsonValue.asArray(): JsonValue.JsonArray = this as JsonValue.JsonArray

internal fun JsonValue.asString(): String = (this as JsonValue.JsonString).value

internal fun JsonValue.JsonObject.string(key: String): String = members.getValue(key).asString()

internal fun JsonValue.JsonObject.stringOrNull(key: String): String? =
    when (val v = members[key]) {
        null, JsonValue.JsonNull -> null
        else -> v.asString()
    }

internal fun JsonValue.JsonObject.array(key: String): JsonValue.JsonArray = members.getValue(key).asArray()

internal fun JsonValue.JsonObject.objectOrNull(key: String): JsonValue.JsonObject? =
    when (val v = members[key]) {
        null, JsonValue.JsonNull -> null
        else -> v.asObject()
    }
