package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ProtocolJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BudgetMeterTest {
    private val provider = "plugin-provider"

    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("budget.json", 72)
        for (vector in vectorList(VectorsGenerated.BUDGET)) {
            report.check(vector.text("name"), emptyList()) { disagreements(vector) }
        }
        report.finish()
    }

    @Test
    fun `settling an unknown or already settled call is a mistake of the host`() {
        val meter = BudgetMeter { 0 }
        fun assertUnknown(callId: String) {
            val error = assertFailsWith<BudgetMeterException>(callId) { meter.settle(callId, null) }
            assertEquals(BudgetMeterException.UNKNOWN_CALL, error.code)
        }
        assertUnknown("never-admitted")
        meter.admit(AdmitRequest("cn_a", provider, "c1", 10, 10, 10, ModelTiers.FAST), Budget())
        assertEquals(1, meter.settle("c1", null).requestsToday)
        assertUnknown("c1")
    }

    @Test
    fun `a time that is not a count is refused`() {
        assertInvalidAdmit(BudgetMeter { -1 }, request("c1"), Budget())
        assertInvalidAdmit(BudgetMeter { MAX_SAFE_INTEGER + 1 }, request("c1"), Budget())
    }

    @Test
    fun `a stored budget never gets a limit it does not state`() {
        val read = ProtocolJson.decodeFromString(
            Budget.serializer(),
            """{"requestsPerMinute":1.5,"requestsPerDay":"500","maxInputBytes":null,"tokensPerDay":-1,
                "maxTier":"turbo","maxCallMs":"soon","maxConcurrent":0}""",
        )
        val none = BudgetSerializer.NOT_A_LIMIT
        assertEquals(
            Budget(
                requestsPerMinute = none,
                requestsPerDay = none,
                maxTokensPerRequest = none,
                maxInputBytes = none,
                tokensPerDay = none,
                maxTier = "",
                maxCallMs = MAX_CALL_MS,
                maxConcurrent = 1,
            ),
            read,
        )
        assertEquals(read, ProtocolJson.decodeFromString(Budget.serializer(), "{}"))
        assertEquals(
            Budget(),
            ProtocolJson.decodeFromString(
                Budget.serializer(),
                ProtocolJson.encodeToString(Budget.serializer(), Budget()),
            ),
        )
        assertEquals(
            ProviderCap(requestsPerDay = 7, tokensPerDay = none),
            ProtocolJson.decodeFromString(ProviderCap.serializer(), """{"requestsPerDay":7,"tokensPerDay":1.5}"""),
        )
    }

    private fun disagreements(vector: JsonObject): List<String> {
        var now = 0L
        val meter = BudgetMeter { now }
        val budget = ProtocolJson.decodeFromJsonElement(Budget.serializer(), vector.getValue("budget"))
        val cap = vector["providerCap"]?.takeIf { it !is JsonNull }
            ?.let { ProtocolJson.decodeFromJsonElement(ProviderCap.serializer(), it) }

        return vector.getValue("steps").jsonArray.map { it.jsonObject }.mapIndexedNotNull { i, step ->
            now = step.countOrInvalid("atMs")
            val expected = CanonicalJson.encode(step.getValue("expect"))
            val actual = try {
                when (step.text("op")) {
                    "admit" -> admit(meter, step, budget, cap)
                    "settle" -> settle(meter, step, cap != null)
                    else -> "unknown op ${step.text("op")}"
                }
            } catch (e: BudgetMeterException) {
                """{"error":"${e.code}"}"""
            }
            if (actual == expected) null else "step $i: expected $expected but was $actual"
        }
    }

    private fun admit(meter: BudgetMeter, step: JsonObject, budget: Budget, cap: ProviderCap?): String {
        val request = AdmitRequest(
            connection = step.textOrNull("connection") ?: "cn_a",
            provider = provider,
            callId = step.text("callId"),
            maxTokens = step.countOrInvalid("maxTokens"),
            inputBytes = step.countOrInvalid("inputBytes"),
            requestBytes = step.countOrInvalid("requestBytes"),
            tier = step.textOrNull("tier").orEmpty(),
        )
        return when (val admission = meter.admit(request, budget, cap)) {
            is Admission.Admitted ->
                """{"admitted":true,"reservedTokens":${admission.reservedTokens},"waitMs":${admission.waitMs}}"""
            is Admission.Refused ->
                """{"admitted":false,"budget":"${admission.budget}","code":"${admission.code}"}"""
        }
    }

    private fun settle(meter: BudgetMeter, step: JsonObject, withProvider: Boolean): String {
        val settlement = meter.settle(step.text("callId"), tokenUsageOf(step["usage"]))
        val providerPart = if (withProvider) {
            ""","providerRequestsToday":${settlement.providerRequestsToday}""" +
                ""","providerTokensToday":${settlement.providerTokensToday}"""
        } else {
            ""
        }
        return """{"chargedTokens":${settlement.chargedTokens}$providerPart""" +
            ""","requestsToday":${settlement.requestsToday},"tokensToday":${settlement.tokensToday}}"""
    }

    private fun request(
        callId: String,
        maxTokens: Long = 8000,
        inputBytes: Long = 0,
        requestBytes: Long = 0,
        tier: String = ModelTiers.FAST,
    ) = AdmitRequest("cn_a", provider, callId, maxTokens, inputBytes, requestBytes, tier)

    private fun assertInvalidAdmit(meter: BudgetMeter, request: AdmitRequest, budget: Budget) {
        val error = assertFailsWith<BudgetMeterException>(request.toString()) { meter.admit(request, budget) }
        assertEquals(BudgetMeterException.INVALID_ADMIT, error.code)
    }

    @Test
    fun `a negative size is refused and lowers no counter`() {
        val budget = Budget(tokensPerDay = 10_000)
        val meter = BudgetMeter { 0 }
        assertInvalidAdmit(meter, request("n1", maxTokens = -1_000_000), budget)
        assertInvalidAdmit(meter, request("n2", requestBytes = -3_000_000), budget)
        assertInvalidAdmit(meter, request("n3", inputBytes = -1), budget)

        assertIs<Admission.Admitted>(meter.admit(request("c1"), budget))
        assertEquals(
            Admission.Refused(BudgetDetails.TOKENS_PER_DAY),
            meter.admit(request("c2"), budget),
        )
    }

    @Test
    fun `a size outside the safe integer range is refused before it can overflow`() {
        val unlimited = Budget(
            maxTokensPerRequest = MAX_SAFE_INTEGER,
            maxInputBytes = MAX_SAFE_INTEGER,
            tokensPerDay = MAX_SAFE_INTEGER,
        )
        val meter = BudgetMeter { 0 }
        assertInvalidAdmit(meter, request("o1", maxTokens = Long.MAX_VALUE, requestBytes = 30), unlimited)
        assertInvalidAdmit(meter, request("o2", maxTokens = MAX_SAFE_INTEGER + 1), unlimited)
        assertInvalidAdmit(meter, request("o3", requestBytes = Long.MAX_VALUE), unlimited)

        assertEquals(
            Admission.Refused(BudgetDetails.TOKENS_PER_DAY),
            meter.admit(request("o4", maxTokens = MAX_SAFE_INTEGER, requestBytes = 3), unlimited),
        )
        assertEquals(
            Admission.Admitted(waitMs = 0, reservedTokens = MAX_SAFE_INTEGER),
            meter.admit(request("o5", maxTokens = MAX_SAFE_INTEGER - 1, requestBytes = 3), unlimited),
        )
    }

    @Test
    fun `a limit that is not a count admits nothing`() {
        val limits = listOf(
            Budget(maxTokensPerRequest = -1) to BudgetDetails.TOKENS_PER_REQUEST,
            Budget(maxTokensPerRequest = Long.MAX_VALUE) to BudgetDetails.TOKENS_PER_REQUEST,
            Budget(maxInputBytes = -1) to BudgetDetails.INPUT_BYTES,
            Budget(requestsPerDay = -1) to BudgetDetails.REQUESTS_PER_DAY,
            Budget(tokensPerDay = Long.MAX_VALUE) to BudgetDetails.TOKENS_PER_DAY,
            Budget(requestsPerMinute = 0) to BudgetDetails.REQUESTS_PER_MINUTE,
            Budget(requestsPerMinute = -3) to BudgetDetails.REQUESTS_PER_MINUTE,
            Budget(maxTier = "turbo") to BudgetDetails.TIER,
        )
        for ((budget, detail) in limits) {
            assertEquals(Admission.Refused(detail), BudgetMeter { 0 }.admit(request("c1", maxTokens = 1), budget))
        }
        assertEquals(
            Admission.Refused(BudgetDetails.PROVIDER_CAP),
            BudgetMeter { 0 }.admit(request("c1", maxTokens = 1), Budget(), ProviderCap(500, tokensPerDay = -1)),
        )
    }

    @Test
    fun `an unknown tier is refused`() {
        for (tier in listOf("", "FAST", "turbo", "best ")) {
            assertEquals(
                Admission.Refused(BudgetDetails.TIER),
                BudgetMeter { 0 }.admit(request("c1", tier = tier), Budget(maxTier = ModelTiers.BEST)),
                tier,
            )
        }
    }

    @Test
    fun `a call id that is still open cannot be admitted again`() {
        val budget = Budget(tokensPerDay = 20_000)
        val meter = BudgetMeter { 0 }
        assertIs<Admission.Admitted>(meter.admit(request("same"), budget))
        val error = assertFailsWith<BudgetMeterException> { meter.admit(request("same"), budget) }
        assertEquals(BudgetMeterException.CALL_ID_IN_USE, error.code)

        assertEquals(1, meter.settle("same", TokenUsage(1, 1)).requestsToday)
        assertIs<Admission.Admitted>(meter.admit(request("same"), budget))
        assertIs<Admission.Admitted>(meter.admit(request("other"), budget))
        assertEquals(Admission.Refused(BudgetDetails.TOKENS_PER_DAY), meter.admit(request("third"), budget))
    }

    @Test
    fun `usage that is not two counts is no report`() {
        val reports = listOf(
            TokenUsage(-5, 10),
            TokenUsage(10, -5),
            TokenUsage(-1_000_000, -1_000_000),
            TokenUsage(Long.MAX_VALUE, 1),
            TokenUsage(MAX_SAFE_INTEGER, 1),
        )
        for (usage in reports) {
            val meter = BudgetMeter { 0 }
            meter.admit(request("c1", maxTokens = 100, requestBytes = 30), Budget())
            val settlement = meter.settle("c1", usage)
            assertEquals(100, settlement.chargedTokens, usage.toString())
            assertEquals(100, settlement.tokensToday, usage.toString())
        }
    }

    @Test
    fun `earlier days are forgotten once their calls have settled`() {
        var now = 0L
        val meter = BudgetMeter { now }
        val budget = Budget(requestsPerDay = 1)
        val dayMs = 86_400_000L

        assertIs<Admission.Admitted>(meter.admit(request("day0"), budget))
        now = dayMs
        assertIs<Admission.Admitted>(meter.admit(request("day1"), budget))
        meter.settle("day1", null)

        meter.forgetDaysBefore(1)
        assertEquals(1, meter.settle("day0", null).requestsToday, "a day with an open call is kept")
        assertEquals(Admission.Refused(BudgetDetails.REQUESTS_PER_DAY), meter.admit(request("again"), budget))

        // The clock is held at day 1 to see that its counters are gone.
        meter.forgetDaysBefore(2)
        assertIs<Admission.Admitted>(meter.admit(request("again"), budget))
    }
}
