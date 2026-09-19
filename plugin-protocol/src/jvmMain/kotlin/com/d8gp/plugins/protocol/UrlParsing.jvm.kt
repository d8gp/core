package com.d8gp.plugins.protocol

import java.net.URI
import java.net.URL

// URI is the primary parser; URL is the lenient fallback for strings WHATWG
// URL accepts but URI rejects (e.g. unencoded characters in the path).
internal actual fun parseSchemeAndHost(rawUrl: String): Pair<String, String>? {
    try {
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host
        if (host != null) return scheme to host.lowercase()
    } catch (_: Exception) {
    }
    return try {
        val url = URL(rawUrl)
        val host = url.host
        if (host.isNullOrEmpty()) null else url.protocol.lowercase() to host.lowercase()
    } catch (_: Exception) {
        null
    }
}

internal actual fun parseScheme(rawUrl: String): String? = try {
    URI(rawUrl).scheme
} catch (_: Exception) {
    null
}

internal actual fun parseStrictHost(rawUrl: String): String? = try {
    URI(rawUrl).host
} catch (_: Exception) {
    null
}
