package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanonicalJsonException(val code: String) : Exception("canonical json: $code") {
    companion object {
        const val NON_INTEGER_NUMBER = "non-integer-number"
        const val NUMBER_OUT_OF_RANGE = "number-out-of-range"
        const val LONE_SURROGATE = "lone-surrogate"
    }
}

// One byte-exact text per JSON value, shared with the TypeScript twin: keys
// sorted by UTF-16 code unit, no whitespace, integers only, and control
// characters always written as \u00xx.
object CanonicalJson {
    fun encode(value: JsonElement): String = buildString { write(value) }

    fun sha256Hex(value: JsonElement): String = Sha256.hexOfUtf8(encode(value))

    private fun StringBuilder.write(value: JsonElement) {
        when (value) {
            is JsonNull -> append("null")
            is JsonObject -> writeObject(value)
            is JsonArray -> writeArray(value)
            is JsonPrimitive -> when {
                value.isString -> writeString(value.content)
                value.content == "true" || value.content == "false" -> append(value.content)
                else -> writeNumber(value)
            }
        }
    }

    private fun StringBuilder.writeObject(value: JsonObject) {
        append('{')
        value.keys.sorted().forEachIndexed { i, key ->
            if (i > 0) append(',')
            writeString(key)
            append(':')
            write(value.getValue(key))
        }
        append('}')
    }

    private fun StringBuilder.writeArray(value: JsonArray) {
        append('[')
        value.forEachIndexed { i, element ->
            if (i > 0) append(',')
            write(element)
        }
        append(']')
    }

    private fun StringBuilder.writeNumber(value: JsonPrimitive) {
        val number = value.numberOrNull()
        if (number == null || !number.isIntegral()) {
            if (number != null && number.isInfinite()) {
                throw CanonicalJsonException(CanonicalJsonException.NUMBER_OUT_OF_RANGE)
            }
            throw CanonicalJsonException(CanonicalJsonException.NON_INTEGER_NUMBER)
        }
        val integer = value.safeIntegerOrNull()
            ?: throw CanonicalJsonException(CanonicalJsonException.NUMBER_OUT_OF_RANGE)
        append(integer)
    }

    private fun StringBuilder.writeString(text: String) {
        append('"')
        var i = 0
        while (i < text.length) {
            val unit = text[i]
            when {
                unit == '"' -> append("\\\"")
                unit == '\\' -> append("\\\\")
                unit.code < 0x20 -> {
                    append("\\u00")
                    append(unit.code.toString(16).padStart(2, '0'))
                }
                unit.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) {
                        throw CanonicalJsonException(CanonicalJsonException.LONE_SURROGATE)
                    }
                    append(unit)
                    append(text[i + 1])
                    i++
                }
                unit.isLowSurrogate() -> throw CanonicalJsonException(CanonicalJsonException.LONE_SURROGATE)
                else -> append(unit)
            }
            i++
        }
        append('"')
    }
}
