package com.d8gp.plugins.protocol.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test

// Port of tests/unit/pluginRpc.test.ts: two peers wired back-to-back over an
// async in-memory transport, the way a host and a plugin talk across a real
// bridge.
class PeerTest {
    private fun withPeers(block: suspend CoroutineScope.(host: RpcPeer, plugin: RpcPeer) -> Unit) {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            lateinit var host: RpcPeer
            lateinit var plugin: RpcPeer
            host = RpcPeer("host", scope) { json -> scope.launch { plugin.handleMessage(json) } }
            plugin = RpcPeer("plugin", scope) { json -> scope.launch { host.handleMessage(json) } }
            try {
                // supervisorScope so a failed async child (an expected call
                // rejection) doesn't cancel the whole test scope.
                withTimeout(10_000) { supervisorScope { block(host, plugin) } }
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun awaitCallError(block: suspend () -> JsonElement): RpcCallException {
        try {
            block()
        } catch (e: RpcCallException) {
            return e
        }
        fail("expected RpcCallException")
        throw AssertionError()
    }

    private suspend fun awaitError(deferred: Deferred<JsonElement>): RpcCallException =
        awaitCallError { deferred.await() }

    @Test
    fun `round-trips a host to plugin call`() = withPeers { host, plugin ->
        val items = buildJsonArray {
            add(buildJsonObject {
                put("id", "reddit:1")
                put("source", "reddit")
                put("kind", "native")
                put("linkUrl", "https://x")
            })
        }
        plugin.setHandler("feed.fetchBatch") { _, _ -> items }
        assertEquals(items, host.call("feed-reddit", "feed.fetchBatch", buildJsonObject {}))
    }

    @Test
    fun `round-trips a plugin to host call and passes the plugin id to the handler`() = withPeers { host, plugin ->
        var sawPlugin = ""
        host.setHandler("http.fetch") { id, params ->
            sawPlugin = id
            buildJsonObject {
                put("status", 200)
                put("url", params.jsonObject["url"]!!.jsonPrimitive.content)
            }
        }
        val res = plugin.call("feed-reddit", "http.fetch", buildJsonObject { put("url", "https://reddit.com/.rss") })
        assertEquals(200, res.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals("feed-reddit", sawPlugin)
    }

    @Test
    fun `propagates a thrown handler error with the answering side code`() = withPeers { host, plugin ->
        plugin.setHandler("feed.fetchBatch") { _, _ -> throw RuntimeException("kaboom") }
        val e = awaitCallError { host.call("feed-reddit", "feed.fetchBatch", buildJsonObject {}) }
        assertEquals("plugin-error", e.code)
        assertTrue(e.message!!.contains("kaboom"))

        host.setHandler("http.fetch") { _, _ -> throw RuntimeException("denied") }
        val e2 = awaitCallError { plugin.call("feed-reddit", "http.fetch", buildJsonObject {}) }
        assertEquals("host-error", e2.code)
    }

    @Test
    fun `a handler can pick its error code by throwing RpcCallException`() = withPeers { host, plugin ->
        host.setHandler("http.fetch") { pluginId, _ ->
            throw RpcCallException("permission-denied", pluginId, "http.fetch", "net not allowed")
        }
        val e = awaitCallError { plugin.call("feed-reddit", "http.fetch", buildJsonObject {}) }
        assertEquals("permission-denied", e.code)
    }

    @Test
    fun `rejects an unknown method with not-found`() = withPeers { host, _ ->
        val e = awaitCallError { host.call("feed-reddit", "does.not.exist", buildJsonObject {}) }
        assertEquals("not-found", e.code)
        assertEquals("does.not.exist [not-found]: no handler for does.not.exist", e.message)
    }

    @Test
    fun `times out a call whose handler never resolves`() = withPeers { host, plugin ->
        plugin.setHandler("feed.fetchBatch") { _, _ -> awaitCancellation() }
        val e = awaitCallError {
            host.call("feed-reddit", "feed.fetchBatch", buildJsonObject {}, timeoutMs = 30)
        }
        assertEquals("timeout", e.code)
        assertEquals("feed.fetchBatch [timeout]: feed.fetchBatch timed out after 30ms", e.message)
    }

    @Test
    fun `delivers fire-and-forget events with plugin id and payload`() = withPeers { host, plugin ->
        val received = LockedList<Triple<String, String, JsonElement>>()
        host.onEvent { pluginId, event, payload -> received += Triple(pluginId, event, payload) }
        val payload = buildJsonObject {
            put("protocol", "matrix")
            put("conversationId", "!room")
        }
        plugin.emit("messaging-matrix", "messaging.message", payload)
        while (received.isEmpty()) delay(5)
        assertEquals(listOf(Triple("messaging-matrix", "messaging.message", payload as JsonElement)), received)
    }

    @Test
    fun `an unsubscribed event listener stops receiving`() = withPeers { host, plugin ->
        val received = LockedList<String>()
        val unsubscribe = host.onEvent { _, event, _ -> received += event }
        plugin.emit("p", "one", JsonNull)
        while (received.isEmpty()) delay(5)
        unsubscribe()
        plugin.emit("p", "two", JsonNull)
        delay(50)
        assertEquals(listOf("one"), received)
    }

    @Test
    fun `rejects in-flight calls when disposed`() = withPeers { host, plugin ->
        plugin.setHandler("feed.fetchBatch") { _, _ -> awaitCancellation() }
        val call = async {
            host.call("feed-reddit", "feed.fetchBatch", buildJsonObject {}, timeoutMs = 5_000)
        }
        delay(50)
        host.dispose()
        val e = awaitError(call)
        assertEquals("plugin-unloaded", e.code)
    }

    @Test
    fun `a disposed peer rejects new calls immediately`() = withPeers { host, _ ->
        host.dispose()
        val e = awaitCallError { host.call("feed-reddit", "feed.fetchBatch", buildJsonObject {}) }
        assertEquals("plugin-unloaded", e.code)
        assertEquals("feed.fetchBatch [plugin-unloaded]: peer disposed", e.message)
    }

    @Test
    fun `ignores a response whose plugin id does not match the call`() = runBlocking {
        val sent = LockedList<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val host = RpcPeer("host", scope) { sent += it }
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                host.call("feed-a", "feed.fetchBatch", buildJsonObject {}, timeoutMs = 5_000)
            }
            withTimeout(2_000) { while (sent.isEmpty()) delay(5) }
            val id = (parseRpcMessage(sent[0]) as RpcRequest).id
            // A forged frame must neither settle the call nor evict the pending entry.
            host.handleMessage("""{"v":1,"t":"res","id":"$id","plugin":"feed-b","ok":true,"result":["forged"]}""")
            host.handleMessage("""{"v":1,"t":"res","id":"$id","plugin":"feed-a","ok":true,"result":["real"]}""")
            assertEquals(buildJsonArray { add("real") }, call.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `flushPending rejects in-flight calls per-plugin or all without disposing`() = withPeers { host, plugin ->
        plugin.setHandler("feed.fetchBatch") { _, _ -> awaitCancellation() }
        val callA = async { host.call("feed-a", "feed.fetchBatch", buildJsonObject {}, timeoutMs = 5_000) }
        val callB = async { host.call("feed-b", "feed.fetchBatch", buildJsonObject {}, timeoutMs = 5_000) }
        delay(50)
        host.flushPending("plugin-error", "feed-a")
        val eA = awaitError(callA)
        assertEquals("plugin-error", eA.code)
        assertEquals("feed.fetchBatch [plugin-error]: call flushed by transport", eA.message)
        host.flushPending("plugin-unloaded")
        assertEquals("plugin-unloaded", awaitError(callB).code)
        // The peer survives a flush.
        plugin.setHandler("feed.other") { _, _ -> JsonPrimitive(42) }
        assertEquals(42, host.call("feed-c", "feed.other", buildJsonObject {}).jsonPrimitive.int)
    }

    @Test
    fun `request ids are side-prefixed and increment`() = runBlocking {
        val sent = LockedList<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val host = RpcPeer("host", scope) { sent += it }
        try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { host.call("p", "a.b", JsonNull, timeoutMs = 50) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { host.call("p", "a.b", JsonNull, timeoutMs = 50) }
            }
            withTimeout(2_000) { while (sent.size < 2) delay(5) }
            assertEquals("host:0", (parseRpcMessage(sent[0]) as RpcRequest).id)
            assertEquals("host:1", (parseRpcMessage(sent[1]) as RpcRequest).id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refuses an oversized request frame with payload-too-large`() = withPeers { host, _ ->
        val blob = "x".repeat(RPC_MAX_PAYLOAD_BYTES + 1)
        val e = awaitCallError {
            host.call("feed-a", "feed.fetchBatch", buildJsonObject { put("blob", blob) })
        }
        assertEquals("payload-too-large", e.code)
    }

    @Test
    fun `a void handler result travels as result null`() = withPeers { host, plugin ->
        plugin.setHandler("feed.nothing") { _, _ -> JsonNull }
        assertEquals(JsonNull, host.call("feed-x", "feed.nothing", buildJsonObject {}))
    }
}
