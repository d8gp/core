package com.d8gp.plugins.protocol.rpc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

// Wire-framing pins: exact frame bytes, the v===1 number guard, silent drops
// for garbage, and the framePluginMatches transport guard from
// tests/unit/pluginRpc.test.ts.
class ProtocolTest {
    @Test
    fun `encodes request frames byte-exactly`() {
        assertEquals(
            """{"v":1,"t":"req","id":"host:0","plugin":"feed-a","method":"feed.fetchBatch","params":{"sourceKey":"reddit"}}""",
            encodeRpcMessage(
                makeRequest("host:0", "feed-a", "feed.fetchBatch", buildJsonObject { put("sourceKey", "reddit") }),
            ),
        )
    }

    @Test
    fun `encodes ok responses with result null for void handlers`() {
        // JS coerces an undefined handler result to null — result is always present.
        assertEquals(
            """{"v":1,"t":"res","id":"p:1","plugin":"feed-a","ok":true,"result":null}""",
            encodeRpcMessage(makeOk("p:1", "feed-a", JsonNull)),
        )
    }

    @Test
    fun `encodes error responses with the code-message envelope`() {
        assertEquals(
            """{"v":1,"t":"res","id":"p:1","plugin":"feed-a","ok":false,"error":{"code":"not-found","message":"no handler for x.y"}}""",
            encodeRpcMessage(makeErr("p:1", "feed-a", "not-found", "no handler for x.y")),
        )
    }

    @Test
    fun `encodes event frames`() {
        assertEquals(
            """{"v":1,"t":"event","plugin":"feed-a","event":"plugin.ready","payload":null}""",
            encodeRpcMessage(makeEvent("feed-a", "plugin.ready", JsonNull)),
        )
    }

    @Test
    fun `parses valid frames of all three kinds`() {
        val req = parseRpcMessage(
            """{"v":1,"t":"req","id":"host:0","plugin":"p","method":"feed.fetchBatch","params":{}}""",
        ) as RpcRequest
        assertEquals("feed.fetchBatch", req.method)

        val res = parseRpcMessage(
            """{"v":1,"t":"res","id":"host:0","plugin":"p","ok":true,"result":[1,2]}""",
        ) as RpcResponse
        assertTrue(res.ok)
        assertEquals(buildJsonArray { add(1); add(2) }, res.result)

        val err = parseRpcMessage(
            """{"v":1,"t":"res","id":"host:0","plugin":"p","ok":false,"error":{"code":"timeout","message":"m"}}""",
        ) as RpcResponse
        assertEquals(RpcError("timeout", "m"), err.error)

        val event = parseRpcMessage(
            """{"v":1,"t":"event","plugin":"p","event":"log","payload":{"level":"info","msg":"hi"}}""",
        ) as RpcEvent
        assertEquals("log", event.event)
    }

    @Test
    fun `v must be the number 1`() {
        assertNull(parseRpcMessage("""{"v":"1","t":"req","id":"a","plugin":"p","method":"m"}"""))
        assertNull(parseRpcMessage("""{"v":2,"t":"req","id":"a","plugin":"p","method":"m"}"""))
        assertNull(parseRpcMessage("""{"v":true,"t":"req","id":"a","plugin":"p","method":"m"}"""))
        // 1.0 === 1 in JS, so a fractional-form 1 still passes.
        assertTrue(parseRpcMessage("""{"v":1.0,"t":"req","id":"a","plugin":"p","method":"m"}""") is RpcRequest)
    }

    @Test
    fun `drops garbage and structurally invalid frames silently`() {
        assertNull(parseRpcMessage("not json"))
        assertNull(parseRpcMessage("[1,2]"))
        assertNull(parseRpcMessage("null"))
        assertNull(parseRpcMessage("""{"v":1,"t":"req","id":"a","plugin":"p"}""")) // missing method
        assertNull(parseRpcMessage("""{"v":1,"t":"req","id":7,"plugin":"p","method":"m"}""")) // non-string id
        assertNull(parseRpcMessage("""{"v":1,"t":"res","id":"a","plugin":"p","ok":"true"}""")) // string ok
        assertNull(parseRpcMessage("""{"v":1,"t":"event","plugin":"p"}""")) // missing event
        assertNull(parseRpcMessage("""{"v":1,"t":"nope","plugin":"p"}"""))
        assertFalse(isRpcMessage("{}"))
    }

    @Test
    fun `request params default to null when absent`() {
        // isRpcMessage does not require the params field, so both forms parse.
        val withNull = parseRpcMessage("""{"v":1,"t":"req","id":"a","plugin":"p","method":"m","params":null}""") as RpcRequest
        assertEquals(JsonNull, withNull.params)
        val absent = parseRpcMessage("""{"v":1,"t":"req","id":"b","plugin":"p","method":"m"}""") as RpcRequest
        assertEquals(JsonNull, absent.params)
    }

    @Test
    fun `framePluginMatches verifies the inner plugin field against the authenticated sender`() {
        val json = encodeRpcMessage(makeRequest("p:0", "feed-a", "http.fetch", buildJsonObject {}))
        assertTrue(framePluginMatches(json, "feed-a"))
        assertFalse(framePluginMatches(json, "feed-b"))
        assertFalse(framePluginMatches("not json", "feed-a"))
        assertFalse(framePluginMatches("""{"v":2}""", "feed-a"))
    }

    @Test
    fun `RpcCallException formats its message like RpcCallError`() {
        val e = RpcCallException("timeout", "feed-a", "feed.fetchBatch", "feed.fetchBatch timed out after 30ms")
        assertEquals("feed.fetchBatch [timeout]: feed.fetchBatch timed out after 30ms", e.message)
        assertEquals("timeout", e.code)
        assertEquals("feed-a", e.plugin)
        assertEquals("feed.fetchBatch", e.method)
    }

    @Test
    fun `constants match the TS contract`() {
        assertEquals(30_000L, RPC_DEFAULT_TIMEOUT_MS)
        assertEquals(4 * 1024 * 1024, RPC_MAX_PAYLOAD_BYTES)
    }
}
