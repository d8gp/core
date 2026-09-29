package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class DataFlow {
    // Consumer data goes to the provider.
    @SerialName("request-bearing") RequestBearing,

    // Provider data goes to the consumer.
    @SerialName("response-bearing") ResponseBearing,
}

@Serializable
data class GraphPlugin(
    val net: List<String> = emptyList(),
    val exec: List<String> = emptyList(),
)

@Serializable
data class GraphConnection(
    val id: String,
    val consumer: String,
    val provider: String,
    @SerialName("interface")
    val iface: String,
    val dataFlow: DataFlow,
    val state: String,
    val egress: List<String> = emptyList(), // the provider's DECLARED egress, as stored in the record
    val scope: JsonObject? = null,
)

@Serializable
data class EgressGraph(
    val plugins: Map<String, GraphPlugin> = emptyMap(),
    val connections: List<GraphConnection> = emptyList(),
)

// Everywhere data handed to `plugin` can end up: its own network hosts and
// programs, the declared egress of providers it sends requests to, and the
// same for every plugin that reads from it, transitively.
fun onwardEgress(plugin: String, graph: EgressGraph): List<String> {
    val active = graph.connections.filter { it.state == ConnectionStates.ACTIVE }

    val reach = linkedSetOf(plugin)
    val pending = ArrayDeque(listOf(plugin))
    while (pending.isNotEmpty()) {
        val provider = pending.removeFirst()
        active.filter { it.provider == provider }
            .map { it.consumer }
            .filter(reach::add)
            .forEach(pending::addLast)
    }

    val egress = mutableSetOf<String>()
    for (member in reach) {
        graph.plugins[member]?.let { own ->
            egress += own.net
            egress += own.exec.map(::execEgress)
        }
        active.filter { it.consumer == member && it.dataFlow == DataFlow.RequestBearing }
            .forEach { egress += it.egress }
    }
    return egress.sorted()
}
