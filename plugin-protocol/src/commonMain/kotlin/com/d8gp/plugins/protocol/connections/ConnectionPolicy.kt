package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.CONNECTOR_INTERFACE_RE
import com.d8gp.plugins.protocol.CONNECTOR_METHOD_RE
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface CallDecision {
    // `serve` is the connection.serve params without callId, which the broker
    // issues when it delivers.
    data class Deliver(val serve: JsonObject, val deadlineMs: Long) : CallDecision

    data class Refuse(val code: String, val message: String) : CallDecision
}

object CallerKinds {
    const val PLUGIN = "plugin"
    const val HOST = "host"
}

object ConnectionPolicy {
    // `authenticated` is the plugin id the transport authenticated and the
    // only source of the caller's identity. `record` is what the broker
    // resolved for (authenticated, requested interface), or null.
    fun authorize(
        authenticated: String,
        request: JsonElement,
        requestBytes: Long,
        record: ConnectionRecord?,
    ): CallDecision {
        val call = parseCall(request)
            ?: return CallDecision.Refuse(ConnectionErrorCodes.INVALID_REQUEST, "malformed connection.call")

        val wired = record != null &&
            record.consumer.plugin == authenticated &&
            record.iface == call.iface &&
            !InterfaceSpecs.isHostOnly(call.iface)
        if (record == null || !wired) {
            return CallDecision.Refuse(ConnectionErrorCodes.NOT_WIRED, "no connection for ${call.iface}")
        }
        when (record.state) {
            ConnectionStates.ACTIVE -> Unit
            ConnectionStates.REVOKED ->
                return CallDecision.Refuse(ConnectionErrorCodes.REVOKED, "the connection was revoked")
            else ->
                return CallDecision.Refuse(ConnectionErrorCodes.NEEDS_RECONSENT, "the connection needs approval again")
        }
        val budget = record.budget
            ?: return CallDecision.Refuse(
                ConnectionErrorCodes.NEEDS_RECONSENT,
                "the connection has no approved budget",
            )
        if (record.major < 1 || call.version != record.major) {
            return CallDecision.Refuse(
                ConnectionErrorCodes.VERSION_MISMATCH,
                "the connection is for version ${record.major}",
            )
        }
        if (call.method !in record.methods) {
            return CallDecision.Refuse(ConnectionErrorCodes.METHOD_NOT_GRANTED, "${call.method} is not granted")
        }
        if (requestBytes !in 0..MAX_CALL_PARAMS_BYTES) {
            return CallDecision.Refuse(
                ConnectionErrorCodes.PAYLOAD_TOO_LARGE,
                "request is larger than $MAX_CALL_PARAMS_BYTES bytes",
            )
        }
        if (InterfaceSpecs.isKnown(call.iface)) {
            val spec = InterfaceSpecs.find(call.iface, call.version)
                ?: return CallDecision.Refuse(
                    ConnectionErrorCodes.VERSION_MISMATCH,
                    "this host has no version ${call.version} of ${call.iface}",
                )
            val check = spec.methods[call.method]
                ?: return CallDecision.Refuse(
                    ConnectionErrorCodes.INVALID_REQUEST,
                    "${call.iface} has no method ${call.method}",
                )
            check(call.params)?.let { return CallDecision.Refuse(ConnectionErrorCodes.INVALID_REQUEST, it) }
        }

        // A record with no time at all still gets a deadline the broker can
        // enforce, and the call ends as deadline-exceeded.
        val deadlineMs = budget.maxCallMs.coerceIn(1, MAX_CALL_MS)
        val serve = buildJsonObject {
            put("interface", call.iface)
            put("version", call.version)
            put("method", call.method)
            put("params", call.params)
            put("caller", buildJsonObject {
                put("kind", CallerKinds.PLUGIN)
                put("plugin", authenticated)
                put("connection", record.id)
            })
            put("deadlineMs", deadlineMs)
        }
        return CallDecision.Deliver(serve, deadlineMs)
    }

    fun resultTooLarge(iface: String, major: Long, resultBytes: Long): Boolean =
        resultBytes > InterfaceSpecs.maxResultBytes(iface, major)

    private class Call(val iface: String, val version: Long, val method: String, val params: JsonObject)

    private fun parseCall(request: JsonElement): Call? {
        val obj = request as? JsonObject ?: return null
        val iface = obj["interface"].stringOrNull()?.takeIf(CONNECTOR_INTERFACE_RE::matches) ?: return null
        val version = obj["version"].integerIn(1..MAX_SAFE_INTEGER) ?: return null
        val method = obj["method"].stringOrNull()?.takeIf(CONNECTOR_METHOD_RE::matches) ?: return null
        val params = obj["params"] as? JsonObject ?: return null
        return Call(iface, version, method, params)
    }
}
