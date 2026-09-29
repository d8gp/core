package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ProtocolJson
import kotlinx.serialization.json.JsonElement

class StrictJsonException(val code: String) : Exception("json text: $code") {
    companion object {
        const val NOT_JSON = "not-json"
        const val TOO_DEEP = "too-deep"
    }
}

// The only way from a JSON text to a value for the contract. kotlinx accepts
// texts that are not RFC 8259 JSON (01, 1d, unquoted words, raw control
// characters) and recurses once per level, so the scan refuses those texts
// and every text deeper than MAX_DEPTH before kotlinx sees them.
object StrictJson {
    const val MAX_DEPTH = 64

    fun read(text: String): JsonElement {
        scan(text)?.let { throw StrictJsonException(it) }
        return try {
            ProtocolJson.parseToJsonElement(text)
        } catch (_: Exception) {
            throw StrictJsonException(StrictJsonException.NOT_JSON)
        }
    }

    fun readOrNull(text: String): JsonElement? = try {
        read(text)
    } catch (_: StrictJsonException) {
        null
    }

    // The code of the first error met from left to right, or null for a text
    // that is JSON and at most MAX_DEPTH levels deep.
    fun scan(text: String): String? = Scan(text).run()

    private enum class Expect { Value, ValueOrArrayEnd, KeyOrObjectEnd, Key, Colon, CommaOrEnd }

    // Characters are compared one by one with ASCII: isDigit, isWhitespace
    // and isLetter accept characters that JSON does not.
    private class Scan(private val text: String) {
        private val openIsObject = BooleanArray(MAX_DEPTH)
        private var depth = 0
        private var at = 0
        private var expect = Expect.Value

        fun run(): String? {
            while (true) {
                skipWhitespace()
                if (at == text.length) return if (complete()) null else StrictJsonException.NOT_JSON
                step(text[at])?.let { return it }
            }
        }

        private fun complete(): Boolean = expect == Expect.CommaOrEnd && depth == 0

        private fun skipWhitespace() {
            while (at < text.length && (text[at] == ' ' || text[at] == '\t' || text[at] == '\n' || text[at] == '\r')) {
                at++
            }
        }

        private fun step(unit: Char): String? = when (expect) {
            Expect.Value -> value(unit)
            Expect.ValueOrArrayEnd -> if (unit == ']') close(isObject = false) else value(unit)
            Expect.KeyOrObjectEnd -> if (unit == '}') close(isObject = true) else key(unit)
            Expect.Key -> key(unit)
            Expect.Colon -> accept(unit == ':', then = Expect.Value)
            Expect.CommaOrEnd -> when {
                depth == 0 -> StrictJsonException.NOT_JSON
                unit == ',' -> accept(true, then = if (openIsObject[depth - 1]) Expect.Key else Expect.Value)
                unit == ']' -> close(isObject = false)
                unit == '}' -> close(isObject = true)
                else -> StrictJsonException.NOT_JSON
            }
        }

        private fun accept(ok: Boolean, then: Expect): String? {
            if (!ok) return StrictJsonException.NOT_JSON
            at++
            expect = then
            return null
        }

        private fun value(unit: Char): String? = when (unit) {
            '[' -> open(isObject = false)
            '{' -> open(isObject = true)
            '"' -> string(then = Expect.CommaOrEnd)
            't' -> word("true")
            'f' -> word("false")
            'n' -> word("null")
            else -> number()
        }

        private fun key(unit: Char): String? =
            if (unit == '"') string(then = Expect.Colon) else StrictJsonException.NOT_JSON

        private fun open(isObject: Boolean): String? {
            if (depth == MAX_DEPTH) return StrictJsonException.TOO_DEEP
            openIsObject[depth++] = isObject
            at++
            expect = if (isObject) Expect.KeyOrObjectEnd else Expect.ValueOrArrayEnd
            return null
        }

        private fun close(isObject: Boolean): String? {
            if (depth == 0 || openIsObject[depth - 1] != isObject) return StrictJsonException.NOT_JSON
            depth--
            at++
            expect = Expect.CommaOrEnd
            return null
        }

        private fun word(word: String): String? {
            if (!text.startsWith(word, at)) return StrictJsonException.NOT_JSON
            at += word.length
            expect = Expect.CommaOrEnd
            return null
        }

        private fun number(): String? {
            if (at < text.length && text[at] == '-') at++
            when {
                at == text.length -> return StrictJsonException.NOT_JSON
                text[at] == '0' -> at++
                text[at] in '1'..'9' -> skipDigits()
                else -> return StrictJsonException.NOT_JSON
            }
            if (at < text.length && text[at] == '.') {
                at++
                if (!skipDigits()) return StrictJsonException.NOT_JSON
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                if (!skipDigits()) return StrictJsonException.NOT_JSON
            }
            expect = Expect.CommaOrEnd
            return null
        }

        private fun skipDigits(): Boolean {
            val start = at
            while (at < text.length && text[at] in '0'..'9') at++
            return at > start
        }

        private fun string(then: Expect): String? {
            at++
            while (at < text.length) {
                val unit = text[at++]
                when {
                    unit == '"' -> {
                        expect = then
                        return null
                    }
                    unit.code < 0x20 -> return StrictJsonException.NOT_JSON
                    unit == '\\' -> if (!escape()) return StrictJsonException.NOT_JSON
                }
            }
            return StrictJsonException.NOT_JSON
        }

        private fun escape(): Boolean {
            if (at == text.length) return false
            val unit = text[at++]
            if (unit != 'u') return unit in "\"\\/bfnrt"
            if (at + 4 > text.length) return false
            repeat(4) { if (!isHexDigit(text[at++])) return false }
            return true
        }

        private fun isHexDigit(unit: Char): Boolean = unit in '0'..'9' || unit in 'a'..'f' || unit in 'A'..'F'
    }
}
