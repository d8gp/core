@file:OptIn(ExperimentalSerializationApi::class)

package com.d8gp.plugins.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Kotlin twin of plugin-host-core/types.ts — JSON field names are the wire
// format shared with the RN host and plugin bundles, verbatim.

@Serializable
data class PluginPermissions(
    val net: List<String>? = null,
    val storage: Boolean? = null,
    val media: Boolean? = null,
    val saved: Boolean? = null,
    val ui: Boolean? = null,
    val auth: Boolean? = null,
    // Read access to the host's d8gp contacts (contacts.lookup / contacts.list).
    // Additive: absent in old manifests, ignored by hosts that predate it.
    val contacts: Boolean? = null,
)

@Serializable
data class MessagingAttachmentsDecl(
    val image: Boolean,
    val video: Boolean,
    val audio: Boolean,
    val file: Boolean,
)

@Serializable
data class MessagingCapabilitiesDecl(
    val canSend: Boolean,
    val groupMessaging: Boolean,
    val attachments: MessagingAttachmentsDecl,
    val deleteMessage: Boolean,
    val deleteConversation: Boolean,
    val markRead: Boolean,
    val resend: Boolean,
    val contactSearch: Boolean,
)

// Discriminated on the `type` field INSIDE the object (TS union style) — the
// custom serializer below avoids kotlinx's class-discriminator wrapper.
@Serializable(with = CapabilityDeclSerializer::class)
sealed class CapabilityDecl {
    abstract val type: String
}

@Serializable
data class FeedCapabilityDecl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "feed",
    val sourceKey: String,
    val name: String,
    val icon: String, // Ionicon name
    val color: String,
) : CapabilityDecl()

@Serializable
data class MessagingCapabilityDecl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "messaging",
    val protocol: String,
    val displayName: String,
    val icon: String,
    val color: String? = null,
    val capabilities: MessagingCapabilitiesDecl,
) : CapabilityDecl()

@Serializable
data class LibraryCapabilityDecl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "library",
    val providerKey: String,
    val name: String,
    val icon: String,
    val origins: List<String>? = null,
) : CapabilityDecl()

object CapabilityDeclSerializer : JsonContentPolymorphicSerializer<CapabilityDecl>(CapabilityDecl::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CapabilityDecl> =
        when (val type = element.jsonObject["type"]?.jsonPrimitive?.content) {
            "feed" -> FeedCapabilityDecl.serializer()
            "messaging" -> MessagingCapabilityDecl.serializer()
            "library" -> LibraryCapabilityDecl.serializer()
            else -> throw SerializationException("unknown capability type: $type")
        }
}

@Serializable
data class PluginEntry(
    val url: String,
    val sha256: String, // 64-char lowercase hex of bundle.js
    val size: Long? = null,
)

@Serializable
data class PluginAuthor(
    val name: String? = null,
    val npub: String? = null,
)

// Native-WebView substrate declaration. A plugin that declares `webview` is not
// a DOM-free sandboxed bundle: the host loads `url` in a real, network-enabled
// WebView on its own isolated profile (cookies/storage separate from the app
// and every other plugin), and the plugin's bundle runs as a content script in
// that page — so the site's own web app handles login/pairing/crypto and the
// plugin drives its DOM. `origins` is the navigation allowlist (hostname-suffix
// match, like PluginPermissions.net) that bounds where the WebView may go.
@Serializable
data class WebViewSubstrate(
    val url: String, // http(s) URL the isolated WebView loads and pins as its origin
    val origins: List<String>, // hostname-suffix navigation allowlist
)

@Serializable
data class PluginManifest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val manifestVersion: Int = 1,
    val id: String, // ^[a-z0-9][a-z0-9._-]{2,63}$ — also install dir + nostr `d` tag
    val name: String,
    val version: String, // semver x.y.z
    val description: String? = null,
    val author: PluginAuthor? = null,
    val entry: PluginEntry,
    val minAppVersion: String? = null,
    val capabilities: List<CapabilityDecl>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val permissions: PluginPermissions = PluginPermissions(),
    // Present ⇒ this plugin runs on the native-WebView substrate (see
    // WebViewSubstrate) rather than the sandboxed iframe shell. Absent ⇒
    // ordinary sandboxed bundle. Additive/optional so existing manifests and
    // the RN host (which ignores it) are unaffected.
    val webview: WebViewSubstrate? = null,
)

@Serializable
enum class PluginRuntimeKind {
    @SerialName("bundled") Bundled,
    @SerialName("sandboxed") Sandboxed,
    @SerialName("webview") WebView,
}

@Serializable
enum class PluginOrigin {
    @SerialName("builtin") Builtin,
    @SerialName("url") Url,
    @SerialName("index") Index,
}

@Serializable
enum class PluginDisabledReason {
    @SerialName("crash-loop") CrashLoop,
    @SerialName("user") User,
    @SerialName("incompatible") Incompatible,
}

@Serializable
data class InstalledPlugin(
    val id: String,
    val manifest: PluginManifest,
    val runtime: PluginRuntimeKind,
    val origin: PluginOrigin,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val authorPubkey: String? = null, // hex, TOFU pin when installed from the d8gp-net index
    val crashCount: Int = 0, // consecutive; reset on a successful call
    val disabledReason: PluginDisabledReason? = null,
)
