package com.d8gp.plugins.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `accepts a messaging manifest with a full capabilities block`() {
        val raw = base()
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
        val m = parseManifest(raw)
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
            "manifest.capabilities[0].type: must be one of feed, messaging, library",
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
            assertEquals("manifest.: must be a JSON object", e.message)
        }
        assertFalse(isValidManifest("not json"))
    }
}
