package com.d8gp.plugins.protocol.rpc

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
// Brokered calls go through the non-striking serve path: a provider that
// crashes during one is struck by the kernel crash path, which does not ask
// here.
fun strikeFor(code: String, callKind: CallKind): Boolean = when (callKind) {
    CallKind.Capability -> code == RpcErrorCodes.TIMEOUT || code == RpcErrorCodes.PLUGIN_ERROR
    CallKind.Connection, CallKind.Task -> false
}

// For the call kind as the wire carries it. A kind this version does not
// know never strikes.
fun strikeFor(code: String, callKind: String): Boolean =
    CallKind.fromWire(callKind)?.let { strikeFor(code, it) } ?: false
