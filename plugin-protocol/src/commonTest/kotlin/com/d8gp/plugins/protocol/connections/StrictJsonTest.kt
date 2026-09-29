package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ManifestException
import com.d8gp.plugins.protocol.parseManifest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictJsonTest {
    private fun verdict(text: String): String = try {
        "ok ${CanonicalJson.encode(StrictJson.read(text))}"
    } catch (e: StrictJsonException) {
        "error ${e.code}"
    }

    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("json-text.json", 515)
        for (vector in vectorList(VectorsGenerated.JSON_TEXT)) {
            val expect = vector.getValue("expect")
            val expected = if (expect is JsonObject) "error ${expect.text("error")}" else "ok ${vector.text("canonical")}"
            report.check(vector.text("name"), expected) { verdict(vector.text("text")) }
        }
        report.finish()
    }

    @Test
    fun `a text of any depth is scanned without recursion`() {
        assertEquals(StrictJsonException.TOO_DEEP, StrictJson.scan("[".repeat(1_000_000)))
        assertEquals(StrictJsonException.TOO_DEEP, StrictJson.scan("{\"a\":".repeat(1_000_000)))
        assertEquals(StrictJsonException.NOT_JSON, StrictJson.scan("]".repeat(1_000_000)))
    }

    @Test
    fun `the deepest text that is accepted is parsed`() {
        val deepest = "[".repeat(StrictJson.MAX_DEPTH) + "]".repeat(StrictJson.MAX_DEPTH)
        assertNull(StrictJson.scan(deepest))
        assertEquals(deepest, CanonicalJson.encode(StrictJson.read(deepest)))
        assertEquals(StrictJsonException.TOO_DEEP, StrictJson.scan("[$deepest]"))
    }

    @Test
    fun `a manifest text that is not json is refused at the root`() {
        val deep = "{\"a\":".repeat(StrictJson.MAX_DEPTH + 1) + "1" + "}".repeat(StrictJson.MAX_DEPTH + 1)
        for (text in listOf("", "{\"manifestVersion\":01}", "{manifestVersion:1}", "{\"id\":\"a\tb\"}", "﻿{}", deep)) {
            val error = assertFailsWith<ManifestException>(text) { parseManifest(text) }
            assertTrue(error.message!!.startsWith("manifest.: "), error.message)
        }
    }

    @Test
    fun `a record text goes through the strict reader`() {
        val stored = vectorList(VectorsGenerated.POLICY).first { it.getValue("expect") !is JsonObject }
            .getValue("record")
        val record = parseConnectionRecord(stored.toString())
        assertEquals(stored.toString().let(StrictJson::read), stored)
        assertEquals(record, parseConnectionRecord(" $stored\n"))
        val lenient = stored.toString().replaceFirst("\"major\":1", "\"major\":01")
        assertTrue(lenient != stored.toString())
        assertEquals(
            StrictJsonException.NOT_JSON,
            assertFailsWith<StrictJsonException> { parseConnectionRecord(lenient) }.code,
        )
        assertNull(StrictJson.readOrNull(lenient))
    }
}
