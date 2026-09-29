package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ProtocolJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionPolicyTest {
    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("policy.json", 103)
        for (vector in vectorList(VectorsGenerated.POLICY)) {
            val expect = vector.getValue("expect")
            val expected = if (expect is JsonObject) {
                "refuse ${expect.text("code")}"
            } else {
                "deliver ${CanonicalJson.encode(vector.getValue("serve"))}"
            }
            report.check(vector.text("name"), expected) {
                val call = vector.getValue("call").jsonObject
                val record = vector.getValue("record").takeIf { it !is JsonNull }
                    ?.let { ProtocolJson.decodeFromJsonElement(ConnectionRecord.serializer(), it) }
                val decision = ConnectionPolicy.authorize(
                    authenticated = call.text("authenticated"),
                    request = call.getValue("request"),
                    requestBytes = call.countOrInvalid("requestBytes"),
                    record = record,
                )
                when (decision) {
                    is CallDecision.Deliver -> "deliver ${CanonicalJson.encode(decision.serve)}"
                    is CallDecision.Refuse -> "refuse ${decision.code}"
                }
            }
        }
        report.finish()
    }

    @Test
    fun `every refusal code is a known envelope code`() {
        for (vector in vectorList(VectorsGenerated.POLICY)) {
            val expect = vector.getValue("expect") as? JsonObject ?: continue
            assertTrue(expect.text("code") in ConnectionErrorCodes.ALL, vector.text("name"))
        }
    }

    @Test
    fun `result caps follow the interface`() {
        assertFalse(ConnectionPolicy.resultTooLarge("d8gp.llm", 1, 1_048_576))
        assertTrue(ConnectionPolicy.resultTooLarge("d8gp.llm", 1, 1_048_577))
        assertFalse(ConnectionPolicy.resultTooLarge("d8gp.messages", 1, 2_097_152))
        assertTrue(ConnectionPolicy.resultTooLarge("d8gp.events", 1, 2_097_153))
        assertTrue(ConnectionPolicy.resultTooLarge("d8gp.example.echo", 1, 1_048_577))
    }

    private val llmRecord = vectorList(VectorsGenerated.POLICY)
        .first { it.getValue("expect") !is JsonObject && it.getValue("record").jsonObject.text("interface") == "d8gp.llm" }
        .let { ProtocolJson.decodeFromJsonElement(ConnectionRecord.serializer(), it.getValue("record")) }

    private val llmParams = parseJson(
        """{"messages":[{"role":"user","content":"hi"}],
            "output":{"name":"o","schema":{"type":"object","additionalProperties":false}},"maxTokens":1}""",
    )

    private fun call(iface: String, version: Long, method: String, params: JsonElement = JsonObject(emptyMap())) =
        buildJsonObject {
            put("interface", iface)
            put("version", version)
            put("method", method)
            put("params", params)
        }

    private fun decide(record: ConnectionRecord, request: JsonObject): CallDecision =
        ConnectionPolicy.authorize(record.consumer.plugin, request, requestBytes = 10, record = record)

    private fun refusal(record: ConnectionRecord, request: JsonObject): String =
        assertIs<CallDecision.Refuse>(decide(record, request)).code

    @Test
    fun `the record used by the policy tests delivers`() {
        assertIs<CallDecision.Deliver>(decide(llmRecord, call("d8gp.llm", 1, "complete", llmParams)))
    }

    @Test
    fun `a record for a host-only interface is never delivered to a plugin`() {
        for (method in listOf("run", "status")) {
            val record = llmRecord.copy(iface = "d8gp.task", methods = listOf("run", "status"))
            assertEquals(ConnectionErrorCodes.NOT_WIRED, refusal(record, call("d8gp.task", 1, method)), method)
        }
        val laterMajor = llmRecord.copy(iface = "d8gp.task", major = 2, methods = listOf("run"))
        assertEquals(ConnectionErrorCodes.NOT_WIRED, refusal(laterMajor, call("d8gp.task", 2, "run")))
    }

    @Test
    fun `a major this host does not know of a known interface is refused`() {
        val record = llmRecord.copy(major = 2)
        val freeText = parseJson("""{"free":"text"}""")
        assertEquals(
            ConnectionErrorCodes.VERSION_MISMATCH,
            refusal(record, call("d8gp.llm", 2, "complete", freeText)),
        )
    }

    @Test
    fun `a method the interface does not have is refused even when the record grants it`() {
        val record = llmRecord.copy(methods = listOf("complete", "embed"))
        assertEquals(ConnectionErrorCodes.INVALID_REQUEST, refusal(record, call("d8gp.llm", 1, "embed", llmParams)))
    }

    @Test
    fun `an interface this host does not know has no request check`() {
        val record = llmRecord.copy(iface = "d8gp.example.echo", major = 3, methods = listOf("echo"))
        assertIs<CallDecision.Deliver>(decide(record, call("d8gp.example.echo", 3, "echo")))
    }

    private fun echoRecordWithMajor(major: String): ConnectionRecord {
        val stored = vectorList(VectorsGenerated.POLICY).first { it.getValue("expect") !is JsonObject }
            .getValue("record").jsonObject
        val edited = stored + mapOf(
            "interface" to JsonPrimitive("d8gp.example.echo"),
            "methods" to parseJson("""["echo"]"""),
            "major" to parseJson(major),
        )
        return ProtocolJson.decodeFromJsonElement(ConnectionRecord.serializer(), JsonObject(edited))
    }

    @Test
    fun `a stored major is an integer by value`() {
        for (major in listOf("1", "1.0", "1e0", "10e-1")) {
            assertIs<CallDecision.Deliver>(
                decide(echoRecordWithMajor(major), call("d8gp.example.echo", 1, "echo")),
                major,
            )
        }
    }

    @Test
    fun `a stored major that is not an integer of at least 1 matches no call`() {
        for (major in listOf("\"1\"", "1.5", "0", "-1", "null", "true", "[1]", "1e400", "9007199254740992")) {
            val record = echoRecordWithMajor(major)
            assertEquals(ConnectionMajorSerializer.NOT_A_MAJOR, record.major, major)
            assertEquals(
                ConnectionErrorCodes.VERSION_MISMATCH,
                refusal(record, call("d8gp.example.echo", 1, "echo")),
                major,
            )
        }
    }

    @Test
    fun `a major is stored as the number it is`() {
        val record = echoRecordWithMajor("3")
        val stored = ProtocolJson.encodeToJsonElement(ConnectionRecord.serializer(), record).jsonObject
        assertEquals(JsonPrimitive(3), stored.getValue("major"))
    }

    @Test
    fun `the deadline is never below one millisecond`() {
        for (maxCallMs in listOf(-5L, 0L, Long.MIN_VALUE)) {
            val record = llmRecord.copy(budget = llmRecord.budget.copy(maxCallMs = maxCallMs))
            val decision = assertIs<CallDecision.Deliver>(decide(record, call("d8gp.llm", 1, "complete", llmParams)))
            assertEquals(1, decision.deadlineMs)
            assertEquals("1", decision.serve.getValue("deadlineMs").toString())
        }
    }

    @Test
    fun `data flows as the design states`() {
        assertEquals(DataFlow.RequestBearing, InterfaceSpecs.llm.dataFlow)
        assertEquals(DataFlow.ResponseBearing, InterfaceSpecs.messages.dataFlow)
        assertEquals(DataFlow.ResponseBearing, InterfaceSpecs.events.dataFlow)
        assertTrue(InterfaceSpecs.isHostOnly("d8gp.task"))
        assertFalse(InterfaceSpecs.isHostOnly("d8gp.llm"))
    }

    @Test
    fun `envelopes have the wire shape of design 2_2`() {
        assertEquals(
            """{"ok":true,"result":{},"callId":"c_1"}""",
            okEnvelope(JsonObject(emptyMap()), "c_1").toString(),
        )
        assertEquals(
            """{"ok":false,"error":{"code":"not-wired","message":"m","retryable":false,"retryAfterMs":0,"detail":{}},"callId":"c_1"}""",
            errorEnvelope(ConnectionErrorCodes.NOT_WIRED, "m", "c_1").toString(),
        )
        assertEquals(
            """{"budget":"tier"}""",
            budgetExceededEnvelope(BudgetDetails.TIER).getValue("error").jsonObject.getValue("detail").toString(),
        )
    }

    @Test
    fun `bridge rejections map to envelope codes`() {
        val report = VectorReport("rejection-mapping.json", 48)
        vectorList(VectorsGenerated.REJECTION_MAPPING).forEachIndexed { i, vector ->
            report.check("#$i <${vector.text("message")}>", vector.text("code")) {
                codeForBridgeRejection(vector.text("message"))
            }
        }
        report.finish()
    }
}
