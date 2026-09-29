package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ProtocolJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class OnwardEgressTest {
    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("onward-egress.json", 31)
        for (vector in vectorList(VectorsGenerated.ONWARD_EGRESS)) {
            val expected = vector.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
            report.check(vector.text("name"), expected) {
                val graph = ProtocolJson.decodeFromJsonElement(EgressGraph.serializer(), vector.getValue("graph"))
                onwardEgress(vector.text("plugin"), graph)
            }
        }
        report.finish()
    }
}
