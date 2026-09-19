package com.d8gp.plugins.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Port of plugin-host-core/manifest.ts. Hand-rolled validation (no schema
// lib) so error messages, check ordering, and first-error semantics match the
// TS validator exactly: throws ManifestException("manifest.<path>: <msg>") on
// the first problem.

class ManifestException(path: String, reason: String) : Exception("manifest.$path: $reason")

private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")
private val CAPABILITY_TYPES = listOf("feed", "messaging", "library")

fun parseManifest(json: String): PluginManifest {
    val root = try {
        ProtocolJson.parseToJsonElement(json)
    } catch (_: Exception) {
        throw ManifestException("", "must be a JSON object")
    }
    return parseManifest(root)
}

fun parseManifest(json: JsonElement): PluginManifest {
    val obj = json as? JsonObject ?: throw ManifestException("", "must be a JSON object")

    // JS `=== 1` accepts any JSON number equal to 1 (1, 1.0) and nothing else.
    val mv = (obj["manifestVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    if (mv != 1.0) throw ManifestException("manifestVersion", "must be 1")

    val id = reqString(obj, "id", "")
    if (!ID_RE.matches(id)) throw ManifestException("id", "must match ^[a-z0-9][a-z0-9._-]{2,63}\$")

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
    val description = optString(obj, "description", "")
    val entry = parseEntry(obj["entry"])
    val permissions = parsePermissions(obj["permissions"])
    val webview = parseWebView(obj["webview"])

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
    (el as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun numberOrNull(el: JsonElement?): Double? =
    (el as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

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

private fun isHttpUrl(url: String): Boolean {
    val scheme = parseScheme(url)?.lowercase()
    return scheme == "http" || scheme == "https"
}

private fun parseEntry(v: JsonElement?): PluginEntry {
    val obj = v as? JsonObject ?: throw ManifestException("entry", "required object")
    val url = reqString(obj, "url", "entry.")
    if (!isHttpUrl(url)) throw ManifestException("entry.url", "must be an http(s) URL")
    val sha256 = reqString(obj, "sha256", "entry.").lowercase()
    if (!SHA256_RE.matches(sha256)) throw ManifestException("entry.sha256", "must be 64-char hex")
    val sizeEl = obj["size"]
    var size: Long? = null
    if (sizeEl != null) {
        val n = numberOrNull(sizeEl)
        if (n == null || n < 0) throw ManifestException("entry.size", "must be a non-negative number")
        size = n.toLong()
    }
    return PluginEntry(url = url, sha256 = sha256, size = size)
}

private fun parseWebView(v: JsonElement?): WebViewSubstrate? {
    if (v == null) return null
    val obj = v as? JsonObject ?: throw ManifestException("webview", "must be an object")
    val url = reqString(obj, "url", "webview.")
    // Require a real host, not just an http(s) scheme: "https:///boot" would
    // otherwise pass and yield malformed WebView origin rules ("https://*.").
    val host = parseStrictHost(url)
    if (!isHttpUrl(url) || host.isNullOrBlank()) throw ManifestException("webview.url", "must be an http(s) URL")
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
        else -> LibraryCapabilityDecl(
            providerKey = reqString(obj, "providerKey", "$path."),
            name = reqString(obj, "name", "$path."),
            icon = reqString(obj, "icon", "$path."),
            origins = optStringArray(obj, "origins", "$path."),
        )
    }
}
