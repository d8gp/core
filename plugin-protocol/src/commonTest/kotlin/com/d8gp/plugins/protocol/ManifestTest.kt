package com.d8gp.plugins.protocol

import com.d8gp.plugins.protocol.connections.VectorReport
import com.d8gp.plugins.protocol.connections.VectorsGenerated
import com.d8gp.plugins.protocol.connections.text
import com.d8gp.plugins.protocol.connections.vectorList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test

// Port of tests/unit/pluginManifest.test.ts, plus exact-message pins for the
// "manifest.<path>: <msg>" first-error contract.
class ManifestTest {
    private val hash = "a".repeat(64)

    private fun base(): JsonObject = buildJsonObject {
        put("manifestVersion", 1)
        put("id", "feed-reddit")
        put("name", "Reddit")
        put("version", "1.0.0")
        put("entry", buildJsonObject {
            put("url", "https://plugins.d8gp.com/feed-reddit/1.0.0/bundle.js")
            put("sha256", hash)
            put("size", 4096)
        })
        put("capabilities", buildJsonArray {
            add(buildJsonObject {
                put("type", "feed")
                put("sourceKey", "reddit")
                put("name", "Reddit")
                put("icon", "logo-reddit")
                put("color", "#ff4500")
            })
        })
        put("permissions", buildJsonObject {
            put("net", buildJsonArray {
                add("reddit.com")
                add("redd.it")
            })
            put("storage", true)
        })
    }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(this + (key to value))

    private fun errorOf(json: JsonElement): String {
        try {
            parseManifest(json)
        } catch (e: ManifestException) {
            return e.message!!
        }
        fail("expected ManifestException")
        throw AssertionError()
    }

    @Test
    fun `accepts a well-formed feed manifest and normalizes it`() {
        val m = parseManifest(base())
        assertEquals("feed-reddit", m.id)
        val cap = m.capabilities[0] as FeedCapabilityDecl
        assertEquals("feed", cap.type)
        assertEquals("reddit", cap.sourceKey)
        assertEquals(listOf("reddit.com", "redd.it"), m.permissions.net)
        assertEquals(true, m.permissions.storage)
        assertEquals(4096L, m.entry.size)
        assertEquals(listOf("feed"), capabilityTypes(m))
    }

    @Test
    fun `parses from a raw JSON string`() {
        val m = parseManifest(base().toString())
        assertEquals("feed-reddit", m.id)
    }

    @Test
    fun `parses the contacts permission and defaults it to absent`() {
        assertEquals(null, parseManifest(base()).permissions.contacts)
        val granted = parseManifest(
            base().with(
                "permissions",
                buildJsonObject { put("contacts", true) },
            ),
        )
        assertEquals(true, granted.permissions.contacts)
        assertTrue(hasPermission(granted.permissions, "contacts"))
    }

    private fun messagingManifest(): JsonObject =
        base()
            .with("id", JsonPrimitive("messaging-matrix"))
            .with("capabilities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "messaging")
                    put("protocol", "matrix")
                    put("displayName", "Matrix")
                    put("icon", "chatbubbles")
                    put("capabilities", buildJsonObject {
                        put("canSend", true)
                        put("groupMessaging", true)
                        put("attachments", buildJsonObject {
                            put("image", false)
                            put("video", false)
                            put("audio", false)
                            put("file", false)
                        })
                        put("deleteMessage", false)
                        put("deleteConversation", false)
                        put("markRead", true)
                        put("resend", false)
                        put("contactSearch", false)
                    })
                })
            })

    @Test
    fun `accepts a messaging manifest with a full capabilities block`() {
        val m = parseManifest(messagingManifest())
        val cap = m.capabilities[0] as MessagingCapabilityDecl
        assertEquals("messaging", cap.type)
        assertEquals("matrix", cap.protocol)
        assertNull(cap.color)
        assertTrue(cap.capabilities.markRead)
        assertFalse(cap.capabilities.attachments.image)
    }

    @Test
    fun `rejects malformed manifests`() {
        val cases = listOf(
            JsonPrimitive(42),
            base().with("manifestVersion", JsonPrimitive(2)),
            base().with("id", JsonPrimitive("No Caps!")),
            base().with("version", JsonPrimitive("1.0")),
            base().with("entry", buildJsonObject {
                put("url", "https://x/y.js")
                put("sha256", "abc")
            }),
            base().with("entry", buildJsonObject {
                put("url", "ftp://x/y.js")
                put("sha256", hash)
            }),
            base().with("capabilities", buildJsonArray {}),
            base().with("capabilities", buildJsonArray {
                add(buildJsonObject { put("type", "wormhole") })
            }),
            base().with("capabilities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "messaging")
                    put("protocol", "m")
                    put("displayName", "M")
                    put("icon", "x")
                })
            }),
        )
        for (bad in cases) {
            try {
                parseManifest(bad)
                fail("expected rejection: $bad")
            } catch (_: ManifestException) {
            }
            assertFalse(isValidManifest(bad))
        }
    }

    @Test
    fun `first-error messages match the TS validator verbatim`() {
        assertEquals("manifest.: must be a JSON object", errorOf(JsonPrimitive(42)))
        assertEquals(
            "manifest.manifestVersion: must be 1",
            errorOf(base().with("manifestVersion", JsonPrimitive(2))),
        )
        assertEquals(
            "manifest.manifestVersion: must be 1",
            errorOf(base().with("manifestVersion", JsonPrimitive("1"))),
        )
        assertEquals(
            "manifest.id: must match ^[a-z0-9][a-z0-9._-]{2,63}\$",
            errorOf(base().with("id", JsonPrimitive("No Caps!"))),
        )
        assertEquals(
            "manifest.version: must be semver x.y.z",
            errorOf(base().with("version", JsonPrimitive("1.0"))),
        )
        assertEquals(
            "manifest.minAppVersion: must be semver x.y.z",
            errorOf(base().with("minAppVersion", JsonPrimitive("1.0"))),
        )
        assertEquals(
            "manifest.entry.sha256: must be 64-char hex",
            errorOf(base().with("entry", buildJsonObject {
                put("url", "https://x/y.js")
                put("sha256", "abc")
            })),
        )
        assertEquals(
            "manifest.entry.url: must be an http(s) URL",
            errorOf(base().with("entry", buildJsonObject {
                put("url", "ftp://x/y.js")
                put("sha256", hash)
            })),
        )
        assertEquals(
            "manifest.entry.size: must be a non-negative number",
            errorOf(base().with("entry", buildJsonObject {
                put("url", "https://x/y.js")
                put("sha256", hash)
                put("size", -1)
            })),
        )
        assertEquals(
            "manifest.capabilities: must be a non-empty array",
            errorOf(base().with("capabilities", buildJsonArray {})),
        )
        assertEquals(
            "manifest.capabilities[0].type: must be one of feed, messaging, library, connector",
            errorOf(base().with("capabilities", buildJsonArray {
                add(buildJsonObject { put("type", "wormhole") })
            })),
        )
        assertEquals(
            "manifest.capabilities[0].capabilities: required object",
            errorOf(base().with("capabilities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "messaging")
                    put("protocol", "m")
                    put("displayName", "M")
                    put("icon", "x")
                })
            })),
        )
        assertEquals(
            "manifest.capabilities[0].capabilities.attachments: required object",
            errorOf(base().with("capabilities", buildJsonArray {
                add(buildJsonObject {
                    put("type", "messaging")
                    put("protocol", "m")
                    put("displayName", "M")
                    put("icon", "x")
                    put("capabilities", buildJsonObject { put("canSend", true) })
                })
            })),
        )
        assertEquals(
            "manifest.permissions.net: must be an array of strings",
            errorOf(base().with("permissions", buildJsonObject {
                put("net", "reddit.com")
            })),
        )
        assertEquals(
            "manifest.author: must be an object",
            errorOf(base().with("author", JsonPrimitive("mynah"))),
        )
    }

    @Test
    fun `check ordering matches TS - name is validated after capabilities`() {
        val noNameNoCaps = JsonObject(base() - "name").with("capabilities", buildJsonArray {})
        assertEquals("manifest.capabilities: must be a non-empty array", errorOf(noNameNoCaps))
        assertEquals(
            "manifest.name: required non-empty string",
            errorOf(JsonObject(base() - "name")),
        )
    }

    @Test
    fun `lowercases a mixed-case sha256 and accepts all-zeros`() {
        val mixed = parseManifest(base().with("entry", buildJsonObject {
            put("url", "https://x/y.js")
            put("sha256", "A".repeat(64))
        }))
        assertEquals("a".repeat(64), mixed.entry.sha256)
        val zeros = parseManifest(base().with("entry", buildJsonObject {
            put("url", "https://x/y.js")
            put("sha256", "0".repeat(64))
        }))
        assertEquals("0".repeat(64), zeros.entry.sha256)
    }

    @Test
    fun `missing permissions defaults to empty and denies everything`() {
        val m = parseManifest(JsonObject(base() - "permissions"))
        assertEquals(PluginPermissions(), m.permissions)
        assertFalse(hasPermission(m.permissions, "storage"))
        assertFalse(hostAllowed("https://reddit.com/", m.permissions))
    }

    @Test
    fun `accepts author and optional fields`() {
        val m = parseManifest(
            base()
                .with("description", JsonPrimitive("Reddit feeds"))
                .with("minAppVersion", JsonPrimitive("0.2.0"))
                .with("author", buildJsonObject { put("npub", "npub1abc") }),
        )
        assertEquals("Reddit feeds", m.description)
        assertEquals("0.2.0", m.minAppVersion)
        assertEquals(PluginAuthor(name = null, npub = "npub1abc"), m.author)
    }

    @Test
    fun `accepts a webview substrate and defaults it to null when absent`() {
        assertNull(parseManifest(base()).webview)
        val m = parseManifest(
            base().with("webview", buildJsonObject {
                put("url", "https://messages.google.com/web/")
                put("origins", buildJsonArray {
                    add("messages.google.com")
                    add("google.com")
                })
            }),
        )
        assertEquals("https://messages.google.com/web/", m.webview?.url)
        assertEquals(listOf("messages.google.com", "google.com"), m.webview?.origins)
    }

    @Test
    fun `rejects malformed webview substrates`() {
        assertEquals(
            "manifest.webview: must be an object",
            errorOf(base().with("webview", JsonPrimitive("nope"))),
        )
        assertEquals(
            "manifest.webview.url: must be an http(s) URL",
            errorOf(base().with("webview", buildJsonObject {
                put("url", "ftp://messages.google.com/")
                put("origins", buildJsonArray { add("google.com") })
            })),
        )
        // Scheme-only (no host) must be rejected: it would form malformed
        // WebView origin rules ("https://*.").
        assertEquals(
            "manifest.webview.url: must be an http(s) URL",
            errorOf(base().with("webview", buildJsonObject {
                put("url", "https:///boot")
                put("origins", buildJsonArray { add("google.com") })
            })),
        )
        assertEquals(
            "manifest.webview.url: required non-empty string",
            errorOf(base().with("webview", buildJsonObject {
                put("origins", buildJsonArray { add("google.com") })
            })),
        )
        assertEquals(
            "manifest.webview.origins: must be a non-empty array of strings",
            errorOf(base().with("webview", buildJsonObject {
                put("url", "https://messages.google.com/web/")
                put("origins", buildJsonArray {})
            })),
        )
    }

    @Test
    fun `unparseable JSON string is rejected like a non-object`() {
        try {
            parseManifest("not json")
            fail()
        } catch (e: ManifestException) {
            assertEquals("manifest.: must be JSON nested at most 64 levels (not-json)", e.message)
        }
        assertFalse(isValidManifest("not json"))
    }

    private val connectorVectors = vectorList(VectorsGenerated.CONNECTOR_MANIFESTS)

    private fun connectorManifest(name: String): JsonObject =
        connectorVectors.first { it.text("name") == name }.getValue("manifest").jsonObject

    private fun JsonObject.withCapability(change: (JsonObject) -> JsonObject): JsonObject =
        with("capabilities", buildJsonArray { add(change(getValue("capabilities").jsonArray[0].jsonObject)) })

    private fun errorPathOf(json: JsonElement): String =
        errorOf(json).removePrefix("manifest.").substringBefore(": ")

    private fun strings(json: JsonElement): List<String> = json.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `connector manifests match every shared vector`() {
        val report = VectorReport("connector-manifests.json", 406)
        for (vector in connectorVectors) {
            val manifest = vector.getValue("manifest")
            val expect = vector.getValue("expect")
            if (expect is JsonObject) {
                report.check(vector.text("name"), "rejects at <${expect.text("errorPath")}>") {
                    try {
                        parseManifest(manifest)
                        "parses"
                    } catch (e: ManifestException) {
                        "rejects at <${e.message!!.removePrefix("manifest.").substringBefore(": ")}>"
                    }
                }
                continue
            }
            val parsed = vector.getValue("parsed").jsonObject
            report.check(
                vector.text("name"),
                strings(parsed.getValue("capabilityTypes")) to strings(parsed.getValue("execBinaries")),
            ) {
                val result = parseManifest(manifest)
                result.capabilities.map { it.type } to result.permissions.exec.orEmpty().map { it.binary }
            }
        }
        report.finish()
    }

    @Test
    fun `every accepted connector manifest survives the serializer round trip`() {
        val accepted = connectorVectors.filter { it.getValue("expect") !is JsonObject }
        assertTrue(accepted.size > 30)
        for (vector in accepted) {
            val parsed = parseManifest(vector.getValue("manifest"))
            val wire = ProtocolJson.encodeToString(PluginManifest.serializer(), parsed)
            assertEquals(parsed, ProtocolJson.decodeFromString(PluginManifest.serializer(), wire), vector.text("name"))
            assertEquals(parsed, parseManifest(wire), vector.text("name"))
        }
    }

    @Test
    fun `a connector never parses as a library capability`() {
        val manifest = parseManifest(connectorManifest("ok/library-beside-connector-keeps-both-types"))
        assertIs<ConnectorCapabilityDecl>(manifest.capabilities[0])
        assertIs<LibraryCapabilityDecl>(manifest.capabilities[1])

        val decoded = ProtocolJson.decodeFromString(
            CapabilityDecl.serializer(),
            """{"type":"connector","role":"requires","interface":"d8gp.llm","version":1,
                "methods":["complete"],"name":"Language model","reason":"Asks a model."}""",
        )
        assertEquals(
            ConnectorCapabilityDecl(
                role = ConnectorRole.Requires,
                iface = "d8gp.llm",
                version = 1,
                methods = listOf("complete"),
                name = "Language model",
                reason = "Asks a model.",
            ),
            decoded,
        )
    }

    @Test
    fun `the interface is written under its wire name`() {
        val decl = ConnectorCapabilityDecl(
            role = ConnectorRole.Provides,
            iface = "d8gp.llm",
            version = 1,
            methods = listOf("complete"),
            name = "Model",
            egress = emptyList(),
        )
        assertEquals(
            """{"type":"connector","role":"provides","interface":"d8gp.llm","version":1,""" +
                """"methods":["complete"],"name":"Model","egress":[]}""",
            ProtocolJson.encodeToString(CapabilityDecl.serializer(), decl),
        )
    }

    @Test
    fun `an unknown capability type is refused by the validator and by the serializer`() {
        val job = connectorManifest("ok/consumer").withCapability { JsonObject(it + ("type" to JsonPrimitive("job"))) }
        assertEquals(
            "manifest.capabilities[0].type: must be one of feed, messaging, library, connector",
            errorOf(job),
        )
        assertFailsWith<Exception> { ProtocolJson.decodeFromJsonElement(PluginManifest.serializer(), job) }
    }

    @Test
    fun `plugin id host is reserved and host principals can never be plugin ids`() {
        val consumer = connectorManifest("ok/consumer")
        assertEquals("manifest.id: is reserved for the host", errorOf(consumer.with("id", JsonPrimitive("host"))))
        for (principal in listOf("host:daemon", "host:ui", "host:messages", "host:fixture")) {
            assertEquals("id", errorPathOf(consumer.with("id", JsonPrimitive(principal))), principal)
        }
        assertTrue(isValidManifest(consumer.with("id", JsonPrimitive("hosted-tools"))))
    }

    @Test
    fun `exec template is parsed into literals and slots and written back unchanged`() {
        val source = connectorManifest("ok/plugin-llm-claude-cli-design-section-1.2")
        val exec = parseManifest(source).permissions.exec!!.single()

        assertEquals("claude", exec.binary)
        assertEquals(ExecArg.Literal("-p"), exec.argv[0])
        assertEquals(
            ExecArg.Slot(flag = "--model", slot = "model", enum = listOf("haiku", "sonnet", "opus")),
            exec.argv[2],
        )
        assertEquals(ExecArg.Slot(flag = "--system-prompt", slot = "system", maxBytes = 32768), exec.argv[3])
        assertEquals(ExecArg.Literal("--tools="), exec.argv.last())
        assertEquals(ExecStdin(maxBytes = 524288), exec.stdin)

        assertEquals(
            source.getValue("permissions").jsonObject.getValue("exec").jsonArray[0],
            ProtocolJson.encodeToJsonElement(ExecDecl.serializer(), exec),
        )
    }

    @Test
    fun `exec declares a program name and never a path`() {
        val source = connectorManifest("ok/plugin-llm-claude-cli-design-section-1.2")
        val entry = source.getValue("permissions").jsonObject.getValue("exec").jsonArray[0].jsonObject
        for (binary in listOf("../sh", "/bin/sh", "bin/sh", "sh;id", "")) {
            val permissions = buildJsonObject {
                put("exec", buildJsonArray { add(JsonObject(entry + ("binary" to JsonPrimitive(binary)))) })
            }
            assertEquals(
                "permissions.exec[0].binary",
                errorPathOf(source.with("permissions", permissions)),
                binary,
            )
        }
    }

    @Test
    fun `egress is a covered hostname or a declared program and never prose`() {
        val provider = connectorManifest("ok/provider-anthropic-api")
        fun withEgress(entry: String) = provider.withCapability {
            JsonObject(it + ("egress" to buildJsonArray { add(entry) }))
        }
        assertTrue(isValidManifest(withEgress("api.anthropic.com")))
        for (entry in listOf(
            "sends data to Anthropic",
            "https://api.anthropic.com",
            "api.anthropic.com:443",
            "*.anthropic.com",
            "API.anthropic.com",
            "evil.example",
            "exec:claude",
        )) {
            assertEquals("capabilities[0].egress[0]", errorPathOf(withEgress(entry)), entry)
        }
    }

    @Test
    fun `control characters newlines and escape are refused in consent text`() {
        val consumer = connectorManifest("ok/consumer")
        val hostile = listOf(
            "line\nbreak",
            "carriage\rreturn",
            "tab\there",
            "esc\u001b[31mred",
            "nul\u0000",
            "del\u007f",
        )
        for (text in hostile) {
            for (field in listOf("name", "reason")) {
                assertEquals(
                    "capabilities[0].$field",
                    errorPathOf(consumer.withCapability { JsonObject(it + (field to JsonPrimitive(text))) }),
                    "$field <$text>",
                )
            }
        }

        val cli = connectorManifest("ok/plugin-llm-claude-cli-design-section-1.2")
        val entry = cli.getValue("permissions").jsonObject.getValue("exec").jsonArray[0].jsonObject
        for (text in hostile) {
            val permissions = buildJsonObject {
                put("exec", buildJsonArray { add(JsonObject(entry + ("purpose" to JsonPrimitive(text)))) })
            }
            assertEquals("permissions.exec[0].purpose", errorPathOf(cli.with("permissions", permissions)), text)
        }
    }

    // What a lenient reader makes of a text that is not JSON. parseManifest
    // refuses such a text, but a caller may hand over an element it read itself.
    private fun parseLeniently(text: String): PluginManifest = parseManifest(Json.parseToJsonElement(text))

    private val notJsonAtTheRoot = "manifest.: must be JSON nested at most 64 levels (not-json)"

    @Test
    fun `a literal outside the json number grammar is no integer`() {
        val consumer = ProtocolJson.encodeToString(JsonObject.serializer(), connectorManifest("ok/consumer"))
        assertTrue(""""version":1,""" in consumer)
        for (literal in listOf("1d", "1f", "0x1p0", "01", "+1", "1.", "NaN")) {
            assertEquals(
                "manifest.capabilities[0].version: must be an integer of at least 1",
                assertFailsWith<ManifestException>(literal) {
                    parseLeniently(consumer.replace(""""version":1,""", """"version":$literal,"""))
                }.message,
            )
            assertEquals(
                notJsonAtTheRoot,
                assertFailsWith<ManifestException>(literal) {
                    parseManifest(consumer.replace(""""version":1,""", """"version":$literal,"""))
                }.message,
            )
        }
    }

    @Test
    fun `a literal outside the json grammar is no number and no boolean anywhere in a manifest`() {
        val text = ProtocolJson.encodeToString(JsonObject.serializer(), base())
        fun errorOfText(from: String, to: String): String {
            assertTrue(from in text, from)
            val edited = text.replace(from, to)
            assertEquals(notJsonAtTheRoot, assertFailsWith<ManifestException>(to) { parseManifest(edited) }.message)
            return assertFailsWith<ManifestException>(to) { parseLeniently(edited) }.message!!
        }

        for (literal in listOf("01", "1.", "+1", "1d", "1f", ".1e1", "0x1", "NaN")) {
            assertEquals(
                "manifest.manifestVersion: must be 1",
                errorOfText(""""manifestVersion":1,""", """"manifestVersion":$literal,"""),
            )
        }
        for (literal in listOf("01", "4096d", "0x10", "NaN", "Infinity", "+4096")) {
            assertEquals(
                "manifest.entry.size: must be a non-negative number",
                errorOfText(""""size":4096""", """"size":$literal"""),
            )
        }
        for (literal in listOf("TRUE", "True", "tRuE", "FALSE", "False")) {
            assertEquals(
                "manifest.permissions.storage: must be a boolean",
                errorOfText(""""storage":true""", """"storage":$literal"""),
            )
        }
        assertEquals(1e400.toLong(), parseManifest(text.replace(""""size":4096""", """"size":1e400""")).entry.size)
        assertEquals(1, parseManifest(text.replace(""""manifestVersion":1,""", """"manifestVersion":10e-1,""")).manifestVersion)
    }

    @Test
    fun `a messaging flag outside the json grammar is no boolean`() {
        val messaging = ProtocolJson.encodeToString(JsonObject.serializer(), messagingManifest())
        assertTrue(""""canSend":true""" in messaging)
        assertEquals(
            "manifest.capabilities[0].capabilities.canSend: required boolean",
            assertFailsWith<ManifestException> {
                parseLeniently(messaging.replace(""""canSend":true""", """"canSend":TRUE"""))
            }.message,
        )
        assertEquals(
            notJsonAtTheRoot,
            assertFailsWith<ManifestException> {
                parseManifest(messaging.replace(""""canSend":true""", """"canSend":TRUE"""))
            }.message,
        )
    }

    @Test
    fun `an exec entry that reaches a manifest through the serializer obeys the validator`() {
        val cli = connectorManifest("ok/plugin-llm-claude-cli-design-section-1.2")
        val text = ProtocolJson.encodeToString(JsonObject.serializer(), cli)
        assertEquals("claude", ProtocolJson.decodeFromString(PluginManifest.serializer(), text).permissions.exec!![0].binary)
        assertFailsWith<IllegalArgumentException> {
            ProtocolJson.decodeFromString(
                PluginManifest.serializer(),
                text.replace(""""binary":"claude"""", """"binary":"../../bin/sh""""),
            )
        }
    }

    @Test
    fun `the id predicate accepts what the validator accepts and nothing path-shaped`() {
        for (id in listOf("plugin-events", "abc", "a.b_c-d", "0day")) assertTrue(isPluginId(id), id)
        val refused = listOf("", "ab", "host", "Plugin", "../outside/evil", "plugin/x", ".hidden", "-dash", "a".repeat(65), "plugin x")
        for (id in refused) assertFalse(isPluginId(id), id)
    }
}
