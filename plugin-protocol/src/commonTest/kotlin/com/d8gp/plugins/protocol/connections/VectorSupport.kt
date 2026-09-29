package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.fail

internal fun vectorList(text: String): List<JsonObject> =
    Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

// A size as the host hands it to the policy code: what is not a count in the
// vector arrives as a value that is not a count.
internal fun JsonObject.countOrInvalid(key: String): Long = get(key).integerIn(0..MAX_SAFE_INTEGER) ?: -1

internal fun JsonObject.textOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

// Runs every vector before failing, so one report lists every disagreement
// with the shared contract by vector name.
internal class VectorReport(private val file: String, private val expectedCount: Int) {
    private val failures = mutableListOf<String>()
    private var seen = 0

    fun <T> check(name: String, expected: T, actual: () -> T) {
        seen++
        val got = try {
            actual()
        } catch (e: Throwable) {
            failures += "$name: expected <$expected> but threw ${e::class.simpleName}: ${e.message}"
            return
        }
        if (got != expected) failures += "$name: expected <$expected> but was <$got>"
    }

    fun finish() = finishCounting(checksPerVector = 1)

    fun finishCounting(checksPerVector: Int) {
        assertEquals(expectedCount * checksPerVector, seen, "$file: number of vectors differs from the README table")
        if (failures.isNotEmpty()) {
            fail("$file: ${failures.size} of $seen vectors disagree\n" + failures.joinToString("\n"))
        }
    }
}

internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)
