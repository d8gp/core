package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ExecDecl
import com.d8gp.plugins.protocol.ProtocolJson
import com.d8gp.plugins.protocol.parseManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConsentTextTest {
    private val record = ProtocolJson.decodeFromJsonElement(
        ConnectionRecord.serializer(),
        vectorList(VectorsGenerated.FINGERPRINT)
            .first { it.text("name") == "base/design-section-4.1-record" }
            .getValue("record"),
    )

    private val messagesConnection = GraphConnection(
        id = "cn_msgs",
        consumer = "plugin-events",
        provider = "host:messages",
        iface = "d8gp.messages",
        dataFlow = DataFlow.ResponseBearing,
        state = ConnectionStates.ACTIVE,
        scope = JsonObject(mapOf("sources" to buildJsonArray { add(JsonPrimitive("email")) })),
    )

    private val graph = EgressGraph(
        plugins = mapOf(
            "plugin-events" to GraphPlugin(),
            "plugin-llm-claude-cli" to GraphPlugin(exec = listOf("claude")),
        ),
        connections = listOf(messagesConnection),
    )

    private val context = ConsentContext(
        consumerName = "Event extraction",
        providerName = "Claude via Claude Code CLI",
        reason = "Reads message text and asks a model which calendar events it mentions.",
        consumerProvides = listOf("d8gp.events"),
        programs = listOf(
            ProgramFacts(
                binary = "claude",
                path = "/Users/charles/.local/bin/claude",
                signedBy = "Anthropic PBC",
                contacts = "Anthropic under your own Claude login",
            ),
        ),
        providerCap = ProviderCap(requestsPerDay = 500, tokensPerDay = 2_000_000),
        usageIsEstimated = true,
        credentialNote = "d8gp never reads your Claude login.",
        requestedFrom = "cli",
    )

    private val claudeDecl: ExecDecl = parseManifest(
        vectorList(VectorsGenerated.CONNECTOR_MANIFESTS)
            .first { it.text("name") == "ok/plugin-llm-claude-cli-design-section-1.2" }
            .getValue("manifest"),
    ).permissions.exec!!.single()

    private val grant = ExecGrantRequest(
        pluginName = "Claude via Claude Code CLI",
        pluginId = "plugin-llm-claude-cli",
        pluginVersion = "0.1.0",
        decl = claudeDecl,
        launchPath = "/Users/charles/.local/bin/claude",
        resolvedPath = "/Users/charles/.local/share/claude/versions/2.1.177",
        signer = SignerFacts(name = "Anthropic PBC", teamId = "Q6L2SF6YDW", identifier = "com.anthropic.claude-code"),
        lockdownCheck = "passed for version 2.1.177 (no tools, no MCP servers, no skills, no plugins)",
    )

    @Test
    fun `connection consent is the layout of design 4_3`() {
        val expected = """
Connect two plugins

  "Event extraction" (plugin-events 0.1.0)   [name declared by the plugin, not verified]
      will be able to call
  "Claude via Claude Code CLI" (plugin-llm-claude-cli 0.1.0)

  Interface      d8gp.llm version 1, method: complete
  It says why    "Reads message text and asks a model which calendar events it mentions."
  What is sent   text chosen by plugin-events, which may include your messages
  Where plugin-llm-claude-cli can send it
                 runs the program claude (/Users/charles/.local/bin/claude, signed by Anthropic PBC),
                 which contacts Anthropic under your own Claude login.
                 d8gp cannot restrict where that program connects. Declared network hosts: none.
  Where plugin-events can send what it receives
                 network hosts: none. Other connections: d8gp.messages from host:messages (email);
                 its events are read by: nobody yet.
  Limits         20 requests/min, 500 requests/day, 8192 output tokens and 512 KiB input per request,
                 model tier: fast, 120 s per call, 1 at a time.
                 Token totals for this provider are estimates; request counts and time are enforced.
  Provider total 500 requests/day across all plugins that use it.
  Credentials    none cross this connection. d8gp never reads your Claude login.
  This provider already serves: nobody else.
  Requested from: command line.   You can disconnect at any time; calls in progress are cancelled.
        """.trim()
        assertEquals(expected, ConsentText.render(record, graph, context))
    }

    @Test
    fun `exec grant consent is the layout of design 5_2`() {
        val expected = """
Allow "Claude via Claude Code CLI" (plugin-llm-claude-cli 0.1.0) to run a program on this Mac?

Program       /Users/charles/.local/bin/claude
              -> /Users/charles/.local/share/claude/versions/2.1.177
Signed by     Anthropic PBC (Q6L2SF6YDW), com.anthropic.claude-code
Command line  claude -p --output-format=json --model=<model> --system-prompt=<system> --json-schema=<schema>
              --strict-mcp-config --disable-slash-commands --no-session-persistence
              --settings={"disableAllHooks":true} --setting-sources= --disallowedTools=mcp__* --tools=
The plugin fills in: model (haiku, sonnet or opus), system (text), schema (text), and the program's input.
Lockdown check: passed for version 2.1.177 (no tools, no MCP servers, no skills, no plugins).

This program runs as you, outside the sandbox. It can read your files and use the network.
d8gp limits how it is started, not what it does. Allow this only for a plugin you fully trust.
Runs with: a clean environment, an empty temporary folder, 120 s limit, one run per request, every run recorded.
This approval covers exactly this version of the plugin.
        """.trim()
        assertEquals(expected, ConsentText.renderExecGrant(grant))
    }

    @Test
    fun `consent names both plugins the interface the budget and both egress sets`() {
        val anthropic = record.copy(
            provider = record.provider.copy(
                plugin = "plugin-llm-anthropic",
                net = listOf("api.anthropic.com"),
                exec = emptyList(),
                egress = listOf("api.anthropic.com"),
            ),
            consumer = record.consumer.copy(net = listOf("www.googleapis.com")),
        )
        val text = ConsentText.render(anthropic, graph, context.copy(programs = emptyList()))
        for (part in listOf(
            "plugin-events",
            "plugin-llm-anthropic",
            "d8gp.llm version 1",
            "500 requests/day",
            "network hosts: api.anthropic.com.",
            "network hosts: www.googleapis.com.",
        )) {
            assertTrue(part in text, part)
        }
    }

    @Test
    fun `other consumers of the provider and readers of the consumer are listed`() {
        val busy = graph.copy(
            connections = graph.connections + listOf(
                GraphConnection(
                    id = "cn_other",
                    consumer = "plugin-notes",
                    provider = "plugin-llm-claude-cli",
                    iface = "d8gp.llm",
                    dataFlow = DataFlow.RequestBearing,
                    state = ConnectionStates.ACTIVE,
                ),
                GraphConnection(
                    id = "cn_gcal",
                    consumer = "plugin-gcal",
                    provider = "plugin-events",
                    iface = "d8gp.events",
                    dataFlow = DataFlow.ResponseBearing,
                    state = ConnectionStates.ACTIVE,
                ),
                GraphConnection(
                    id = "cn_gone",
                    consumer = "plugin-revoked",
                    provider = "plugin-llm-claude-cli",
                    iface = "d8gp.llm",
                    dataFlow = DataFlow.RequestBearing,
                    state = ConnectionStates.REVOKED,
                ),
            ),
        )
        val text = ConsentText.render(record, busy, context)
        assertTrue("This provider already serves: plugin-notes." in text)
        assertTrue("its events are read by: plugin-gcal." in text)
        assertFalse("plugin-revoked" in text)
    }

    @Test
    fun `a program that was never approved is not described as signed`() {
        val text = ConsentText.render(record, graph, context.copy(programs = emptyList()))
        assertTrue("runs the program claude (not approved yet)," in text)
        assertTrue("which contacts servers d8gp does not know." in text)
    }

    @Test
    fun `an unsigned program without a lockdown profile says so`() {
        val text = ConsentText.renderExecGrant(
            grant.copy(signer = null, unsignedSha256 = "ab".repeat(32), lockdownCheck = null, resolvedPath = null),
        )
        assertTrue("Signed by     nobody. Pinned by SHA-256 ${"ab".repeat(32)}" in text)
        assertTrue("Lockdown check: not available for this program." in text)
        assertFalse("->" in text)
    }

    @Test
    fun `the consent hash follows the text`() {
        val text = ConsentText.render(record, graph, context)
        assertNotEquals(
            Sha256.hexOfUtf8(text),
            Sha256.hexOfUtf8(ConsentText.render(record, graph, context.copy(requestedFrom = "ui"))),
        )
    }
}
