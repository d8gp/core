package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.connections.interfaces.EventsInterface
import com.d8gp.plugins.protocol.connections.interfaces.LlmInterface
import com.d8gp.plugins.protocol.connections.interfaces.MessagesInterface
import com.d8gp.plugins.protocol.connections.interfaces.TaskInterface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val MEBIBYTE = 1_048_576L

const val MAX_CALL_PARAMS_BYTES = MEBIBYTE
const val DEFAULT_MAX_RESULT_BYTES = MEBIBYTE
const val MAX_CALL_MS = 120_000L

// Returns why the params are refused, or null when they are acceptable.
typealias RequestCheck = (params: JsonObject) -> String?

data class InterfaceSpec(
    val id: String,
    val major: Long,
    val dataFlow: DataFlow,
    val maxResultBytes: Long,
    val methods: Map<String, RequestCheck>,
    // Called by the host itself, so it can never be the subject of a
    // connection.
    val hostOnly: Boolean = false,
)

object InterfaceSpecs {
    const val MAX_LLM_SYSTEM_BYTES = 32_768
    const val MAX_LLM_MESSAGES = 8
    const val MAX_MESSAGES_LIMIT = 100L
    const val MAX_EVENTS_LIMIT = 200L

    private val LLM_ROLES = setOf("user", "assistant")

    val llm = InterfaceSpec(
        id = LlmInterface.ID,
        major = LlmInterface.MAJOR,
        dataFlow = DataFlow.RequestBearing,
        maxResultBytes = MEBIBYTE,
        methods = mapOf(LlmInterface.COMPLETE to ::checkLlmComplete),
    )

    val messages = InterfaceSpec(
        id = MessagesInterface.ID,
        major = MessagesInterface.MAJOR,
        dataFlow = DataFlow.ResponseBearing,
        maxResultBytes = 2 * MEBIBYTE,
        methods = mapOf(MessagesInterface.LIST to { params -> checkLimit(params, MAX_MESSAGES_LIMIT) }),
    )

    val events = InterfaceSpec(
        id = EventsInterface.ID,
        major = EventsInterface.MAJOR,
        dataFlow = DataFlow.ResponseBearing,
        maxResultBytes = 2 * MEBIBYTE,
        methods = mapOf(EventsInterface.LIST to { params -> checkLimit(params, MAX_EVENTS_LIMIT) }),
    )

    val task = InterfaceSpec(
        id = TaskInterface.ID,
        major = TaskInterface.MAJOR,
        dataFlow = DataFlow.ResponseBearing,
        maxResultBytes = MEBIBYTE,
        methods = mapOf(TaskInterface.RUN to { null }, TaskInterface.STATUS to { null }),
        hostOnly = true,
    )

    private val all: List<InterfaceSpec> = listOf(llm, messages, events, task)

    fun find(id: String, major: Long): InterfaceSpec? = all.firstOrNull { it.id == id && it.major == major }

    fun isKnown(id: String): Boolean = all.any { it.id == id }

    fun isHostOnly(id: String): Boolean = all.any { it.id == id && it.hostOnly }

    fun maxResultBytes(id: String, major: Long): Long = find(id, major)?.maxResultBytes ?: DEFAULT_MAX_RESULT_BYTES

    private fun checkLimit(params: JsonObject, max: Long): String? {
        val limit = params["limit"] ?: return null
        return if (limit.integerIn(1..max) == null) "limit must be an integer from 1 to $max" else null
    }

    private fun checkLlmComplete(params: JsonObject): String? {
        val system = params["system"]
        if (system != null) {
            val text = system.stringOrNull() ?: return "system must be a string"
            if (utf8Length(text) > MAX_LLM_SYSTEM_BYTES) return "system must be at most $MAX_LLM_SYSTEM_BYTES bytes"
        }

        val messages = params["messages"] as? JsonArray
        if (messages == null || messages.isEmpty() || messages.size > MAX_LLM_MESSAGES) {
            return "messages must be an array of 1 to $MAX_LLM_MESSAGES entries"
        }
        for (message in messages) {
            val entry = message as? JsonObject ?: return "each message must be an object"
            if (entry["role"].stringOrNull() !in LLM_ROLES) return "message role must be user or assistant"
            if (entry["content"].stringOrNull() == null) return "message content must be a string"
        }
        if ((messages.last() as JsonObject)["role"].stringOrNull() != "user") return "the last message must be from user"

        val output = params["output"] as? JsonObject ?: return "output is required"
        if (output["name"].stringOrNull().isNullOrEmpty()) return "output.name must be a non-empty string"
        val schema = output["schema"] ?: return "output.schema is required"
        SchemaSubset.check(schema)?.let { return "output.schema is outside the dialect: $it" }

        val model = params["model"]
        if (model != null && model.stringOrNull() !in ModelTiers.ORDER) {
            return "model must be one of ${ModelTiers.ORDER.joinToString(", ")}"
        }
        if (params["maxTokens"].integerIn(1..MAX_SAFE_INTEGER) == null) {
            return "maxTokens must be an integer of at least 1"
        }
        return null
    }
}
