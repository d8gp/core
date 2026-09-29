package com.d8gp.plugins.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ManifestTraitsTest {
    private val hash = "a".repeat(64)

    private fun base(cap: JsonObject = feedCap()): JsonObject = buildJsonObject {
        put("manifestVersion", 1)
        put("id", "feed-reddit")
        put("name", "Reddit")
        put("version", "1.0.0")
        put("entry", buildJsonObject {
            put("url", "https://plugins.d8gp.com/feed-reddit/1.0.0/bundle.js")
            put("sha256", hash)
        })
        put("capabilities", buildJsonArray { add(cap) })
        put("permissions", buildJsonObject {})
    }

    private fun feedCap(): JsonObject = buildJsonObject {
        put("type", "feed")
        put("sourceKey", "reddit")
        put("name", "Reddit")
        put("icon", "logo-reddit")
        put("color", "#ff4500")
    }

    private fun libraryCap(): JsonObject = buildJsonObject {
        put("type", "library")
        put("providerKey", "reddit-lib")
        put("name", "Reddit")
        put("icon", "library")
    }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(this + (key to value))

    @Test
    fun `needsSignIn when the auth permission is requested`() {
        val m = parseManifest(base().with("permissions", buildJsonObject { put("auth", true) }))
        assertTrue(needsSignIn(m))
    }

    @Test
    fun `needsSignIn when the plugin runs on the webview substrate`() {
        val m = parseManifest(
            base().with(
                "webview",
                buildJsonObject {
                    put("url", "https://web.example.com/")
                    put("origins", buildJsonArray { add("example.com") })
                },
            ),
        )
        assertTrue(needsSignIn(m))
    }

    @Test
    fun `no sign-in when auth is absent or false and no webview`() {
        assertFalse(needsSignIn(parseManifest(base())))
        val explicitFalse = parseManifest(base().with("permissions", buildJsonObject { put("auth", false) }))
        assertFalse(needsSignIn(explicitFalse))
    }

    @Test
    fun `providesFeed follows the feed capability`() {
        assertTrue(providesFeed(parseManifest(base())))
        assertFalse(providesFeed(parseManifest(base(cap = libraryCap()))))
    }

    private fun connector(role: String, iface: String, egress: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "connector")
            put("role", role)
            put("interface", iface)
            put("version", 1)
            put("methods", buildJsonArray { add("run") })
            put("name", "Connector")
            if (role == "requires") put("reason", "Needs it.") else put("egress", buildJsonArray { egress.forEach(::add) })
        }

    private fun execPermission(): JsonObject = buildJsonObject {
        put("exec", buildJsonArray {
            add(buildJsonObject {
                put("binary", "claude")
                put("purpose", "Runs a helper program.")
                put("argv", buildJsonArray { add("-p") })
            })
        })
    }

    @Test
    fun `providesInterface and requiresInterface find the declaration by role`() {
        val both = parseManifest(
            base().with(
                "capabilities",
                buildJsonArray {
                    add(connector("provides", "d8gp.events"))
                    add(connector("requires", "d8gp.llm"))
                },
            ),
        )
        assertEquals("d8gp.events", providesInterface(both, "d8gp.events")?.iface)
        assertEquals("d8gp.llm", requiresInterface(both, "d8gp.llm")?.iface)
        assertNull(providesInterface(both, "d8gp.llm"))
        assertNull(requiresInterface(both, "d8gp.events"))
        assertNull(providesInterface(parseManifest(base()), "d8gp.events"))
    }

    @Test
    fun `a model provider may not hold storage`() {
        val provider = base(cap = connector("provides", "d8gp.llm"))
        assertNull(connectorInstallRefusal(parseManifest(provider)))
        assertNotNull(
            connectorInstallRefusal(
                parseManifest(provider.with("permissions", buildJsonObject { put("storage", true) })),
            ),
        )
    }

    @Test
    fun `a provider with unrestricted network is refused`() {
        val wildcard = buildJsonObject { put("net", buildJsonArray { add("*") }) }
        val provider = base(cap = connector("provides", "d8gp.events", egress = listOf("anything.example")))
        assertNotNull(connectorInstallRefusal(parseManifest(provider.with("permissions", wildcard))))
    }

    @Test
    fun `a task provider may not run programs`() {
        val task = base(cap = connector("provides", "d8gp.task"))
        assertNull(connectorInstallRefusal(parseManifest(task)))
        assertNotNull(connectorInstallRefusal(parseManifest(task.with("permissions", execPermission()))))
    }

    @Test
    fun `consumers and ordinary plugins are never refused as connectors`() {
        val greedy = buildJsonObject {
            put("net", buildJsonArray { add("*") })
            put("storage", true)
        }
        assertNull(connectorInstallRefusal(parseManifest(base().with("permissions", greedy))))
        assertNull(
            connectorInstallRefusal(
                parseManifest(base(cap = connector("requires", "d8gp.llm")).with("permissions", greedy)),
            ),
        )
    }

    @Test
    fun `network installs refuse reviewed privileges including running programs`() {
        assertNull(networkInstallRefusal(parseManifest(base())))
        assertEquals(
            "asks to run programs on this device",
            networkInstallRefusal(parseManifest(base().with("permissions", execPermission()))),
        )
        assertEquals(
            "asks for the auth vault",
            networkInstallRefusal(parseManifest(base().with("permissions", buildJsonObject { put("auth", true) }))),
        )
        assertEquals(
            "asks for contacts",
            networkInstallRefusal(
                parseManifest(base().with("permissions", buildJsonObject { put("contacts", true) })),
            ),
        )
        assertEquals(
            "asks for unrestricted network",
            networkInstallRefusal(
                parseManifest(base().with("permissions", buildJsonObject { put("net", buildJsonArray { add("*") }) })),
            ),
        )
        assertEquals(
            "runs on the WebView substrate",
            networkInstallRefusal(
                parseManifest(
                    base().with(
                        "webview",
                        buildJsonObject {
                            put("url", "https://web.example.com/")
                            put("origins", buildJsonArray { add("example.com") })
                        },
                    ),
                ),
            ),
        )
    }
}
