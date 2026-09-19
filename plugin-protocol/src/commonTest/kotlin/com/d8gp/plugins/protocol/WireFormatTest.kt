package com.d8gp.plugins.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

// Pins the serialized shapes of the shared wire types: discriminator INSIDE
// the capability object (no kotlinx class-discriminator wrapper), absent
// optionals omitted exactly like JSON.stringify dropping undefined.
class WireFormatTest {
    @Test
    fun `feed capability round-trips with the type field inside the object`() {
        val json = """{"type":"feed","sourceKey":"reddit","name":"Reddit","icon":"logo-reddit","color":"#ff4500"}"""
        val decoded = ProtocolJson.decodeFromString<CapabilityDecl>(json)
        assertTrue(decoded is FeedCapabilityDecl)
        assertEquals("reddit", (decoded as FeedCapabilityDecl).sourceKey)
        assertEquals(json, ProtocolJson.encodeToString<CapabilityDecl>(decoded))
    }

    @Test
    fun `messaging capability omits an absent color`() {
        val cap: CapabilityDecl = MessagingCapabilityDecl(
            protocol = "matrix",
            displayName = "Matrix",
            icon = "chatbubbles",
            capabilities = MessagingCapabilitiesDecl(
                canSend = true,
                groupMessaging = true,
                attachments = MessagingAttachmentsDecl(image = false, video = false, audio = false, file = false),
                deleteMessage = false,
                deleteConversation = false,
                markRead = true,
                resend = false,
                contactSearch = false,
            ),
        )
        val encoded = ProtocolJson.encodeToString(cap)
        assertTrue(encoded.startsWith("""{"type":"messaging","protocol":"matrix""""))
        assertFalse(encoded.contains("color"))
        val decoded = ProtocolJson.decodeFromString<CapabilityDecl>(encoded)
        assertEquals(cap, decoded)
    }

    @Test
    fun `library capability decodes with optional origins`() {
        val decoded = ProtocolJson.decodeFromString<CapabilityDecl>(
            """{"type":"library","providerKey":"plex","name":"Plex","icon":"library","origins":["plex.tv"]}""",
        )
        assertEquals(
            LibraryCapabilityDecl(providerKey = "plex", name = "Plex", icon = "library", origins = listOf("plex.tv")),
            decoded,
        )
    }

    @Test
    fun `content item omits absent optional fields`() {
        val minimal = ContentItem(id = "reddit:1", source = "reddit", kind = "native", linkUrl = "https://x")
        assertEquals(
            """{"id":"reddit:1","source":"reddit","kind":"native","linkUrl":"https://x"}""",
            ProtocolJson.encodeToString(minimal),
        )
        val decoded = ProtocolJson.decodeFromString<ContentItem>(
            """{"id":"yt:2","source":"youtube","kind":"embed","embedUrl":"https://e","embedMode":"iframe","linkUrl":"https://y","likes":12}""",
        )
        assertEquals("embed", decoded.kind)
        assertEquals("iframe", decoded.embedMode)
        assertEquals(12L, decoded.likes)
        assertEquals(null, decoded.comments)
    }

    @Test
    fun `content item tolerates unknown fields from newer plugins`() {
        val decoded = ProtocolJson.decodeFromString<ContentItem>(
            """{"id":"a","source":"s","kind":"native","linkUrl":"https://x","futureField":true}""",
        )
        assertEquals("a", decoded.id)
    }

    @Test
    fun `installed plugin enums use the exact wire strings`() {
        val row = InstalledPlugin(
            id = "feed-reddit",
            manifest = parseManifest(manifestJson),
            runtime = PluginRuntimeKind.Sandboxed,
            origin = PluginOrigin.Builtin,
            enabled = false,
            installedAt = 1,
            updatedAt = 2,
            crashCount = 3,
            disabledReason = PluginDisabledReason.CrashLoop,
        )
        val encoded = ProtocolJson.encodeToString(row)
        assertTrue(encoded.contains(""""runtime":"sandboxed""""))
        assertTrue(encoded.contains(""""origin":"builtin""""))
        assertTrue(encoded.contains(""""disabledReason":"crash-loop""""))
        assertEquals(row, ProtocolJson.decodeFromString<InstalledPlugin>(encoded))
    }

    @Test
    fun `parsed manifest re-encodes with manifestVersion and permissions present`() {
        val encoded = ProtocolJson.encodeToString(parseManifest(manifestJson))
        assertTrue(encoded.contains(""""manifestVersion":1"""))
        assertTrue(encoded.contains(""""permissions":{"net":["reddit.com"]}"""))
        assertFalse(encoded.contains("description"))
    }

    private val manifestJson = """
        {
          "manifestVersion": 1,
          "id": "feed-reddit",
          "name": "Reddit",
          "version": "1.0.0",
          "entry": { "url": "https://x/y.js", "sha256": "${"a".repeat(64)}" },
          "capabilities": [
            { "type": "feed", "sourceKey": "reddit", "name": "Reddit", "icon": "logo-reddit", "color": "#ff4500" }
          ],
          "permissions": { "net": ["reddit.com"] }
        }
    """.trimIndent()
}
