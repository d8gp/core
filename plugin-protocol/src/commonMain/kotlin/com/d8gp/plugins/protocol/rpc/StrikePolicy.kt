package com.d8gp.plugins.protocol.rpc

import com.d8gp.plugins.protocol.connections.ConnectionErrorCodes

enum class CallKind(val wire: String) {
    Capability("capability"),
    Connection("connection"),
    Task("task"),
    ;

    companion object {
        fun fromWire(wire: String): CallKind? = entries.firstOrNull { it.wire == wire }
    }
}

// Whether a failed call counts towards the provider's crash-loop strikes.
// Capability calls take RPC error codes, brokered calls take envelope codes.
fun strikeFor(code: String, callKind: CallKind): Boolean = when (callKind) {
    CallKind.Capability -> code == RpcErrorCodes.TIMEOUT || code == RpcErrorCodes.PLUGIN_ERROR
    CallKind.Connection, CallKind.Task -> code == ConnectionErrorCodes.PROVIDER_UNAVAILABLE
}
