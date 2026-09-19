package com.d8gp.plugins.protocol.rpc

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

// Coroutine port of createRpcPeer (plugin-host-core/rpc/peer.ts). A symmetric
// RPC endpoint: issues calls (suspends), answers incoming calls via registered
// handlers, and fans out fire-and-forget events. Transport-agnostic — it only
// needs a send(json) sink and to be fed inbound frames via handleMessage.
//
// The `plugin` field namespaces every frame so one host-side peer can serve
// many iframes over a single WebView; the transport routes down-frames by that
// field and stamps up-frames with the authenticated sender id.
class RpcPeer(
    private val side: String,
    private val scope: CoroutineScope,
    private val localErrorCode: String =
        if (side == "host") RpcErrorCodes.HOST_ERROR else RpcErrorCodes.PLUGIN_ERROR,
    private val send: (String) -> Unit,
) {
    private class Pending(
        val deferred: CompletableDeferred<JsonElement>,
        val plugin: String,
        val method: String,
    ) {
        var timeoutJob: Job? = null
    }

    private val lock = SynchronizedObject()
    private val pending = LinkedHashMap<String, Pending>()
    private val handlers = HashMap<String, suspend (plugin: String, params: JsonElement) -> JsonElement>()
    private val listeners = mutableListOf<(plugin: String, event: String, payload: JsonElement) -> Unit>()
    private var counter = 0

    @Volatile
    private var disposed = false

    init {
        // If the peer's scope is torn down (cancel/dispose of the owning scope)
        // while calls are in flight, their timeout jobs die with it and the
        // deferreds would hang forever — reject them so no caller is stranded.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            flushPending(RpcErrorCodes.PLUGIN_UNLOADED)
        }
    }

    // timeoutMs <= 0 means no timeout (wait forever), matching the TS peer.
    suspend fun call(
        plugin: String,
        method: String,
        params: JsonElement,
        timeoutMs: Long = RPC_DEFAULT_TIMEOUT_MS,
    ): JsonElement {
        val entry = Pending(CompletableDeferred(), plugin, method)
        val id = synchronized(lock) {
            // Checked under the lock so a concurrent dispose() can't strand a
            // freshly registered pending entry.
            if (disposed) {
                throw RpcCallException(RpcErrorCodes.PLUGIN_UNLOADED, plugin, method, "peer disposed")
            }
            "$side:${counter++}".also { pending[it] = entry }
        }
        val frame = encodeRpcMessage(makeRequest(id, plugin, method, params))
        if (frame.encodeToByteArray().size > RPC_MAX_PAYLOAD_BYTES) {
            takePending(id)
            throw RpcCallException(
                RpcErrorCodes.PAYLOAD_TOO_LARGE,
                plugin,
                method,
                "request payload exceeds $RPC_MAX_PAYLOAD_BYTES bytes",
            )
        }
        if (timeoutMs > 0) {
            entry.timeoutJob = scope.launch {
                delay(timeoutMs)
                val p = takePending(id) ?: return@launch
                p.deferred.completeExceptionally(
                    RpcCallException(RpcErrorCodes.TIMEOUT, plugin, method, "$method timed out after ${timeoutMs}ms"),
                )
            }
        }
        send(frame)
        return try {
            entry.deferred.await()
        } catch (e: CancellationException) {
            takePending(id)
            throw e
        }
    }

    fun emit(plugin: String, event: String, payload: JsonElement) {
        if (disposed) return
        post(makeEvent(plugin, event, payload))
    }

    fun setHandler(method: String, handler: suspend (plugin: String, params: JsonElement) -> JsonElement) {
        synchronized(lock) { handlers[method] = handler }
    }

    fun onEvent(listener: (plugin: String, event: String, payload: JsonElement) -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(listener) }
        return { synchronized(lock) { listeners.remove(listener) } }
    }

    fun handleMessage(json: String) {
        if (disposed) return
        when (val msg = parseRpcMessage(json) ?: return) {
            is RpcRequest -> scope.launch { dispatchRequest(msg) }
            is RpcResponse -> settleResponse(msg)
            is RpcEvent -> {
                val snapshot = synchronized(lock) { listeners.toList() }
                for (listener in snapshot) listener(msg.plugin, msg.event, msg.payload)
            }
        }
    }

    // Reject in-flight calls (optionally only one plugin's) without tearing the
    // peer down — for when the far side goes away (shell reload, plugin crash)
    // so callers fail fast instead of waiting out their timeouts.
    fun flushPending(reason: String, plugin: String? = null) {
        val flushed = synchronized(lock) {
            val hit = mutableListOf<Pending>()
            val iter = pending.values.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                if (plugin != null && p.plugin != plugin) continue
                iter.remove()
                p.timeoutJob?.cancel()
                hit += p
            }
            hit
        }
        for (p in flushed) {
            p.deferred.completeExceptionally(
                RpcCallException(reason, p.plugin, p.method, "call flushed by transport"),
            )
        }
    }

    fun dispose(reason: String = RpcErrorCodes.PLUGIN_UNLOADED) {
        val flushed = synchronized(lock) {
            if (disposed) return
            disposed = true
            val hit = pending.values.toList()
            pending.clear()
            hit.forEach { it.timeoutJob?.cancel() }
            handlers.clear()
            listeners.clear()
            hit
        }
        for (p in flushed) {
            p.deferred.completeExceptionally(
                RpcCallException(reason, p.plugin, p.method, "peer disposed"),
            )
        }
    }

    private fun post(msg: RpcMessage) {
        send(encodeRpcMessage(msg))
    }

    private fun takePending(id: String): Pending? = synchronized(lock) {
        val p = pending.remove(id) ?: return null
        p.timeoutJob?.cancel()
        p
    }

    private suspend fun dispatchRequest(msg: RpcRequest) {
        val handler = synchronized(lock) { handlers[msg.method] }
        if (handler == null) {
            post(makeErr(msg.id, msg.plugin, RpcErrorCodes.NOT_FOUND, "no handler for ${msg.method}"))
            return
        }
        val response = try {
            makeOk(msg.id, msg.plugin, handler(msg.plugin, msg.params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcCallException) {
            // A handler may pick a specific error code by throwing
            // RpcCallException; its formatted message rides along, as in TS.
            makeErr(msg.id, msg.plugin, e.code, e.message ?: "")
        } catch (e: Throwable) {
            makeErr(msg.id, msg.plugin, localErrorCode, e.message ?: e.toString())
        }
        if (!disposed) post(response)
    }

    private fun settleResponse(msg: RpcResponse) {
        val p = synchronized(lock) {
            val entry = pending[msg.id] ?: return // unknown/expired id
            // A response must come from the plugin the call was addressed to —
            // checked before settling so a forged frame can't evict the real entry.
            if (entry.plugin != msg.plugin) return
            pending.remove(msg.id)
            entry.timeoutJob?.cancel()
            entry
        }
        if (msg.ok) {
            p.deferred.complete(msg.result)
        } else {
            val error = msg.error ?: RpcError(RpcErrorCodes.PLUGIN_ERROR, "")
            p.deferred.completeExceptionally(
                RpcCallException(error.code, p.plugin, p.method, error.message),
            )
        }
    }
}
