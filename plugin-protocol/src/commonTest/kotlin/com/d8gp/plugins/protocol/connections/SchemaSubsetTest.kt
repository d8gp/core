package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaSubsetTest {
    private val file = parseJson(VectorsGenerated.SCHEMA_SUBSET).jsonObject

    @Test
    fun `dialect check matches every shared vector`() {
        val report = VectorReport("schema-subset.json dialect", 104)
        for (vector in file.getValue("dialect").jsonArray.map { it.jsonObject }) {
            val expect = vector.getValue("expect")
            val expected = if (expect is JsonObject) expect.text("reject") else null
            report.check(vector.text("name"), expected) { SchemaSubset.check(vector.getValue("schema")) }
        }
        report.finish()
    }

    @Test
    fun `instance validation matches every shared vector`() {
        val report = VectorReport("schema-subset.json instances", 50)
        for (vector in file.getValue("instances").jsonArray.map { it.jsonObject }) {
            val schema = vector.getValue("schema")
            report.check(vector.text("name") + " (schema is in the dialect)", null) { SchemaSubset.check(schema) }
            report.check(vector.text("name"), vector.getValue("valid").jsonPrimitive.content.toBoolean()) {
                SchemaSubset.isValid(schema, vector.getValue("instance"))
            }
        }
        report.finishCounting(2)
    }

    @Test
    fun `a literal outside the json number grammar is no number and no integer`() {
        val number = parseJson("""{"type":"number"}""")
        val integer = parseJson("""{"type":"integer"}""")
        for (literal in listOf("1d", "1f", "0x1p3", "01", "+1", "1.", "NaN", "Infinity")) {
            val instance = parseJson("[$literal]").jsonArray[0]
            assertFalse(SchemaSubset.isValid(number, instance), literal)
            assertFalse(SchemaSubset.isValid(integer, instance), literal)
        }
        assertTrue(SchemaSubset.isValid(integer, parseJson("1e2")))
        assertEquals("enum", SchemaSubset.check(parseJson("""{"type":"integer","enum":[1d]}""")))
    }

    @Test
    fun `a type array is accepted`() {
        assertNull(SchemaSubset.check(parseJson("""{"type":["string","null"]}""")))
    }

    @Test
    fun `depth nine is refused and depth eight is not`() {
        fun nested(depth: Int): String =
            if (depth == 1) {
                """{"type":"string"}"""
            } else {
                """{"type":"array","items":${nested(depth - 1)}}"""
            }
        assertNull(SchemaSubset.check(parseJson(nested(8))))
        assertEquals(SchemaSubset.RULE_DEPTH, SchemaSubset.check(parseJson(nested(9))))
    }

    @Test
    fun `a reference keyword is refused but a url inside a description is fine`() {
        val dollar = '$'
        assertEquals(
            "${dollar}ref",
            SchemaSubset.check(parseJson("""{"${dollar}ref":"https://evil.example/s.json"}""")),
        )
        assertTrue(
            SchemaSubset.isInDialect(
                parseJson("""{"type":"string","description":"see https://example.com/x"}"""),
            ),
        )
    }
}
