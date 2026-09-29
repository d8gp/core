package com.d8gp.plugins.protocol

import com.d8gp.plugins.protocol.connections.integerIn
import com.d8gp.plugins.protocol.connections.isBoolean
import com.d8gp.plugins.protocol.connections.isConsentSafe
import com.d8gp.plugins.protocol.connections.isConsentText
import com.d8gp.plugins.protocol.connections.MAX_SAFE_INTEGER
import com.d8gp.plugins.protocol.connections.numberOrNull
import com.d8gp.plugins.protocol.connections.StrictJson
import com.d8gp.plugins.protocol.connections.StrictJsonException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Port of plugin-host-core/manifest.ts. Hand-rolled validation (no schema
// lib) so error messages, check ordering, and first-error semantics match the
// TS validator exactly: throws ManifestException("manifest.<path>: <msg>") on
// the first problem.

class ManifestException(path: String, reason: String) : Exception("manifest.$path: $reason")

private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")
private val CAPABILITY_TYPES = listOf("feed", "messaging", "library", "connector")
private const val RESERVED_HOST_ID = "host"

val CONNECTOR_INTERFACE_RE = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){1,3}")
val CONNECTOR_METHOD_RE = Regex("[a-z][a-zA-Z0-9]{0,31}")
internal val EXEC_BINARY_RE = Regex("[a-z0-9][a-z0-9._-]{0,31}")
internal val EXEC_FLAG_RE = Regex("--[a-zA-Z][a-zA-Z0-9-]{0,62}")
internal val EXEC_SLOT_RE = Regex("[a-z][a-zA-Z0-9]{0,31}")
private val HOST_LABEL_RE = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

const val EXEC_EGRESS_PREFIX = "exec:"
private const val MAX_CONNECTOR_METHODS = 16
private const val MAX_CONNECTOR_NAME_UNITS = 64
private const val MAX_CONNECTOR_REASON_UNITS = 240
private const val MAX_EXEC_ENTRIES = 4
private const val MAX_EXEC_PURPOSE_UNITS = 200
private const val MAX_EXEC_ENUM_VALUES = 32
private const val MAX_EXEC_SLOT_BYTES = 131_072L
private const val MAX_EXEC_STDIN_BYTES = 1_048_576L
private const val MAX_HOSTNAME_LENGTH = 253

fun parseManifest(json: String): PluginManifest {
    val root = try {
        StrictJson.read(json)
    } catch (e: StrictJsonException) {
        throw ManifestException("", "must be JSON nested at most ${StrictJson.MAX_DEPTH} levels (${e.code})")
    }
    return parseManifest(root)
}

fun parseManifest(json: JsonElement): PluginManifest {
    val obj = json as? JsonObject ?: throw ManifestException("", "must be a JSON object")

    // JS `=== 1` accepts any JSON number equal to 1 (1, 1.0) and nothing else.
    val mv = obj["manifestVersion"].numberOrNull()
    if (mv != 1.0) throw ManifestException("manifestVersion", "must be 1")

    val id = reqString(obj, "id", "")
    if (!ID_RE.matches(id)) throw ManifestException("id", "must match ^[a-z0-9][a-z0-9._-]{2,63}\$")
    if (id == RESERVED_HOST_ID) throw ManifestException("id", "is reserved for the host")

    val version = reqString(obj, "version", "")
    if (!Semver.isSemver(version)) throw ManifestException("version", "must be semver x.y.z")

    val minAppVersion = optString(obj, "minAppVersion", "")
    if (minAppVersion != null && !Semver.isSemver(minAppVersion)) {
        throw ManifestException("minAppVersion", "must be semver x.y.z")
    }

    val capsRaw = obj["capabilities"] as? JsonArray
    if (capsRaw == null || capsRaw.isEmpty()) {
        throw ManifestException("capabilities", "must be a non-empty array")
    }
    val capabilities = capsRaw.mapIndexed { i, c -> parseCapability(c, i) }
    rejectDuplicateConnectors(capabilities)

    var author: PluginAuthor? = null
    val authorRaw = obj["author"]
    if (authorRaw != null) {
        val a = authorRaw as? JsonObject ?: throw ManifestException("author", "must be an object")
        author = PluginAuthor(
            name = optString(a, "name", "author."),
            npub = optString(a, "npub", "author."),
        )
    }

    // Remaining checks in the TS return-literal evaluation order:
    // name, description, entry, permissions.
    val name = reqString(obj, "name", "")
    if (!isConsentSafe(name)) throw ManifestException("name", CONSENT_TEXT_FLAW)
    val description = optString(obj, "description", "")
    val entry = parseEntry(obj["entry"])
    val permissions = parsePermissions(obj["permissions"])
    val webview = parseWebView(obj["webview"])
    requireEgressCovered(capabilities, permissions)

    return PluginManifest(
        manifestVersion = 1,
        id = id,
        name = name,
        version = version,
        description = description,
        author = author,
        entry = entry,
        minAppVersion = minAppVersion,
        capabilities = capabilities,
        permissions = permissions,
        webview = webview,
    )
}

// Non-throwing guard for places that only need a yes/no.
fun isValidManifest(json: String): Boolean = try {
    parseManifest(json)
    true
} catch (_: Exception) {
    false
}

fun isValidManifest(json: JsonElement): Boolean = try {
    parseManifest(json)
    true
} catch (_: Exception) {
    false
}

fun capabilityTypes(manifest: PluginManifest): List<String> =
    manifest.capabilities.map { it.type }.distinct()

private fun stringOrNull(el: JsonElement?): String? =
    (el as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun boolOrNull(el: JsonElement?): Boolean? =
    if (el.isBoolean()) (el as JsonPrimitive).content == "true" else null

private fun reqString(obj: JsonObject, key: String, path: String): String {
    val v = stringOrNull(obj[key])
    if (v.isNullOrEmpty()) throw ManifestException("$path$key", "required non-empty string")
    return v
}

private fun optString(obj: JsonObject, key: String, path: String): String? {
    val el = obj[key] ?: return null
    return stringOrNull(el) ?: throw ManifestException("$path$key", "must be a string")
}

private fun optStringArray(obj: JsonObject, key: String, path: String): List<String>? {
    val el = obj[key] ?: return null
    val arr = el as? JsonArray ?: throw ManifestException("$path$key", "must be an array of strings")
    return arr.map {
        stringOrNull(it) ?: throw ManifestException("$path$key", "must be an array of strings")
    }
}

private fun optBool(obj: JsonObject, key: String, path: String): Boolean? {
    val el = obj[key] ?: return null
    return boolOrNull(el) ?: throw ManifestException("$path$key", "must be a boolean")
}

private fun parseEntry(v: JsonElement?): PluginEntry {
    val obj = v as? JsonObject ?: throw ManifestException("entry", "required object")
    val url = reqString(obj, "url", "entry.")
    if (!isManifestUrl(url)) throw ManifestException("entry.url", "must be an http(s) URL")
    val sha256 = reqString(obj, "sha256", "entry.").lowercase()
    if (!SHA256_RE.matches(sha256)) throw ManifestException("entry.sha256", "must be 64-char hex")
    val sizeEl = obj["size"]
    var size: Long? = null
    if (sizeEl != null) {
        val n = sizeEl.numberOrNull()
        if (n == null || n < 0) throw ManifestException("entry.size", "must be a non-negative number")
        size = n.toLong()
    }
    return PluginEntry(url = url, sha256 = sha256, size = size)
}

private fun parseWebView(v: JsonElement?): WebViewSubstrate? {
    if (v == null) return null
    val obj = v as? JsonObject ?: throw ManifestException("webview", "must be an object")
    val url = reqString(obj, "url", "webview.")
    if (!isManifestUrl(url)) throw ManifestException("webview.url", "must be an http(s) URL")
    val origins = optStringArray(obj, "origins", "webview.")
    if (origins.isNullOrEmpty()) throw ManifestException("webview.origins", "must be a non-empty array of strings")
    return WebViewSubstrate(url = url, origins = origins)
}

private fun parsePermissions(v: JsonElement?): PluginPermissions {
    if (v == null) return PluginPermissions()
    val obj = v as? JsonObject ?: throw ManifestException("permissions", "must be an object")
    return PluginPermissions(
        net = optStringArray(obj, "net", "permissions."),
        storage = optBool(obj, "storage", "permissions."),
        media = optBool(obj, "media", "permissions."),
        saved = optBool(obj, "saved", "permissions."),
        ui = optBool(obj, "ui", "permissions."),
        auth = optBool(obj, "auth", "permissions."),
        contacts = optBool(obj, "contacts", "permissions."),
        exec = parseExec(obj["exec"]),
    )
}

private fun consentText(obj: JsonObject, key: String, path: String, maxUnits: Int): String {
    val text = stringOrNull(obj[key])
    if (text == null || !isConsentText(text, maxUnits)) {
        throw ManifestException(
            "$path$key",
            "required text of 1 to $maxUnits characters without control, invisible or line break characters",
        )
    }
    return text
}

private fun optInteger(obj: JsonObject, key: String, path: String, range: LongRange): Long? {
    val el = obj[key] ?: return null
    return el.integerIn(range) ?: throw ManifestException(
        "$path$key",
        if (range.last == MAX_SAFE_INTEGER) {
            "must be an integer of at least ${range.first}"
        } else {
            "must be an integer from ${range.first} to ${range.last}"
        },
    )
}

private fun parseExec(v: JsonElement?): List<ExecDecl>? {
    if (v == null) return null
    val path = "permissions.exec"
    val entries = v as? JsonArray
    if (entries == null || entries.size > MAX_EXEC_ENTRIES) {
        throw ManifestException(path, "must be an array of at most $MAX_EXEC_ENTRIES entries")
    }
    val binaries = mutableSetOf<String>()
    return entries.mapIndexed { i, entry -> parseExecEntry(entry, "$path[$i]", binaries) }
}

private fun parseExecEntry(v: JsonElement, path: String, binaries: MutableSet<String>): ExecDecl {
    val obj = v as? JsonObject ?: throw ManifestException(path, "required object")

    val binary = stringOrNull(obj["binary"])
    if (binary == null || !EXEC_BINARY_RE.matches(binary)) {
        throw ManifestException("$path.binary", "must be a program name matching ^[a-z0-9][a-z0-9._-]{0,31}\$")
    }
    if (!binaries.add(binary)) throw ManifestException("$path.binary", "is declared twice")

    val purpose = consentText(obj, "purpose", "$path.", MAX_EXEC_PURPOSE_UNITS)

    val argvRaw = obj["argv"] as? JsonArray ?: throw ManifestException("$path.argv", "required array")
    val slots = mutableSetOf<String>()
    val argv = argvRaw.mapIndexed { k, arg -> parseExecArg(arg, "$path.argv[$k]", slots) }

    var stdin: ExecStdin? = null
    val stdinRaw = obj["stdin"]
    if (stdinRaw != null) {
        val stdinObj = stdinRaw as? JsonObject ?: throw ManifestException("$path.stdin", "must be an object")
        val maxBytes = stdinObj["maxBytes"].integerIn(1..MAX_EXEC_STDIN_BYTES)
            ?: throw ManifestException("$path.stdin.maxBytes", "required integer from 1 to $MAX_EXEC_STDIN_BYTES")
        stdin = ExecStdin(maxBytes = maxBytes.toInt())
    }

    val timeoutMs = optInteger(obj, "timeoutMs", "$path.", 1..MAX_SAFE_INTEGER)
    val maxStdoutBytes = optInteger(obj, "maxStdoutBytes", "$path.", 1..MAX_SAFE_INTEGER)
    if ("env" in obj) throw ManifestException("$path.env", "is not supported")

    return ExecDecl(
        binary = binary,
        purpose = purpose,
        argv = argv,
        stdin = stdin,
        timeoutMs = timeoutMs,
        maxStdoutBytes = maxStdoutBytes,
    )
}

private fun parseExecArg(v: JsonElement, path: String, slots: MutableSet<String>): ExecArg {
    val literal = stringOrNull(v)
    if (literal != null) {
        if (!isExecLiteral(literal)) throw ManifestException(path, CONSENT_TEXT_FLAW)
        return ExecArg.Literal(literal)
    }
    val obj = v as? JsonObject ?: throw ManifestException(path, "must be a string or a slot object")

    val flag = stringOrNull(obj["flag"])
    if (flag == null || !EXEC_FLAG_RE.matches(flag)) {
        throw ManifestException("$path.flag", "must match ^--[a-zA-Z][a-zA-Z0-9-]{0,62}\$")
    }
    val slot = stringOrNull(obj["slot"])
    if (slot == null || !EXEC_SLOT_RE.matches(slot)) {
        throw ManifestException("$path.slot", "must match ^[a-z][a-zA-Z0-9]{0,31}\$")
    }
    if (!slots.add(slot)) throw ManifestException("$path.slot", "is declared twice")

    var enum: List<String>? = null
    val enumRaw = obj["enum"]
    if (enumRaw != null) {
        val values = (enumRaw as? JsonArray)?.map(::stringOrNull)
        if (
            values == null || values.isEmpty() || values.size > MAX_EXEC_ENUM_VALUES ||
            values.any { it == null || !isExecEnumValue(it) }
        ) {
            throw ManifestException(
                "$path.enum",
                "must be an array of 1 to $MAX_EXEC_ENUM_VALUES non-empty strings without control, " +
                    "invisible or line break characters",
            )
        }
        enum = values.filterNotNull()
    }
    val maxBytes = optInteger(obj, "maxBytes", "$path.", 1..MAX_EXEC_SLOT_BYTES)

    return ExecArg.Slot(flag = flag, slot = slot, enum = enum, maxBytes = maxBytes?.toInt())
}

// A last label of digits only is refused, which keeps IPv4 literals out.
fun isEgressHostname(host: String): Boolean {
    if (host.length > MAX_HOSTNAME_LENGTH) return false
    val labels = host.split('.')
    return labels.all(HOST_LABEL_RE::matches) && !labels.last().all { it in '0'..'9' }
}

private fun isEgressEntry(entry: String): Boolean =
    if (entry.startsWith(EXEC_EGRESS_PREFIX)) {
        EXEC_BINARY_RE.matches(entry.removePrefix(EXEC_EGRESS_PREFIX))
    } else {
        isEgressHostname(entry)
    }

private fun egressCovered(entry: String, permissions: PluginPermissions): Boolean {
    if (entry.startsWith(EXEC_EGRESS_PREFIX)) {
        val binary = entry.removePrefix(EXEC_EGRESS_PREFIX)
        return permissions.exec.orEmpty().any { it.binary == binary }
    }
    val allow = permissions.net.orEmpty()
    if ("*" in allow) return true
    return allow.any { suffix ->
        val s = suffix.lowercase().trimStart('.')
        '.' in s && (entry == s || entry.endsWith(".$s"))
    }
}

private fun requireEgressCovered(capabilities: List<CapabilityDecl>, permissions: PluginPermissions) {
    capabilities.forEachIndexed { i, capability ->
        val egress = (capability as? ConnectorCapabilityDecl)?.egress ?: return@forEachIndexed
        egress.forEachIndexed { k, entry ->
            if (!egressCovered(entry, permissions)) {
                throw ManifestException(
                    "capabilities[$i].egress[$k]",
                    "is not covered by permissions.net or permissions.exec",
                )
            }
        }
    }
}

private fun rejectDuplicateConnectors(capabilities: List<CapabilityDecl>) {
    val seen = mutableSetOf<Pair<ConnectorRole, String>>()
    capabilities.forEachIndexed { i, capability ->
        if (capability !is ConnectorCapabilityDecl) return@forEachIndexed
        if (!seen.add(capability.role to capability.iface)) {
            throw ManifestException(
                "capabilities[$i].interface",
                "is already declared with the same role",
            )
        }
    }
}

private fun parseConnector(obj: JsonObject, path: String): ConnectorCapabilityDecl {
    val role = when (stringOrNull(obj["role"])) {
        "provides" -> ConnectorRole.Provides
        "requires" -> ConnectorRole.Requires
        else -> throw ManifestException("$path.role", "must be one of provides, requires")
    }

    val iface = stringOrNull(obj["interface"])
    if (iface == null || !CONNECTOR_INTERFACE_RE.matches(iface)) {
        throw ManifestException(
            "$path.interface",
            "must match ^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){1,3}\$",
        )
    }

    val version = obj["version"].integerIn(1..MAX_SAFE_INTEGER)
        ?: throw ManifestException("$path.version", "must be an integer of at least 1")

    val methodsRaw = obj["methods"] as? JsonArray
    if (methodsRaw == null || methodsRaw.isEmpty() || methodsRaw.size > MAX_CONNECTOR_METHODS) {
        throw ManifestException("$path.methods", "must be an array of 1 to $MAX_CONNECTOR_METHODS method names")
    }
    val methods = methodsRaw.mapIndexed { k, method ->
        stringOrNull(method)?.takeIf(CONNECTOR_METHOD_RE::matches)
            ?: throw ManifestException("$path.methods[$k]", "must match ^[a-z][a-zA-Z0-9]{0,31}\$")
    }

    val name = consentText(obj, "name", "$path.", MAX_CONNECTOR_NAME_UNITS)
    val icon = optString(obj, "icon", "$path.")

    val reason = when (role) {
        ConnectorRole.Requires -> consentText(obj, "reason", "$path.", MAX_CONNECTOR_REASON_UNITS)
        ConnectorRole.Provides -> {
            if ("reason" in obj) throw ManifestException("$path.reason", "is only allowed on a requires connector")
            null
        }
    }

    val egress = when (role) {
        ConnectorRole.Provides -> {
            val entries = obj["egress"] as? JsonArray
                ?: throw ManifestException("$path.egress", "required array, may be empty")
            entries.mapIndexed { k, entry ->
                stringOrNull(entry)?.takeIf(::isEgressEntry)
                    ?: throw ManifestException("$path.egress[$k]", "must be a hostname or exec:<binary>")
            }
        }
        ConnectorRole.Requires -> {
            if ("egress" in obj) throw ManifestException("$path.egress", "is only allowed on a provides connector")
            null
        }
    }

    return ConnectorCapabilityDecl(
        role = role,
        iface = iface,
        version = version,
        methods = methods,
        name = name,
        icon = icon,
        reason = reason,
        egress = egress,
    )
}

private fun parseMessagingCaps(v: JsonElement?, path: String): MessagingCapabilitiesDecl {
    val obj = v as? JsonObject ?: throw ManifestException(path, "required object")
    fun bool(k: String): Boolean =
        boolOrNull(obj[k]) ?: throw ManifestException("$path.$k", "required boolean")
    val att = obj["attachments"] as? JsonObject
        ?: throw ManifestException("$path.attachments", "required object")
    fun attBool(k: String): Boolean =
        boolOrNull(att[k]) ?: throw ManifestException("$path.attachments.$k", "required boolean")
    return MessagingCapabilitiesDecl(
        canSend = bool("canSend"),
        groupMessaging = bool("groupMessaging"),
        attachments = MessagingAttachmentsDecl(
            image = attBool("image"),
            video = attBool("video"),
            audio = attBool("audio"),
            file = attBool("file"),
        ),
        deleteMessage = bool("deleteMessage"),
        deleteConversation = bool("deleteConversation"),
        markRead = bool("markRead"),
        resend = bool("resend"),
        contactSearch = bool("contactSearch"),
    )
}

private fun parseCapability(v: JsonElement, i: Int): CapabilityDecl {
    val path = "capabilities[$i]"
    val obj = v as? JsonObject ?: throw ManifestException(path, "required object")
    val type = stringOrNull(obj["type"])
    if (type == null || type !in CAPABILITY_TYPES) {
        throw ManifestException("$path.type", "must be one of ${CAPABILITY_TYPES.joinToString(", ")}")
    }
    return when (type) {
        "feed" -> FeedCapabilityDecl(
            sourceKey = reqString(obj, "sourceKey", "$path."),
            name = reqString(obj, "name", "$path."),
            icon = reqString(obj, "icon", "$path."),
            color = reqString(obj, "color", "$path."),
        )
        "messaging" -> MessagingCapabilityDecl(
            protocol = reqString(obj, "protocol", "$path."),
            displayName = reqString(obj, "displayName", "$path."),
            icon = reqString(obj, "icon", "$path."),
            color = optString(obj, "color", "$path."),
            capabilities = parseMessagingCaps(obj["capabilities"], "$path.capabilities"),
        )
        "library" -> LibraryCapabilityDecl(
            providerKey = reqString(obj, "providerKey", "$path."),
            name = reqString(obj, "name", "$path."),
            icon = reqString(obj, "icon", "$path."),
            origins = optStringArray(obj, "origins", "$path."),
        )
        "connector" -> parseConnector(obj, path)
        else -> throw ManifestException("$path.type", "unhandled type $type")
    }
}
