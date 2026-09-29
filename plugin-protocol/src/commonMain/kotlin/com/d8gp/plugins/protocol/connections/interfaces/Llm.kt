package com.d8gp.plugins.protocol.connections.interfaces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object LlmInterface {
    const val ID = "d8gp.llm"
    const val MAJOR = 1L
    const val COMPLETE = "complete"
}

object LlmStopReasons {
    const val END = "end"
    const val MAX_TOKENS = "max_tokens"
    const val REFUSAL = "refusal"
    const val INVALID_OUTPUT = "invalid_output"
    const val RATE_LIMITED = "rate_limited"
    const val PROVIDER_ERROR = "provider_error"
    const val UNAVAILABLE = "unavailable"

    val ALL: Set<String> = setOf(
        END,
        MAX_TOKENS,
        REFUSAL,
        INVALID_OUTPUT,
        RATE_LIMITED,
        PROVIDER_ERROR,
        UNAVAILABLE,
    )
}

@Serializable
data class LlmMessage(
    val role: String, // "user" | "assistant"
    val content: String,
)

@Serializable
data class LlmOutputSpec(
    val name: String,
    val schema: JsonObject,
)

@Serializable
data class LlmCompleteRequest(
    val system: String? = null,
    val messages: List<LlmMessage>,
    val output: LlmOutputSpec,
    val model: String? = null, // tier hint, never a vendor id
    val maxTokens: Long,
    val temperature: Double? = null,
)

@Serializable
data class LlmUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val estimated: Boolean = false,
)

@Serializable
data class LlmCompleteResponse(
    val stopReason: String,
    val output: JsonElement? = null, // non-null only with stopReason "end"
    val model: String? = null,
    val usage: LlmUsage? = null,
    val detail: String? = null,
)
