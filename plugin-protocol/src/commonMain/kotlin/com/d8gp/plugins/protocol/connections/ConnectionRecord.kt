@file:OptIn(ExperimentalSerializationApi::class)

package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ConnectorCapabilityDecl
import com.d8gp.plugins.protocol.EXEC_EGRESS_PREFIX
import com.d8gp.plugins.protocol.PluginManifest
import com.d8gp.plugins.protocol.providesInterface
import com.d8gp.plugins.protocol.requiresInterface
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object ConnectionStates {
    const val ACTIVE = "active"
    const val NEEDS_RECONSENT = "needs-reconsent"
    const val REVOKED = "revoked"
}

object ProviderKinds {
    const val PLUGIN = "plugin"
    const val HOST = "host"
}

object ModelTiers {
    const val FAST = "fast"
    const val BALANCED = "balanced"
    const val BEST = "best"

    val ORDER: List<String> = listOf(FAST, BALANCED, BEST)

    // -1 for an unknown tier, so an unknown tier never outranks a known one.
    fun rank(tier: String): Int = ORDER.indexOf(tier)
}

// Every limit is required in a stored record. The defaults are the budget of
// design 4.1 for code that builds a record; BudgetSerializer never applies
// them to a record it reads.
@Serializable(with = BudgetSerializer::class)
data class Budget(
    val requestsPerMinute: Long = 20,
    val requestsPerDay: Long = 500,
    val maxTokensPerRequest: Long = 8192,
    val maxInputBytes: Long = 524_288,
    val tokensPerDay: Long = 2_000_000,
    val maxTier: String = ModelTiers.FAST,
    val maxCallMs: Long = MAX_CALL_MS,
    val maxConcurrent: Long = 1,
) {
    fun raises(approved: Budget): Boolean =
        requestsPerMinute > approved.requestsPerMinute ||
            requestsPerDay > approved.requestsPerDay ||
            maxTokensPerRequest > approved.maxTokensPerRequest ||
            maxInputBytes > approved.maxInputBytes ||
            tokensPerDay > approved.tokensPerDay ||
            ModelTiers.rank(maxTier) > ModelTiers.rank(approved.maxTier) ||
            maxCallMs > approved.maxCallMs ||
            maxConcurrent > approved.maxConcurrent
}

// A limit that is absent from the stored budget or is not a count is read as
// NOT_A_LIMIT, which admits nothing, and a tier that is not a tier as the
// empty text, which ranks below every tier. maxCallMs and maxConcurrent are
// not limits of the meter: they fall back to the hard cap and to one call.
object BudgetSerializer : KSerializer<Budget> {
    const val NOT_A_LIMIT = -1L

    @Serializable
    @SerialName("com.d8gp.plugins.protocol.connections.Budget")
    private class Fields(
        val requestsPerMinute: Long,
        val requestsPerDay: Long,
        val maxTokensPerRequest: Long,
        val maxInputBytes: Long,
        val tokensPerDay: Long,
        val maxTier: String,
        val maxCallMs: Long,
        val maxConcurrent: Long,
    )

    override val descriptor: SerialDescriptor = Fields.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Budget) = encoder.encodeSerializableValue(
        Fields.serializer(),
        Fields(
            requestsPerMinute = value.requestsPerMinute,
            requestsPerDay = value.requestsPerDay,
            maxTokensPerRequest = value.maxTokensPerRequest,
            maxInputBytes = value.maxInputBytes,
            tokensPerDay = value.tokensPerDay,
            maxTier = value.maxTier,
            maxCallMs = value.maxCallMs,
            maxConcurrent = value.maxConcurrent,
        ),
    )

    override fun deserialize(decoder: Decoder): Budget {
        val input = decoder as? JsonDecoder ?: throw SerializationException("a budget is read from JSON only")
        return read(input.decodeJsonElement())
    }

    fun read(budget: JsonElement?): Budget {
        val fields = budget as? JsonObject ?: JsonObject(emptyMap())
        fun limit(key: String): Long = fields[key].integerIn(0..MAX_SAFE_INTEGER) ?: NOT_A_LIMIT
        return Budget(
            requestsPerMinute = limit("requestsPerMinute"),
            requestsPerDay = limit("requestsPerDay"),
            maxTokensPerRequest = limit("maxTokensPerRequest"),
            maxInputBytes = limit("maxInputBytes"),
            tokensPerDay = limit("tokensPerDay"),
            maxTier = fields["maxTier"].stringOrNull()?.takeIf { it in ModelTiers.ORDER }.orEmpty(),
            maxCallMs = fields["maxCallMs"].safeIntegerOrNull() ?: MAX_CALL_MS,
            maxConcurrent = fields["maxConcurrent"].integerIn(1..MAX_SAFE_INTEGER) ?: 1,
        )
    }
}

// Bounds the SUM over every consumer of one provider (one key, one login).
@Serializable(with = ProviderCapSerializer::class)
data class ProviderCap(
    val requestsPerDay: Long,
    val tokensPerDay: Long,
)

object ProviderCapSerializer : KSerializer<ProviderCap> {
    @Serializable
    @SerialName("com.d8gp.plugins.protocol.connections.ProviderCap")
    private class Fields(val requestsPerDay: Long, val tokensPerDay: Long)

    override val descriptor: SerialDescriptor = Fields.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ProviderCap) =
        encoder.encodeSerializableValue(Fields.serializer(), Fields(value.requestsPerDay, value.tokensPerDay))

    override fun deserialize(decoder: Decoder): ProviderCap {
        val input = decoder as? JsonDecoder ?: throw SerializationException("a provider cap is read from JSON only")
        val fields = input.decodeJsonElement() as? JsonObject ?: JsonObject(emptyMap())
        fun limit(key: String): Long = fields[key].integerIn(0..MAX_SAFE_INTEGER) ?: BudgetSerializer.NOT_A_LIMIT
        return ProviderCap(requestsPerDay = limit("requestsPerDay"), tokensPerDay = limit("tokensPerDay"))
    }
}

@Serializable
data class ConnectionConsumer(
    val plugin: String,
    val version: String? = null,
    val sha256: String? = null,
    val authorKey: String? = null,
    val net: List<String> = emptyList(),
    val exec: List<String> = emptyList(),
    val auth: Boolean = false,
)

@Serializable
data class ConnectionProvider(
    val kind: String = ProviderKinds.PLUGIN,
    val plugin: String,
    val version: String? = null,
    val sha256: String? = null,
    val authorKey: String? = null,
    val net: List<String> = emptyList(),
    val exec: List<String> = emptyList(),
    val auth: Boolean = false,
    val egress: List<String> = emptyList(),
)

@Serializable
data class OnwardEgressSnapshot(
    val consumer: List<String> = emptyList(),
    val provider: List<String> = emptyList(),
)

@Serializable
data class ConnectionConsent(
    val textSha256: String,
    val by: String,
    val at: String,
    val via: String,
    val requestedFrom: String? = null,
)

// Same JSON shape as Transition in the server vault ledger.
@Serializable
data class ConnectionTransition(
    val at: String,
    val from: String,
    val to: String,
    val by: String,
)

// kotlinx reads the string "1" as the number 1 and refuses 1.0. A major that
// is not an integer of at least 1 is read as NOT_A_MAJOR, which no call and
// no declaration can equal.
object ConnectionMajorSerializer : KSerializer<Long> {
    const val NOT_A_MAJOR = -1L

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.d8gp.plugins.protocol.connections.ConnectionMajor", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val input = decoder as? JsonDecoder ?: throw SerializationException("a major is read from JSON only")
        return input.decodeJsonElement().integerIn(1..MAX_SAFE_INTEGER) ?: NOT_A_MAJOR
    }
}

@Serializable
data class ConnectionRecord(
    val id: String,
    val consumer: ConnectionConsumer,
    val provider: ConnectionProvider,
    @SerialName("interface")
    val iface: String,
    @Serializable(with = ConnectionMajorSerializer::class)
    val major: Long,
    val methods: List<String>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val scope: JsonObject = JsonObject(emptyMap()),
    val budget: Budget,
    val onwardEgress: OnwardEgressSnapshot = OnwardEgressSnapshot(),
    val fingerprint: String? = null,
    // Kept as text so a state this version does not know fails closed instead
    // of failing to decode.
    val state: String,
    val consent: ConnectionConsent? = null,
    val history: List<ConnectionTransition> = emptyList(),
)

private val FINGERPRINT_STRIPPED_KEYS = setOf("id", "state", "consent", "history", "fingerprint")
private val FINGERPRINT_PARTIES = setOf("consumer", "provider")
private const val PARTY_VERSION_KEY = "version"

fun connectionFingerprintInput(record: JsonObject): String {
    val covered = record
        .filterKeys { it !in FINGERPRINT_STRIPPED_KEYS }
        .mapValues { (key, value) ->
            if (key in FINGERPRINT_PARTIES && value is JsonObject) {
                JsonObject(value.filterKeys { it != PARTY_VERSION_KEY })
            } else {
                value
            }
        }
    return CanonicalJson.encode(JsonObject(covered))
}

// Covers everything the user approved, including fields this version does
// not know, so an unknown field can never ride along unapproved. That is why
// it takes the STORED JSON and has no form for the typed record, which holds
// only the fields this version knows.
fun connectionFingerprint(record: JsonObject): String =
    Sha256.hexOfUtf8(connectionFingerprintInput(record))

// A party as the host installed it. authorKey is the key the host VERIFIED at
// install (InstalledPlugin.authorPubkey), never the manifest's own claim.
data class InstalledParty(
    val manifest: PluginManifest,
    val authorKey: String? = null,
)

// Why an approved connection must be shown to the user again, or null when
// both installed parties and the graph still match what was approved.
// provider is null for a host provider, whose code ships with the host.
//
// It compares the record with the world around it and nothing else. The
// broker verifies record.fingerprint against the stored JSON and owns
// record.state; an edit of the record itself is recordEditReason.
fun reconsentReason(
    record: ConnectionRecord,
    consumer: InstalledParty,
    provider: InstalledParty?,
    graph: EgressGraph,
): String? =
    consumerChange(record, consumer)
        ?: providerChange(record, provider)
        ?: onwardEgressGrowth(record, graph)

fun reconsentReason(
    record: ConnectionRecord,
    consumerManifest: PluginManifest,
    providerManifest: PluginManifest?,
    graph: EgressGraph,
): String? = reconsentReason(record, InstalledParty(consumerManifest), providerManifest?.let(::InstalledParty), graph)

// Why replacing an approved record by an edited one needs the user, or null
// when the edit only narrows what was approved.
fun recordEditReason(approved: ConnectionRecord, edited: ConnectionRecord): String? = when {
    edited.consumer != approved.consumer -> "consumer changed"
    edited.provider != approved.provider -> "provider changed"
    edited.iface != approved.iface || edited.major != approved.major -> "interface changed"
    !approved.methods.containsAll(edited.methods) -> "methods were added"
    edited.scope != approved.scope -> "scope changed"
    edited.budget.raises(approved.budget) -> "budget was raised"
    grew(edited.onwardEgress.consumer, approved.onwardEgress.consumer) ||
        grew(edited.onwardEgress.provider, approved.onwardEgress.provider) -> "onward egress grew"
    else -> null
}

private fun consumerChange(record: ConnectionRecord, installed: InstalledParty): String? {
    val approved = record.consumer
    val manifest = installed.manifest
    val permissions = manifest.permissions
    return when {
        manifest.id != approved.plugin -> "consumer is not ${approved.plugin}"
        manifest.entry.sha256 != approved.sha256 -> "consumer code changed"
        installed.authorKey != approved.authorKey -> "consumer author changed"
        else -> declChange("consumer", record, requiresInterface(manifest, record.iface))
            ?: permissionChange(
                party = "consumer",
                net = permissions.net.orEmpty() to approved.net,
                exec = permissions.exec.orEmpty().map { it.binary } to approved.exec,
                auth = (permissions.auth == true) to approved.auth,
            )
    }
}

private fun providerChange(record: ConnectionRecord, installed: InstalledParty?): String? {
    val approved = record.provider
    if (approved.kind == ProviderKinds.HOST) {
        return if (installed == null) null else "provider is no longer a host provider"
    }
    if (installed == null) return "provider is not installed"
    val manifest = installed.manifest
    val permissions = manifest.permissions
    val decl = providesInterface(manifest, record.iface)
    return when {
        manifest.id != approved.plugin -> "provider is not ${approved.plugin}"
        manifest.entry.sha256 != approved.sha256 -> "provider code changed"
        installed.authorKey != approved.authorKey -> "provider author changed"
        else -> declChange("provider", record, decl)
            ?: permissionChange(
                party = "provider",
                net = permissions.net.orEmpty() to approved.net,
                exec = permissions.exec.orEmpty().map { it.binary } to approved.exec,
                auth = (permissions.auth == true) to approved.auth,
            )
            ?: "provider egress changed".takeIf { decl?.egress.orEmpty() != approved.egress }
    }
}

private fun declChange(party: String, record: ConnectionRecord, decl: ConnectorCapabilityDecl?): String? = when {
    decl == null -> "$party no longer declares ${record.iface}"
    decl.version != record.major -> "$party interface version changed"
    !decl.methods.containsAll(record.methods) -> "$party methods changed"
    else -> null
}

private fun permissionChange(
    party: String,
    net: Pair<List<String>, List<String>>,
    exec: Pair<List<String>, List<String>>,
    auth: Pair<Boolean, Boolean>,
): String? = when {
    net.first != net.second -> "$party network permission changed"
    exec.first != exec.second -> "$party program permission changed"
    auth.first != auth.second -> "$party vault permission changed"
    else -> null
}

// Only the side that RECEIVES the data can leak it further. An interface this
// version does not know is checked on both sides.
private fun onwardEgressGrowth(record: ConnectionRecord, graph: EgressGraph): String? {
    val dataFlow = InterfaceSpecs.find(record.iface, record.major)?.dataFlow
    val consumerGrew = grew(onwardEgress(record.consumer.plugin, graph), record.onwardEgress.consumer)
    val providerGrew = grew(onwardEgress(record.provider.plugin, graph), record.onwardEgress.provider)
    return when {
        dataFlow != DataFlow.RequestBearing && consumerGrew -> "consumer can now send data further"
        dataFlow != DataFlow.ResponseBearing && providerGrew -> "provider can now send data further"
        else -> null
    }
}

private fun grew(current: List<String>, approved: List<String>): Boolean = !approved.containsAll(current)

internal fun execEgress(binary: String): String = "$EXEC_EGRESS_PREFIX$binary"
