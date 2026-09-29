package com.d8gp.plugins.protocol.rpc

import com.d8gp.plugins.protocol.connections.ConnectionErrorCodes
import com.d8gp.plugins.protocol.connections.VectorReport
import com.d8gp.plugins.protocol.connections.VectorsGenerated
import com.d8gp.plugins.protocol.connections.text
import com.d8gp.plugins.protocol.connections.vectorList
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrikePolicyTest {
    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("strike-policy.json", 82)
        for (vector in vectorList(VectorsGenerated.STRIKE_POLICY)) {
            val code = vector.text("code")
            val kind = vector.text("callKind")
            report.check("$kind/$code", vector.getValue("strike").jsonPrimitive.content.toBoolean()) {
                strikeFor(code, kind)
            }
        }
        report.finish()
    }

    @Test
    fun `no outcome of a brokered call strikes`() {
        val codes = ConnectionErrorCodes.ALL + RpcErrorCodes.TIMEOUT + RpcErrorCodes.PLUGIN_ERROR
        for (kind in listOf(CallKind.Connection, CallKind.Task)) {
            assertEquals(emptyList(), codes.filter { strikeFor(it, kind) }, kind.wire)
        }
    }

    @Test
    fun `the legacy call path keeps the desktop rule`() {
        val codes = ConnectionErrorCodes.ALL + RpcErrorCodes.TIMEOUT + RpcErrorCodes.PLUGIN_ERROR
        assertEquals(
            listOf(RpcErrorCodes.TIMEOUT, RpcErrorCodes.PLUGIN_ERROR),
            codes.filter { strikeFor(it, CallKind.Capability) },
        )
    }

    @Test
    fun `an unknown call kind has no wire name`() {
        assertNull(CallKind.fromWire("job"))
    }

    @Test
    fun `the text entry point agrees with the enumeration`() {
        val codes = ConnectionErrorCodes.ALL + RpcErrorCodes.TIMEOUT + RpcErrorCodes.PLUGIN_ERROR
        for (kind in CallKind.entries) {
            for (code in codes) assertEquals(strikeFor(code, kind), strikeFor(code, kind.wire), "${kind.wire}/$code")
        }
    }
}
