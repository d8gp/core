package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.connections.interfaces.LlmStopReasons
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface LlmResultVerdict {
    // `result` is what the consumer receives: the provider's result with
    // `stopReason` and `output` as the host decided them.
    data class Deliver(val stopReason: String, val output: JsonElement, val result: JsonObject) : LlmResultVerdict

    // Envelope code invalid-response. Nothing of the result is delivered.
    data object InvalidResponse : LlmResultVerdict
}

// What the host does with the result of a d8gp.llm complete call before the
// consumer sees it. A provider's word that its output fits is not trusted.
object LlmResult {
    private const val STOP_REASON = "stopReason"
    private const val OUTPUT = "output"

    // `schema` is output.schema of the request, which passed SchemaSubset.check
    // when the call was authorized.
    fun check(schema: JsonElement, result: JsonElement?): LlmResultVerdict {
        val fields = result as? JsonObject ?: return LlmResultVerdict.InvalidResponse
        val stopReason = fields[STOP_REASON].stringOrNull()?.takeIf { it in LlmStopReasons.ALL }
            ?: return LlmResultVerdict.InvalidResponse

        if (stopReason != LlmStopReasons.END) return deliver(fields, stopReason, JsonNull)
        val output = fields[OUTPUT] ?: JsonNull
        return if (SchemaSubset.isValid(schema, output)) {
            deliver(fields, LlmStopReasons.END, output)
        } else {
            deliver(fields, LlmStopReasons.INVALID_OUTPUT, JsonNull)
        }
    }

    private fun deliver(fields: JsonObject, stopReason: String, output: JsonElement): LlmResultVerdict.Deliver {
        val decided = mapOf(STOP_REASON to JsonPrimitive(stopReason), OUTPUT to output)
        return LlmResultVerdict.Deliver(stopReason, output, JsonObject(fields + decided))
    }
}
