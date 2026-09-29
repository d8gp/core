package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ExecArg
import com.d8gp.plugins.protocol.ExecDecl
import com.d8gp.plugins.protocol.ManifestException
import com.d8gp.plugins.protocol.PluginManifest
import com.d8gp.plugins.protocol.ProtocolJson
import com.d8gp.plugins.protocol.parseManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsentTextInjectionTest {
    private val esc = '\u001b'

    private val hostileTexts = listOf(
        "x\n  Credentials    none. Approved by Apple",
        "x\r\nSigned by     Apple Inc.",
        "x$esc[2J$esc[1;1HLockdown check: passed",
        "x\u2028Lockdown check: passed",
        "x\u2029This program is safe",
        "x\u0085Signed by     Apple Inc.",
        "x\u0000y",
        "x\u007fy",
        "safe \u202e txet",
        "\u200bhidden",
        "a\u2066b\u2069",
        "\ufeffbom",
        "lone\ud83d",
    )

    private val record = ConnectionRecord(
        id = "cn_1",
        consumer = ConnectionConsumer(plugin = "plugin-events", version = "0.1.0"),
        provider = ConnectionProvider(
            plugin = "plugin-llm-claude-cli",
            version = "0.1.0",
            exec = listOf("claude"),
            egress = listOf("exec:claude"),
        ),
        iface = "d8gp.messages",
        major = 1,
        methods = listOf("list"),
        budget = Budget(),
        state = ConnectionStates.ACTIVE,
    )

    private val reading = GraphConnection(
        id = "cn_msgs",
        consumer = "plugin-events",
        provider = "host:messages",
        iface = "d8gp.messages",
        dataFlow = DataFlow.ResponseBearing,
        state = ConnectionStates.ACTIVE,
    )

    private val graph = EgressGraph(connections = listOf(reading))

    private val context = ConsentContext(
        consumerName = "Event extraction",
        providerName = "Claude via Claude Code CLI",
        reason = "Asks a model.",
        consumerProvides = listOf("d8gp.events"),
        programs = listOf(ProgramFacts(binary = "claude", path = "/bin/claude", signedBy = "Anthropic PBC")),
        providerCap = ProviderCap(requestsPerDay = 500, tokensPerDay = 2_000_000),
        credentialNote = "d8gp never reads your Claude login.",
    )

    private val decl = ExecDecl(
        binary = "claude",
        purpose = "Runs a helper program.",
        argv = listOf(
            ExecArg.Literal("-p"),
            ExecArg.Slot(flag = "--model", slot = "model", enum = listOf("haiku", "sonnet")),
        ),
    )

    private val grant = ExecGrantRequest(
        pluginName = "Claude via Claude Code CLI",
        pluginId = "plugin-llm-claude-cli",
        pluginVersion = "0.1.0",
        decl = decl,
        launchPath = "/Users/charles/.local/bin/claude",
        resolvedPath = "/Users/charles/.local/share/claude/versions/2.1.177",
        signer = SignerFacts(name = "Anthropic PBC", teamId = "Q6L2SF6YDW", identifier = "com.anthropic.claude-code"),
        lockdownCheck = "passed for version 2.1.177",
    )

    private fun sources(vararg names: String) =
        JsonObject(mapOf("sources" to buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }))

    private fun assertNothingHiddenOrMoving(text: String, expectedLines: Int, about: String) {
        assertEquals(expectedLines, text.lines().size, "$about\n$text")
        for (unit in text) {
            assertTrue(unit == '\n' || unit.code in 0x20..0x7e, "$about: U+${unit.code.toString(16)} reached the text")
        }
    }

    @Test
    fun `text that could forge a line cannot become an argv literal or an enum value`() {
        for (hostile in hostileTexts) {
            assertFailsWith<IllegalArgumentException>(hostile) { ExecArg.Literal("--tools=$hostile") }
            assertFailsWith<IllegalArgumentException>(hostile) {
                ExecArg.Slot(flag = "--model", slot = "model", enum = listOf("haiku", hostile))
            }
        }
    }

    @Test
    fun `the manifest with forged lockdown and signer lines is refused on both ways in`() {
        fun manifest(argv: String) =
            """{"manifestVersion":1,"id":"plugin-x","name":"X","version":"0.1.0",
                "entry":{"url":"https://plugins.d8gp.example/x/bundle.js","sha256":"${"a".repeat(64)}"},
                "capabilities":[{"type":"connector","role":"requires","interface":"d8gp.llm","version":1,
                  "methods":["complete"],"name":"Language model","reason":"Asks a model."}],
                "permissions":{"exec":[{"binary":"claude","purpose":"Runs a program.","argv":$argv}]}}"""

        val forgedLiteral = manifest(
            """["-p","--tools=\nLockdown check: passed for version 9.9.9 (no tools).\n\nThis program is safe"]""",
        )
        val forgedEnum = manifest(
            """[{"flag":"--model","slot":"model","enum":["haiku\u001b[2J\nSigned by     Apple Inc."]}]""",
        )
        assertEquals(
            "manifest.permissions.exec[0].argv[1]: must not contain control, invisible or line break characters",
            assertFailsWith<ManifestException> { parseManifest(forgedLiteral) }.message,
        )
        assertTrue(
            assertFailsWith<ManifestException> { parseManifest(forgedEnum) }.message!!
                .startsWith("manifest.permissions.exec[0].argv[0].enum: "),
        )
        for (forged in listOf(forgedLiteral, forgedEnum)) {
            assertFailsWith<IllegalArgumentException> {
                ProtocolJson.decodeFromString(PluginManifest.serializer(), forged)
            }
        }
        assertEquals("claude", parseManifest(manifest("""["-p"]""")).permissions.exec!!.single().binary)
    }

    @Test
    fun `text the contract allows is printed as it is`() {
        val name = "Événements 📅 日本語"
        val text = ConsentText.render(record, graph, context.copy(consumerName = name))
        assertTrue("  \"$name\" (plugin-events 0.1.0)" in text)
    }

    @Test
    fun `one argv element never reads as two`() {
        fun commandLine(vararg argv: String): String = ConsentText
            .renderExecGrant(grant.copy(decl = decl.copy(argv = argv.map(ExecArg::Literal))))
            .lines()
            .first { it.startsWith("Command line") }
            .removePrefix("Command line  ")

        assertEquals("claude -p --dangerously-skip-permissions", commandLine("-p", "--dangerously-skip-permissions"))
        assertEquals(
            """claude "-p --dangerously-skip-permissions"""",
            commandLine("-p --dangerously-skip-permissions"),
        )
        assertEquals("claude \"a\u00a0b\" \"\" -x", commandLine("a\u00a0b", "", "-x"))
        assertEquals("""claude "say \"hi\" now"""", commandLine("say \"hi\" now"))
        assertEquals("""claude --settings={"a":true}""", commandLine("""--settings={"a":true}"""))
    }

    @Test
    fun `text that spells an escape is told apart from a character that was escaped`() {
        fun programLine(path: String): String =
            ConsentText.renderExecGrant(grant.copy(launchPath = path)).lines().first { it.startsWith("Program") }

        assertEquals("""Program       a\u000ab""", programLine("a\nb"))
        assertEquals("""Program       a\\u000ab""", programLine("""a\u000ab"""))
    }

    @Test
    fun `an enum value that holds a space never reads as two alternatives`() {
        val two = decl.copy(
            argv = listOf(ExecArg.Slot(flag = "--model", slot = "model", enum = listOf("haiku or opus", "sonnet"))),
        )
        assertTrue(
            """The plugin fills in: model ("haiku or opus" or sonnet).""" in
                ConsentText.renderExecGrant(grant.copy(decl = two)),
        )
    }

    @Test
    fun `no value of an exec grant request can add a line`() {
        val honestLines = ConsentText.renderExecGrant(grant).lines().size
        for (hostile in hostileTexts) {
            val requests = listOf(
                grant.copy(pluginName = hostile),
                grant.copy(pluginId = hostile),
                grant.copy(pluginVersion = hostile),
                grant.copy(launchPath = hostile),
                grant.copy(resolvedPath = hostile),
                grant.copy(signer = SignerFacts(name = hostile, teamId = hostile, identifier = hostile)),
                grant.copy(signer = null, unsignedSha256 = hostile),
                grant.copy(lockdownCheck = hostile),
            )
            for (request in requests) {
                assertNothingHiddenOrMoving(ConsentText.renderExecGrant(request), honestLines, hostile)
            }
        }
    }

    @Test
    fun `no value of a connection can add a line to the consent text`() {
        val honestLines = ConsentText.render(record, graph, context).lines().size
        for (hostile in hostileTexts) {
            val records = listOf(
                record.copy(scope = sources(hostile)),
                record.copy(iface = hostile),
                record.copy(iface = "d8gp.llm", methods = listOf(hostile)),
                record.copy(consumer = record.consumer.copy(plugin = hostile, version = hostile)),
                record.copy(consumer = record.consumer.copy(net = listOf(hostile), exec = listOf(hostile))),
                record.copy(provider = record.provider.copy(plugin = hostile, version = hostile)),
                record.copy(provider = record.provider.copy(egress = listOf(hostile, "exec:claude"))),
                record.copy(budget = Budget(maxTier = hostile)),
            )
            for (changed in records) {
                val expected = if (changed.iface == hostile) honestLines + 1 else honestLines
                assertNothingHiddenOrMoving(ConsentText.render(changed, graph, context), expected, hostile)
            }

            val signed = ProgramFacts("claude", path = hostile, signedBy = hostile, contacts = hostile)
            val contexts = listOf(
                context.copy(consumerName = hostile, providerName = hostile, reason = hostile),
                context.copy(consumerProvides = listOf("d8gp.$hostile")),
                context.copy(programs = listOf(signed)),
                context.copy(programs = listOf(signed.copy(signedBy = null))),
                context.copy(credentialNote = hostile, requestedFrom = hostile),
            )
            for (changed in contexts) {
                assertNothingHiddenOrMoving(ConsentText.render(record, graph, changed), honestLines, hostile)
            }

            val hostileGraph = EgressGraph(
                connections = listOf(
                    reading.copy(provider = hostile, iface = hostile, scope = sources(hostile)),
                    reading.copy(id = "cn_x", consumer = hostile, provider = record.provider.plugin),
                    reading.copy(id = "cn_y", consumer = hostile, provider = "plugin-events", iface = "d8gp.events"),
                ),
            )
            assertNothingHiddenOrMoving(ConsentText.render(record, hostileGraph, context), honestLines, hostile)
        }
    }

    @Test
    fun `a program named by a hostile egress entry cannot add a line`() {
        val honest = record.copy(provider = record.provider.copy(egress = listOf("exec:claude", "exec:jq")))
        val honestLines = ConsentText.render(honest, graph, context).lines().size
        for (hostile in hostileTexts) {
            val changed = record.copy(provider = record.provider.copy(egress = listOf("exec:claude", "exec:$hostile")))
            assertNothingHiddenOrMoving(ConsentText.render(changed, graph, context), honestLines, hostile)
        }
    }

    @Test
    fun `a forged credentials line and a split name stay on the line that carries them`() {
        val forged = record.copy(scope = sources("email)\n  Credentials    none. Approved by Apple"))
        val lines = ConsentText.render(forged, EgressGraph(), ConsentContext("Evil\nname", "P")).lines()

        assertEquals(
            """  "Evil\u000aname" (plugin-events 0.1.0)   [name declared by the plugin, not verified]""",
            lines[2],
        )
        assertEquals(1, lines.count { it.trimStart().startsWith("Credentials") })
        assertTrue(lines.any { it.endsWith("""(email)\u000a  Credentials    none. Approved by Apple)""") })
    }

    @Test
    fun `a quote inside a declared name cannot close the quotation`() {
        val text = ConsentText.render(
            record,
            graph,
            context.copy(consumerName = """Notes" (plugin-trusted 9.9.9) "x""", reason = """a "b" c"""),
        )
        assertTrue("""  "Notes\" (plugin-trusted 9.9.9) \"x" (plugin-events 0.1.0)""" in text)
        assertTrue("  It says why    \"a \\\"b\\\" c\"" in text)
    }
}
