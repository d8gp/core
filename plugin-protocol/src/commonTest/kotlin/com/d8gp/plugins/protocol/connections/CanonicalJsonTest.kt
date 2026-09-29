package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalJsonTest {
    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("canonical-json.json", 35)
        for (vector in vectorList(VectorsGenerated.CANONICAL_JSON)) {
            val name = vector.text("name")
            val value = vector.getValue("value")
            val error = vector.textOrNull("error")
            if (error != null) {
                report.check(name, "error $error") {
                    try {
                        "ok ${CanonicalJson.encode(value)}"
                    } catch (e: CanonicalJsonException) {
                        "error ${e.code}"
                    }
                }
                continue
            }
            report.check(
                name,
                Triple(vector.text("canonical"), vector.long("utf8Bytes"), vector.text("sha256")),
            ) {
                val canonical = CanonicalJson.encode(value)
                Triple(canonical, utf8Length(canonical).toLong(), CanonicalJson.sha256Hex(value))
            }
        }
        report.finish()
    }

    @Test
    fun `an unpaired surrogate is refused`() {
        for (text in listOf("a\uD83Db", "\uDE00", "tail\uD83D")) {
            val error = assertFailsWith<CanonicalJsonException> { CanonicalJson.encode(JsonPrimitive(text)) }
            assertEquals(CanonicalJsonException.LONE_SURROGATE, error.code)
        }
    }

    @Test
    fun `integral numbers written with a fraction or an exponent are integers`() {
        assertEquals("[1,1000,0]", CanonicalJson.encode(parseJson("[1.0, 1e3, -0]")))
    }

    @Test
    fun `a literal outside the json number grammar is not a number`() {
        for (literal in listOf("1d", "1f", "1L", "0x1p3", "0x10", "01", "+1", "1.", ".5", "1e", "NaN", "Infinity")) {
            val error = assertFailsWith<CanonicalJsonException>(literal) {
                CanonicalJson.encode(parseJson("[$literal]"))
            }
            assertEquals(CanonicalJsonException.NON_INTEGER_NUMBER, error.code, literal)
        }
    }
}
