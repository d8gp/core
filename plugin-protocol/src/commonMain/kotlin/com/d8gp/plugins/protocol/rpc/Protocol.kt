package com.d8gp.plugins.protocol.rpc

import com.d8gp.plugins.protocol.ProtocolJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Port of plugin-host-core/rpc/protocol.ts. Frames travel as JSON strings and
// must stay byte-compatible with the JS side: exact field names and ordering,
// v is the number 1, t is 'req'|'res'|'event'.

const val RPC_DEFAULT_TIMEOUT_MS = 30_000L
// Beyond this a single message is refused — media must move as handles, not
// bytes over the bridge.
const val RPC_MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

object RpcErrorCodes {
    const val TIMEOUT = "timeout"
    const val PERMISSION_DENIED = "permission-denied"
    const val NOT_FOUND = "not-found"
    const val INVALID_PARAMS = "invalid-params"
    const val PLUGIN_ERROR = "plugin-error"
    const val HOST_ERROR = "host-error"
    const val PLUGIN_UNLOADED = "plugin-unloaded"
    const val PAYLOAD_TOO_LARGE = "payload-too-large"
}

data class RpcError(val code: String, val message: String)

sealed class RpcMessage {
    abstract val plugin: String
}

data class RpcRequest(
    val id: String, // caller-unique, `${side}:${counter}`
    override val plugin: String, // plugin id — the namespace when iframes share one transport
    val method: String, // dotted: 'http.fetch', 'feed.fetchBatch'
    val params: JsonElement,
) : RpcMessage()

// ok=true carries result (JsonNull when the handler returned nothing — JS
// coerces undefined to null on the wire); ok=false carries error.
data class RpcResponse(
    val id: String,
    override val plugin: String,
    val ok: Boolean,
    val result: JsonElement = JsonNull,
    val error: RpcError? = null,
) : RpcMessage()

// Fire-and-forget (incoming-message push, logs). No response is expected.
data class RpcEvent(
    override val plugin: String,
    val event: String,
    val payload: JsonElement,
) : RpcMessage()

fun makeRequest(id: String, plugin: String, method: String, params: JsonElement): RpcRequest =
    RpcRequest(id = id, plugin = plugin, method = method, params = params)

fun makeOk(id: String, plugin: String, result: JsonElement): RpcResponse =
    RpcResponse(id = id, plugin = plugin, ok = true, result = result)

fun makeErr(id: String, plugin: String, code: String, message: String): RpcResponse =
    RpcResponse(id = id, plugin = plugin, ok = false, error = RpcError(code, message))

fun makeEvent(plugin: String, event: String, payload: JsonElement): RpcEvent =
    RpcEvent(plugin = plugin, event = event, payload = payload)

fun encodeRpcMessage(msg: RpcMessage): String {
    val obj = when (msg) {
        is RpcRequest -> buildJsonObject {
            put("v", 1)
            put("t", "req")
            put("id", msg.id)
            put("plugin", msg.plugin)
            put("method", msg.method)
            put("params", msg.params)
        }
        is RpcResponse -> buildJsonObject {
            put("v", 1)
            put("t", "res")
            put("id", msg.id)
            put("plugin", msg.plugin)
            put("ok", msg.ok)
            if (msg.ok) {
                put("result", msg.result)
            } else {
                val error = checkNotNull(msg.error) { "error response without error" }
                put("error", buildJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                })
            }
        }
        is RpcEvent -> buildJsonObject {
            put("v", 1)
            put("t", "event")
            put("plugin", msg.plugin)
            put("event", msg.event)
            put("payload", msg.payload)
        }
    }
    return obj.toString()
}

// Guard semantics of isRpcMessage in protocol.ts: v must be the NUMBER 1
// ('1' as a string drops the frame); unparseable JSON and non-matching frames
// return null silently.
fun parseRpcMessage(json: String): RpcMessage? {
    val obj = (try {
        ProtocolJson.parseToJsonElement(json)
    } catch (_: Exception) {
        return null
    }) as? JsonObject ?: return null

    val v = obj["v"] as? JsonPrimitive ?: return null
    if (v.isString || v.doubleOrNull != 1.0) return null

    return when (str(obj["t"])) {
        "req" -> {
            val id = str(obj["id"]) ?: return null
            val plugin = str(obj["plugin"]) ?: return null
            val method = str(obj["method"]) ?: return null
            RpcRequest(id, plugin, method, obj["params"] ?: JsonNull)
        }
        "res" -> {
            val id = str(obj["id"]) ?: return null
            val plugin = str(obj["plugin"]) ?: return null
            val ok = bool(obj["ok"]) ?: return null
            if (ok) RpcResponse(id, plugin, ok = true, result = obj["result"] ?: JsonNull)
            else RpcResponse(id, plugin, ok = false, error = parseError(obj["error"]))
        }
        "event" -> {
            val plugin = str(obj["plugin"]) ?: return null
            val event = str(obj["event"]) ?: return null
            RpcEvent(plugin, event, obj["payload"] ?: JsonNull)
        }
        else -> null
    }
}

fun isRpcMessage(json: String): Boolean = parseRpcMessage(json) != null

// Transport-side guard: a relayed frame's inner `plugin` field must match the
// transport-authenticated sender. A frame that lies about its plugin id could
// otherwise borrow another plugin's permissions or settle a stranger's call.
fun framePluginMatches(json: String, plugin: String): Boolean =
    parseRpcMessage(json)?.plugin == plugin

// Typed error thrown when a call fails, so catch blocks can branch on `.code`.
// Message format matches RpcCallError in protocol.ts.
class RpcCallException(
    val code: String,
    val plugin: String,
    val method: String,
    message: String,
) : Exception("$method [$code]: $message")

private fun str(el: JsonElement?): String? =
    (el as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun bool(el: JsonElement?): Boolean? =
    (el as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

// The TS guard accepts an ok:false frame without a well-formed error object
// (the SDK never sends one); fall back to a generic plugin-error.
private fun parseError(el: JsonElement?): RpcError {
    val obj = el as? JsonObject
    return RpcError(
        code = str(obj?.get("code")) ?: RpcErrorCodes.PLUGIN_ERROR,
        message = str(obj?.get("message")) ?: "",
    )
}
