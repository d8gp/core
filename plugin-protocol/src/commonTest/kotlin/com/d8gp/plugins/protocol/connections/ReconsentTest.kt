package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.PluginManifest
import com.d8gp.plugins.protocol.parseManifest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconsentTest {
    private val consumerSha = "a".repeat(64)
    private val providerSha = "b".repeat(64)

    private fun consumerManifest(
        sha256: String = consumerSha,
        methods: String = """["complete"]""",
        version: Int = 1,
        permissions: String = "{}",
    ): PluginManifest = parseManifest(
        """{"manifestVersion":1,"id":"plugin-events","name":"Event extraction","version":"0.1.0",
            "entry":{"url":"https://plugins.d8gp.example/e/bundle.js","sha256":"$sha256"},
            "capabilities":[{"type":"connector","role":"requires","interface":"d8gp.llm","version":$version,
              "methods":$methods,"name":"Language model","reason":"Asks a model."}],
            "permissions":$permissions}""",
    )

    private fun providerManifest(
        sha256: String = providerSha,
        net: String = """["api.anthropic.com"]""",
        egress: String = """["api.anthropic.com"]""",
        auth: Boolean = true,
    ): PluginManifest = parseManifest(
        """{"manifestVersion":1,"id":"plugin-llm-anthropic","name":"Claude","version":"0.1.0",
            "entry":{"url":"https://plugins.d8gp.example/a/bundle.js","sha256":"$sha256"},
            "capabilities":[{"type":"connector","role":"provides","interface":"d8gp.llm","version":1,
              "methods":["complete"],"name":"Claude via Anthropic API","egress":$egress}],
            "permissions":{"net":$net,"auth":$auth}}""",
    )

    private val record = ConnectionRecord(
        id = "cn_1",
        consumer = ConnectionConsumer(plugin = "plugin-events", version = "0.1.0", sha256 = consumerSha),
        provider = ConnectionProvider(
            plugin = "plugin-llm-anthropic",
            version = "0.1.0",
            sha256 = providerSha,
            net = listOf("api.anthropic.com"),
            auth = true,
            egress = listOf("api.anthropic.com"),
        ),
        iface = "d8gp.llm",
        major = 1,
        methods = listOf("complete"),
        budget = Budget(),
        onwardEgress = OnwardEgressSnapshot(
            consumer = listOf("api.anthropic.com"),
            provider = listOf("api.anthropic.com"),
        ),
        state = ConnectionStates.ACTIVE,
    )

    private val wired = GraphConnection(
        id = "cn_1",
        consumer = "plugin-events",
        provider = "plugin-llm-anthropic",
        iface = "d8gp.llm",
        dataFlow = DataFlow.RequestBearing,
        state = ConnectionStates.ACTIVE,
        egress = listOf("api.anthropic.com"),
    )

    private val graph = EgressGraph(
        plugins = mapOf(
            "plugin-events" to GraphPlugin(),
            "plugin-llm-anthropic" to GraphPlugin(net = listOf("api.anthropic.com")),
        ),
        connections = listOf(wired),
    )

    private fun reason(
        consumer: PluginManifest = consumerManifest(),
        provider: PluginManifest? = providerManifest(),
        graph: EgressGraph = this.graph,
        record: ConnectionRecord = this.record,
    ): String? = reconsentReason(record, consumer, provider, graph)

    @Test
    fun `an unchanged pair needs nothing`() {
        assertNull(reason())
    }

    @Test
    fun `a code change of either party pauses the connection`() {
        assertEquals("consumer code changed", reason(consumer = consumerManifest(sha256 = "c".repeat(64))))
        assertEquals("provider code changed", reason(provider = providerManifest(sha256 = "c".repeat(64))))
    }

    @Test
    fun `a changed major or a dropped method pauses the connection`() {
        assertNotNull(reason(consumer = consumerManifest(version = 2)))
        assertNotNull(reason(consumer = consumerManifest(methods = """["embed"]""")))
        assertNull(reason(consumer = consumerManifest(methods = """["complete","embed"]""")))
    }

    @Test
    fun `changed permissions or egress of either side pause the connection`() {
        assertNotNull(reason(consumer = consumerManifest(permissions = """{"net":["evil.example"]}""")))
        assertNotNull(reason(consumer = consumerManifest(permissions = """{"auth":true}""")))
        assertNotNull(
            reason(
                consumer = consumerManifest(
                    permissions = """{"exec":[{"binary":"jq","purpose":"Filters.","argv":[]}]}""",
                ),
            ),
        )
        assertNotNull(reason(provider = providerManifest(net = """["api.anthropic.com","evil.example"]""")))
        assertNotNull(reason(provider = providerManifest(auth = false)))
        assertNotNull(reason(provider = providerManifest(egress = "[]")))
    }

    @Test
    fun `a missing provider or a provider that stopped declaring the interface pauses the connection`() {
        assertNotNull(reason(provider = null))
        assertNotNull(reason(provider = consumerManifest()))
    }

    @Test
    fun `a host provider has no manifest`() {
        val hosted = record.copy(
            provider = ConnectionProvider(kind = ProviderKinds.HOST, plugin = "host:messages"),
            iface = "d8gp.llm",
            onwardEgress = OnwardEgressSnapshot(),
        )
        assertNull(reason(provider = null, record = hosted, graph = EgressGraph()))
    }

    @Test
    fun `grown onward egress of the data receiver pauses the connection`() {
        val downstream = GraphConnection(
            id = "cn_2",
            consumer = "plugin-gcal",
            provider = "plugin-llm-anthropic",
            iface = "d8gp.example.echo",
            dataFlow = DataFlow.ResponseBearing,
            state = ConnectionStates.ACTIVE,
        )
        val grown = EgressGraph(
            plugins = graph.plugins + ("plugin-gcal" to GraphPlugin(net = listOf("www.googleapis.com"))),
            connections = listOf(wired, downstream),
        )
        assertEquals("provider can now send data further", reason(graph = grown))
        assertNull(reason(graph = grown.copy(connections = listOf(wired, downstream.copy(state = "revoked")))))
    }

    @Test
    fun `growth on the side that only sends data is not a reason`() {
        val reading = record.copy(
            provider = ConnectionProvider(kind = ProviderKinds.HOST, plugin = "host:messages"),
            iface = "d8gp.messages",
            methods = listOf("list"),
            onwardEgress = OnwardEgressSnapshot(),
        )
        val consumer = parseManifest(
            """{"manifestVersion":1,"id":"plugin-events","name":"Event extraction","version":"0.1.0",
                "entry":{"url":"https://plugins.d8gp.example/e/bundle.js","sha256":"$consumerSha"},
                "capabilities":[{"type":"connector","role":"requires","interface":"d8gp.messages","version":1,
                  "methods":["list"],"name":"Messages","reason":"Reads messages."}]}""",
        )
        val senderGrew = EgressGraph(
            plugins = mapOf(
                "plugin-events" to GraphPlugin(),
                "host:messages" to GraphPlugin(net = listOf("imap.gmail.com")),
            ),
            connections = listOf(
                wired.copy(provider = "host:messages", iface = "d8gp.messages", dataFlow = DataFlow.ResponseBearing),
            ),
        )
        assertNull(reason(consumer = consumer, provider = null, record = reading, graph = senderGrew))

        val receiverGrew = senderGrew.copy(
            plugins = senderGrew.plugins + ("plugin-events" to GraphPlugin(net = listOf("evil.example"))),
        )
        assertEquals(
            "consumer network permission changed",
            reason(
                consumer = parseManifest(
                    """{"manifestVersion":1,"id":"plugin-events","name":"Event extraction","version":"0.1.0",
                        "entry":{"url":"https://plugins.d8gp.example/e/bundle.js","sha256":"$consumerSha"},
                        "capabilities":[{"type":"connector","role":"requires","interface":"d8gp.messages",
                          "version":1,"methods":["list"],"name":"Messages","reason":"Reads messages."}],
                        "permissions":{"net":["evil.example"]}}""",
                ),
                provider = null,
                record = reading,
                graph = receiverGrew,
            ),
        )
        val downstream = wired.copy(
            id = "cn_9",
            consumer = "plugin-gcal",
            provider = "plugin-events",
            iface = "d8gp.events",
            dataFlow = DataFlow.ResponseBearing,
        )
        val readerAdded = senderGrew.copy(
            plugins = senderGrew.plugins + ("plugin-gcal" to GraphPlugin(net = listOf("www.googleapis.com"))),
            connections = senderGrew.connections + downstream,
        )
        assertEquals(
            "consumer can now send data further",
            reason(consumer = consumer, provider = null, record = reading, graph = readerAdded),
        )
    }

    @Test
    fun `a changed verified author pauses the connection`() {
        val signed = record.copy(
            consumer = record.consumer.copy(authorKey = "k1"),
            provider = record.provider.copy(authorKey = "k2"),
        )
        fun reasonFor(consumerKey: String?, providerKey: String?): String? = reconsentReason(
            signed,
            InstalledParty(consumerManifest(), consumerKey),
            InstalledParty(providerManifest(), providerKey),
            graph,
        )
        assertNull(reasonFor("k1", "k2"))
        assertEquals("consumer author changed", reasonFor(null, "k2"))
        assertEquals("consumer author changed", reasonFor("other", "k2"))
        assertEquals("provider author changed", reasonFor("k1", null))
        assertEquals("consumer author changed", reason(record = signed))
        assertEquals(
            "consumer author changed",
            reconsentReason(record, InstalledParty(consumerManifest(), "k1"), InstalledParty(providerManifest()), graph),
        )
    }

    @Test
    fun `an edit that widens the record needs the user and one that narrows it does not`() {
        assertNull(recordEditReason(record, record))
        assertNull(recordEditReason(record, record.copy(state = ConnectionStates.REVOKED, fingerprint = "f")))
        assertNull(recordEditReason(record, record.copy(budget = Budget(requestsPerDay = 1))))
        assertNull(recordEditReason(record, record.copy(methods = emptyList())))
        assertNull(recordEditReason(record, record.copy(onwardEgress = OnwardEgressSnapshot())))

        assertEquals(
            "budget was raised",
            recordEditReason(record, record.copy(budget = Budget(requestsPerDay = 501))),
        )
        assertEquals("methods were added", recordEditReason(record, record.copy(methods = listOf("complete", "embed"))))
        assertEquals(
            "scope changed",
            recordEditReason(record, record.copy(scope = parseJson("""{"sources":["email"]}""").jsonObject)),
        )
        assertEquals("interface changed", recordEditReason(record, record.copy(major = 2)))
        assertEquals(
            "consumer changed",
            recordEditReason(record, record.copy(consumer = record.consumer.copy(net = listOf("evil.example")))),
        )
        assertEquals(
            "provider changed",
            recordEditReason(record, record.copy(provider = record.provider.copy(sha256 = "c".repeat(64)))),
        )
        assertEquals(
            "onward egress grew",
            recordEditReason(
                record,
                record.copy(onwardEgress = record.onwardEgress.copy(provider = listOf("evil.example"))),
            ),
        )
    }

    @Test
    fun `only a raised budget counts as a raise`() {
        val approved = Budget()
        assertFalse(approved.raises(approved))
        assertFalse(approved.copy(requestsPerDay = 100).raises(approved))
        assertTrue(approved.copy(requestsPerDay = 501).raises(approved))
        assertTrue(approved.copy(maxTier = ModelTiers.BEST).raises(approved))
        assertTrue(approved.copy(maxConcurrent = 2).raises(approved))
    }
}
