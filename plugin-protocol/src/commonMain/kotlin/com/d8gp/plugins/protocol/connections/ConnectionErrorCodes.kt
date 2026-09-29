package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Typed failures travel inside a RESULT envelope, because every plugin-side
// bridge drops the code of a rejected call.
object ConnectionErrorCodes {
    const val NOT_WIRED = "not-wired"
    const val REVOKED = "revoked"
    const val NEEDS_RECONSENT = "needs-reconsent"
    const val METHOD_NOT_GRANTED = "method-not-granted"
    const val VERSION_MISMATCH = "version-mismatch"
    const val BUDGET_EXCEEDED = "budget-exceeded"
    const val INVALID_REQUEST = "invalid-request"
    const val PAYLOAD_TOO_LARGE = "payload-too-large"
    const val DEADLINE_EXCEEDED = "deadline-exceeded"
    const val CANCELLED = "cancelled"
    const val PROVIDER_UNAVAILABLE = "provider-unavailable"
    const val PROVIDER_ERROR = "provider-error"
    const val INVALID_RESPONSE = "invalid-response"
    const val UNAVAILABLE = "unavailable"

    const val EXEC_NOT_DECLARED = "exec-not-declared"
    const val EXEC_NOT_GRANTED = "exec-not-granted"
    const val EXEC_SUSPENDED = "exec-suspended"
    const val NOT_IN_CALL = "not-in-call"
    const val CALL_QUOTA = "call-quota"
    const val EXEC_TIMEOUT = "exec-timeout"
    const val EXEC_OUTPUT_TOO_LARGE = "exec-output-too-large"

    val ALL: List<String> = listOf(
        NOT_WIRED,
        REVOKED,
        NEEDS_RECONSENT,
        METHOD_NOT_GRANTED,
        VERSION_MISMATCH,
        BUDGET_EXCEEDED,
        INVALID_REQUEST,
        PAYLOAD_TOO_LARGE,
        DEADLINE_EXCEEDED,
        CANCELLED,
        PROVIDER_UNAVAILABLE,
        PROVIDER_ERROR,
        INVALID_RESPONSE,
        UNAVAILABLE,
        EXEC_NOT_DECLARED,
        EXEC_NOT_GRANTED,
        EXEC_SUSPENDED,
        NOT_IN_CALL,
        CALL_QUOTA,
        EXEC_TIMEOUT,
        EXEC_OUTPUT_TOO_LARGE,
    )
}

object BudgetDetails {
    const val TIER = "tier"
    const val TOKENS_PER_REQUEST = "tokens-per-request"
    const val INPUT_BYTES = "input-bytes"
    const val REQUESTS_PER_DAY = "requests-per-day"
    const val TOKENS_PER_DAY = "tokens-per-day"
    const val PROVIDER_CAP = "provider-cap"
    const val REQUESTS_PER_MINUTE = "requests-per-minute"
}

private val RETRYABLE_CODES = setOf(
    ConnectionErrorCodes.DEADLINE_EXCEEDED,
    ConnectionErrorCodes.PROVIDER_UNAVAILABLE,
    ConnectionErrorCodes.PROVIDER_ERROR,
)

fun okEnvelope(result: JsonElement, callId: String?): JsonObject = buildJsonObject {
    put("ok", true)
    put("result", result)
    if (callId != null) put("callId", callId)
}

fun errorEnvelope(
    code: String,
    message: String,
    callId: String? = null,
    retryable: Boolean = code in RETRYABLE_CODES,
    retryAfterMs: Long = 0,
    detail: JsonObject = JsonObject(emptyMap()),
): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
        put("retryable", retryable)
        put("retryAfterMs", retryAfterMs)
        put("detail", detail)
    })
    if (callId != null) put("callId", callId)
}

fun budgetExceededEnvelope(budget: String, callId: String? = null): JsonObject = errorEnvelope(
    code = ConnectionErrorCodes.BUDGET_EXCEEDED,
    message = "budget exceeded: $budget",
    callId = callId,
    detail = buildJsonObject { put("budget", budget) },
)

// The bridge rejects with a message only. First match wins; comparisons are
// exact, case-sensitive and untrimmed.
fun codeForBridgeRejection(message: String): String = when {
    "[not-found]" in message || message.startsWith("no handler for") -> ConnectionErrorCodes.UNAVAILABLE
    message.endsWith("timed out") -> ConnectionErrorCodes.DEADLINE_EXCEEDED
    else -> ConnectionErrorCodes.PROVIDER_ERROR
}
