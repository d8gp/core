package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

// kotlinx hands over unquoted literals that are not JSON (1d, 0x1p3, 01, NaN)
// and the platform number parsers accept some of them, each platform its own
// set. Only the JSON grammar counts as a number, as after JSON.parse.
private val JSON_NUMBER_RE = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.numberOrNull(): Double? =
    (this as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull && JSON_NUMBER_RE.matches(it.content) }
        ?.doubleOrNull

internal fun JsonElement?.isBoolean(): Boolean =
    this is JsonPrimitive && !isString && (content == "true" || content == "false")

internal fun Double.isIntegral(): Boolean = !isNaN() && !isInfinite() && this == kotlin.math.floor(this)

// A JSON number with an integral value inside the JavaScript safe range, so
// 1.0 and 1e2 are integers here exactly as they are after JSON.parse.
internal fun JsonElement?.safeIntegerOrNull(): Long? {
    val number = numberOrNull() ?: return null
    if (!number.isIntegral()) return null
    if (number < -MAX_SAFE_INTEGER.toDouble() || number > MAX_SAFE_INTEGER.toDouble()) return null
    return number.toLong()
}

internal fun JsonElement?.integerIn(range: LongRange): Long? =
    safeIntegerOrNull()?.takeIf { it in range }

// Counts what a UTF-8 encoder would write, with an unpaired surrogate counted
// as the three bytes of its replacement character.
fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val unit = text[i]
        when {
            unit.code < 0x80 -> bytes += 1
            unit.code < 0x800 -> bytes += 2
            unit.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                bytes += 4
                i++
            }
            else -> bytes += 3
        }
        i++
    }
    return bytes
}

// Controls, zero-width and direction marks, the Unicode line breaks, the
// invisible format characters and the byte order mark.
private fun isConsentControl(unit: Char): Boolean = when (unit.code) {
    in 0x00..0x1f, in 0x7f..0x9f -> true
    in 0x200b..0x200f, in 0x2028..0x202e, in 0x2060..0x206f, 0xfeff -> true
    else -> false
}

private fun isLoneSurrogate(text: String, index: Int): Boolean {
    val unit = text[index]
    return when {
        unit.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
        unit.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
        else -> false
    }
}

// True for a character that could start a line, move the cursor, reorder or
// hide text in a consent dialog, or that is not text at all.
internal fun isForbiddenInConsentText(text: String, index: Int): Boolean =
    isConsentControl(text[index]) || isLoneSurrogate(text, index)

fun isConsentSafe(text: String): Boolean = text.indices.none { isForbiddenInConsentText(text, it) }

internal fun hasLoneSurrogate(text: String): Boolean = text.indices.any { isLoneSurrogate(text, it) }

// Text shown to the user inside a consent dialog, bounded in UTF-16 units.
fun isConsentText(text: String, maxUnits: Int): Boolean =
    text.isNotEmpty() && text.length <= maxUnits && isConsentSafe(text)
