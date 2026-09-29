package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FingerprintTest {
    private val vectors = vectorList(VectorsGenerated.FINGERPRINT)
    private val base = vectors.first { it.text("name") == "base/design-section-4.1-record" }

    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("fingerprint.json", 36)
        for (vector in vectors) {
            val record = vector.getValue("record").jsonObject
            report.check(vector.text("name"), vector.text("canonical") to vector.text("fingerprint")) {
                connectionFingerprintInput(record) to connectionFingerprint(record)
            }
        }
        report.finish()
    }

    @Test
    fun `same vectors equal the base and every other vector differs from it`() {
        val baseFingerprint = connectionFingerprint(base.getValue("record").jsonObject)
        for (vector in vectors) {
            val name = vector.text("name")
            val fingerprint = connectionFingerprint(vector.getValue("record").jsonObject)
            when (name.substringBefore('/')) {
                "same" -> assertEquals(baseFingerprint, fingerprint, name)
                "differs", "other" -> assertNotEquals(baseFingerprint, fingerprint, name)
            }
        }
    }

    @Test
    fun `a field this version does not know is covered wherever it sits`() {
        val record = base.getValue("record").jsonObject
        val approved = connectionFingerprint(record)
        val surprise = "surprise" to JsonPrimitive("everything")

        assertNotEquals(approved, connectionFingerprint(JsonObject(record + surprise)))
        for (key in listOf("consumer", "provider", "budget", "scope", "onwardEgress")) {
            val widened = JsonObject(record + (key to JsonObject(record.getValue(key).jsonObject + surprise)))
            assertNotEquals(approved, connectionFingerprint(widened), key)
        }
    }
}
