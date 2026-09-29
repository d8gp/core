package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LlmResultTest {
    private val vectors = vectorList(VectorsGenerated.LLM_RESULT)

    private fun described(verdict: LlmResultVerdict): String = when (verdict) {
        is LlmResultVerdict.InvalidResponse -> "refuse ${ConnectionErrorCodes.INVALID_RESPONSE}"
        is LlmResultVerdict.Deliver ->
            "deliver ${verdict.stopReason} ${CanonicalJson.encode(verdict.output)} " +
                "as ${verdict.result["stopReason"]} ${verdict.result["output"]?.let(CanonicalJson::encode)}"
    }

    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("llm-result.json", 48)
        for (vector in vectors) {
            val expect = vector.getValue("expect").jsonObject
            val deliver = expect["deliver"] as? JsonObject
            val expected = if (deliver == null) {
                "refuse ${expect.text("code")}"
            } else {
                val stopReason = deliver.text("stopReason")
                val output = CanonicalJson.encode(deliver.getValue("output"))
                "deliver $stopReason $output as \"$stopReason\" $output"
            }
            report.check(vector.text("name"), expected) {
                described(LlmResult.check(vector.getValue("schema"), vector["result"]))
            }
        }
        report.finish()
    }

    @Test
    fun `every schema of the vectors is in the dialect`() {
        for (vector in vectors) {
            assertEquals(null, SchemaSubset.check(vector.getValue("schema")), vector.text("name"))
        }
    }

    @Test
    fun `an absent result is an invalid response`() {
        assertIs<LlmResultVerdict.InvalidResponse>(LlmResult.check(parseJson("""{"type":"null"}"""), null))
    }

    @Test
    fun `the members the rule does not decide are delivered as the provider sent them`() {
        val schema = parseJson("""{"type":"string"}""")
        val usage = """{"inputTokens":5,"outputTokens":7,"estimated":true}"""
        val sent = parseJson("""{"stopReason":"end","output":1,"model":"m","usage":$usage,"vendor":{"id":"x"}}""")

        val verdict = assertIs<LlmResultVerdict.Deliver>(LlmResult.check(schema, sent))

        assertEquals("invalid_output", verdict.stopReason)
        assertEquals(JsonNull, verdict.output)
        assertEquals(
            parseJson("""{"stopReason":"invalid_output","output":null,"model":"m","usage":$usage,"vendor":{"id":"x"}}"""),
            verdict.result,
        )
    }

    @Test
    fun `an output dropped beside another stop reason leaves no trace in the result`() {
        val sent = parseJson("""{"stopReason":"refusal","output":"I cannot help with that."}""")
        val verdict = assertIs<LlmResultVerdict.Deliver>(LlmResult.check(parseJson("""{"type":"string"}"""), sent))
        assertEquals(parseJson("""{"stopReason":"refusal","output":null}"""), verdict.result)
    }
}
