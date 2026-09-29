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
        val report = VectorReport("strike-policy.json", 50)
        for (vector in vectorList(VectorsGenerated.STRIKE_POLICY)) {
            val code = vector.text("code")
            val kind = vector.text("callKind")
            report.check("$kind/$code", vector.getValue("strike").jsonPrimitive.content.toBoolean()) {
                strikeFor(code, CallKind.fromWire(kind) ?: error("unknown call kind $kind"))
            }
        }
        report.finish()
    }

    @Test
    fun `only a crashed or missing provider strikes on a brokered call`() {
        val striking = ConnectionErrorCodes.ALL.filter { strikeFor(it, CallKind.Connection) }
        assertEquals(listOf(ConnectionErrorCodes.PROVIDER_UNAVAILABLE), striking)
        assertEquals(striking, ConnectionErrorCodes.ALL.filter { strikeFor(it, CallKind.Task) })
    }

    @Test
    fun `an unknown call kind has no wire name`() {
        assertNull(CallKind.fromWire("job"))
    }
}
