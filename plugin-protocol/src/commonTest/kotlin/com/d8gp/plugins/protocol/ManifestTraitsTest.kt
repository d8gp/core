package com.d8gp.plugins.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertFalse
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
}
