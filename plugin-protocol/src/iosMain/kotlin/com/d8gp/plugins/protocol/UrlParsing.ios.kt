package com.d8gp.plugins.protocol

import platform.Foundation.NSURL

// NSURL is what URLSession connects with. Its host omits IPv6 brackets, unlike
// java.net; allowlists hold domain suffixes, so that difference never matches.
internal actual fun parseSchemeAndHost(rawUrl: String): Pair<String, String>? {
    val url = NSURL.URLWithString(rawUrl) ?: return null
    val scheme = url.scheme?.lowercase() ?: return null
    val host = url.host
    return if (host.isNullOrEmpty()) null else scheme to host.lowercase()
}

internal actual fun parseScheme(rawUrl: String): String? = NSURL.URLWithString(rawUrl)?.scheme

internal actual fun parseStrictHost(rawUrl: String): String? = NSURL.URLWithString(rawUrl)?.host
