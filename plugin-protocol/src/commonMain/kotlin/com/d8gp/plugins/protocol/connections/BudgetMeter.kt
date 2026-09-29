package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val PACING_WINDOW_MS = 60_000L
private const val MAX_PACING_WAIT_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val BYTES_PER_ESTIMATED_TOKEN = 3

private fun isCount(value: Long): Boolean = value in 0..MAX_SAFE_INTEGER

private fun above(value: Long, cap: Long): Boolean = !isCount(cap) || value > cap

// Both terms are counts or sums of counts. A sum that leaves the range of Long
// stays at its top instead of turning negative.
private fun saturatingSum(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

data class AdmitRequest(
    val connection: String,
    val provider: String,
    val callId: String,
    val maxTokens: Long,
    val inputBytes: Long, // the model input only, compared with maxInputBytes
    val requestBytes: Long, // the whole request, the base of the token estimate
    val tier: String,
)

// A mistake of the host that drives the meter, never a verdict about a call.
class BudgetMeterException(val code: String, detail: String) : Exception("budget meter $code: $detail") {
    companion object {
        const val CALL_ID_IN_USE = "call-id-in-use"
        const val INVALID_ADMIT = "invalid-admit"
        const val UNKNOWN_CALL = "unknown-call"
    }
}

sealed interface Admission {
    // The broker holds the call for waitMs before it delivers.
    data class Admitted(val waitMs: Long, val reservedTokens: Long) : Admission

    data class Refused(val budget: String) : Admission {
        val code: String get() = ConnectionErrorCodes.BUDGET_EXCEEDED
    }
}

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
)

// The usage of a provider response, or null when it reports none that can be
// charged.
fun tokenUsageOf(usage: JsonElement?): TokenUsage? {
    val fields = usage as? JsonObject ?: return null
    val inputTokens = fields["inputTokens"].integerIn(0..MAX_SAFE_INTEGER) ?: return null
    val outputTokens = fields["outputTokens"].integerIn(0..MAX_SAFE_INTEGER) ?: return null
    return TokenUsage(inputTokens, outputTokens)
}

// Counters of the day the call was ADMITTED, after the charge.
data class Settlement(
    val chargedTokens: Long,
    val requestsToday: Long,
    val tokensToday: Long,
    val providerRequestsToday: Long,
    val providerTokensToday: Long,
)

// Not thread-safe: the broker admits and settles from one serialized context.
// maxConcurrent is enforced by the broker's queue, not here.
class BudgetMeter(private val nowMs: () -> Long) {
    private class DayCounters(var requests: Long = 0, var settledTokens: Long = 0, var reservedTokens: Long = 0) {
        val committedTokens: Long get() = saturatingSum(settledTokens, reservedTokens)
    }

    private class Reservation(
        val connection: String,
        val provider: String,
        val day: Long,
        val reservedTokens: Long,
        val maxTokens: Long,
        val estimatedTokens: Long,
    )

    private val connectionDays = mutableMapOf<Pair<String, Long>, DayCounters>()
    private val providerDays = mutableMapOf<Pair<String, Long>, DayCounters>()
    private val starts = mutableMapOf<String, ArrayDeque<Long>>()
    private val reservations = mutableMapOf<String, Reservation>()

    // Throws BudgetMeterException for a callId that is still open and for a
    // time or size that is not a count. A limit that is not a count admits
    // nothing.
    fun admit(request: AdmitRequest, budget: Budget, providerCap: ProviderCap? = null): Admission {
        if (request.callId in reservations) {
            throw BudgetMeterException(
                BudgetMeterException.CALL_ID_IN_USE,
                "call ${request.callId} is admitted and not settled",
            )
        }
        val now = nowMs()
        if (!listOf(now, request.maxTokens, request.inputBytes, request.requestBytes).all(::isCount)) {
            throw BudgetMeterException(
                BudgetMeterException.INVALID_ADMIT,
                "the time, maxTokens, inputBytes and requestBytes are integers from 0 to $MAX_SAFE_INTEGER",
            )
        }
        val day = now.floorDiv(DAY_MS)

        val tier = ModelTiers.rank(request.tier)
        if (tier < 0 || tier > ModelTiers.rank(budget.maxTier)) return Admission.Refused(BudgetDetails.TIER)
        if (above(request.maxTokens, budget.maxTokensPerRequest)) {
            return Admission.Refused(BudgetDetails.TOKENS_PER_REQUEST)
        }
        if (above(request.inputBytes, budget.maxInputBytes)) return Admission.Refused(BudgetDetails.INPUT_BYTES)

        val estimatedTokens = request.requestBytes / BYTES_PER_ESTIMATED_TOKEN
        val reservedTokens = request.maxTokens + estimatedTokens

        val connection = connectionDays[request.connection to day] ?: DayCounters()
        if (above(connection.requests + 1, budget.requestsPerDay)) {
            return Admission.Refused(BudgetDetails.REQUESTS_PER_DAY)
        }
        if (above(saturatingSum(connection.committedTokens, reservedTokens), budget.tokensPerDay)) {
            return Admission.Refused(BudgetDetails.TOKENS_PER_DAY)
        }

        val provider = providerDays[request.provider to day] ?: DayCounters()
        if (providerCap != null) {
            val overCap = above(provider.requests + 1, providerCap.requestsPerDay) ||
                above(saturatingSum(provider.committedTokens, reservedTokens), providerCap.tokensPerDay)
            if (overCap) return Admission.Refused(BudgetDetails.PROVIDER_CAP)
        }

        if (budget.requestsPerMinute !in 1..MAX_SAFE_INTEGER) {
            return Admission.Refused(BudgetDetails.REQUESTS_PER_MINUTE)
        }
        val window = starts[request.connection] ?: ArrayDeque()
        val start = paceStart(now, window, budget.requestsPerMinute)
        val waitMs = start - now
        if (waitMs > MAX_PACING_WAIT_MS) return Admission.Refused(BudgetDetails.REQUESTS_PER_MINUTE)

        window.addLast(start)
        while (window.size > budget.requestsPerMinute) window.removeFirst()
        starts[request.connection] = window

        for (counters in listOf(connection, provider)) {
            counters.requests += 1
            counters.reservedTokens = saturatingSum(counters.reservedTokens, reservedTokens)
        }
        connectionDays[request.connection to day] = connection
        providerDays[request.provider to day] = provider
        reservations[request.callId] = Reservation(
            connection = request.connection,
            provider = request.provider,
            day = day,
            reservedTokens = reservedTokens,
            maxTokens = request.maxTokens,
            estimatedTokens = estimatedTokens,
        )
        return Admission.Admitted(waitMs = waitMs, reservedTokens = reservedTokens)
    }

    // `usage` is null when the provider reported nothing, including a call
    // that failed or timed out. Usage is the provider's word: a report that is
    // not two counts is no report. Throws BudgetMeterException for a call
    // that is not admitted or is already settled.
    fun settle(callId: String, usage: TokenUsage?): Settlement {
        val reservation = reservations.remove(callId)
            ?: throw BudgetMeterException(BudgetMeterException.UNKNOWN_CALL, "no admitted call $callId to settle")
        val reported = usage?.let(::reportedTokens) ?: reservation.maxTokens
        val charged = maxOf(reported, reservation.estimatedTokens)

        val connection = connectionDays.getValue(reservation.connection to reservation.day)
        val provider = providerDays.getValue(reservation.provider to reservation.day)
        for (counters in listOf(connection, provider)) {
            counters.reservedTokens -= reservation.reservedTokens
            counters.settledTokens = saturatingSum(counters.settledTokens, charged)
        }
        return Settlement(
            chargedTokens = charged,
            requestsToday = connection.requests,
            tokensToday = connection.settledTokens,
            providerRequestsToday = provider.requests,
            providerTokensToday = provider.settledTokens,
        )
    }

    // Counters of earlier days can never be charged again once their calls
    // have settled.
    fun forgetDaysBefore(day: Long) {
        val open = reservations.values.map { it.day }.toSet()
        connectionDays.keys.removeAll { (_, d) -> d < day && d !in open }
        providerDays.keys.removeAll { (_, d) -> d < day && d !in open }
    }

    private fun paceStart(now: Long, window: ArrayDeque<Long>, requestsPerMinute: Long): Long {
        var start = now
        window.lastOrNull()?.let { start = maxOf(start, it) }
        if (window.size >= requestsPerMinute) {
            start = maxOf(start, window[(window.size - requestsPerMinute).toInt()] + PACING_WINDOW_MS)
        }
        return start
    }

    private fun reportedTokens(usage: TokenUsage): Long? {
        if (!isCount(usage.inputTokens) || !isCount(usage.outputTokens)) return null
        return (usage.inputTokens + usage.outputTokens).takeIf(::isCount)
    }
}
