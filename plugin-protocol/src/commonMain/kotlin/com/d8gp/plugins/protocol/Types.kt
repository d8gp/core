@file:OptIn(ExperimentalSerializationApi::class)

package com.d8gp.plugins.protocol

import com.d8gp.plugins.protocol.connections.isConsentSafe
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Kotlin twin of plugin-host-core/types.ts — JSON field names are the wire
// format shared with the RN host and plugin bundles, verbatim.

internal const val CONSENT_TEXT_FLAW = "must not contain control, invisible or line break characters"

// Both are printed by the exec consent dialog, so both are consent text.
internal fun isExecLiteral(text: String): Boolean = isConsentSafe(text)

internal fun isExecEnumValue(text: String): Boolean = text.isNotEmpty() && isConsentSafe(text)

// The host renders a Slot as the single argv element "<flag>=<value>", so a
// plugin-supplied value can never become a flag of its own.
//
// The serializer is a second way into these classes that skips parseManifest,
// so the rules that decide what may reach an argv are enforced on
// construction and an instance that breaks them cannot exist.
@Serializable(with = ExecArgSerializer::class)
sealed class ExecArg {
    @Serializable(with = ExecLiteralSerializer::class)
    data class Literal(val value: String) : ExecArg() {
        init {
            require(isExecLiteral(value)) { "exec argv literal $CONSENT_TEXT_FLAW" }
        }
    }

    @Serializable
    data class Slot(
        val flag: String,
        val slot: String,
        val enum: List<String>? = null,
        val maxBytes: Int? = null,
    ) : ExecArg() {
        init {
            require(EXEC_FLAG_RE.matches(flag)) { "exec slot flag must match ^${EXEC_FLAG_RE.pattern}$" }
            require(EXEC_SLOT_RE.matches(slot)) { "exec slot name must match ^${EXEC_SLOT_RE.pattern}$" }
            require(enum == null || enum.all(::isExecEnumValue)) {
                "exec slot enum values must be non-empty and each $CONSENT_TEXT_FLAW"
            }
        }
    }
}

object ExecLiteralSerializer : KSerializer<ExecArg.Literal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.d8gp.plugins.protocol.ExecArg.Literal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExecArg.Literal) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): ExecArg.Literal = ExecArg.Literal(decoder.decodeString())
}

object ExecArgSerializer : JsonContentPolymorphicSerializer<ExecArg>(ExecArg::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ExecArg> = when {
        element is JsonPrimitive && element.isString -> ExecArg.Literal.serializer()
        element is JsonObject -> ExecArg.Slot.serializer()
        else -> throw SerializationException("exec argv element must be a string or a slot object")
    }
}

@Serializable
data class ExecStdin(
    val maxBytes: Int,
)

@Serializable
data class ExecDecl(
    val binary: String, // a NAME, never a path; the user supplies the path at grant time
    val purpose: String,
    val argv: List<ExecArg>,
    val stdin: ExecStdin? = null,
    val timeoutMs: Long? = null,
    val maxStdoutBytes: Long? = null,
) {
    init {
        require(EXEC_BINARY_RE.matches(binary)) { "exec binary must match ^${EXEC_BINARY_RE.pattern}$" }
    }
}

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
    // Declares programs the plugin may ask the host to run. A declaration
    // grants nothing: each binary needs its own sealed, user-approved grant.
    val exec: List<ExecDecl>? = null,
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

@Serializable
enum class ConnectorRole {
    @SerialName("provides") Provides,
    @SerialName("requires") Requires,
}

@Serializable
data class ConnectorCapabilityDecl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "connector",
    val role: ConnectorRole,
    @SerialName("interface")
    val iface: String,
    val version: Long,
    val methods: List<String>,
    val name: String,
    val icon: String? = null,
    val reason: String? = null,
    val egress: List<String>? = null, // hostnames and "exec:<binary>"
) : CapabilityDecl()

object CapabilityDeclSerializer : JsonContentPolymorphicSerializer<CapabilityDecl>(CapabilityDecl::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CapabilityDecl> =
        when (val type = element.jsonObject["type"]?.jsonPrimitive?.content) {
            "feed" -> FeedCapabilityDecl.serializer()
            "messaging" -> MessagingCapabilityDecl.serializer()
            "library" -> LibraryCapabilityDecl.serializer()
            "connector" -> ConnectorCapabilityDecl.serializer()
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
