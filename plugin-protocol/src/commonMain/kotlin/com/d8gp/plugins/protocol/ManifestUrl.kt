package com.d8gp.plugins.protocol

// The grammar of entry.url and webview.url. It is a plain text rule shared
// with the TypeScript validator: no platform URL parser takes part, because
// they disagree about what a host is.

private const val MAX_URL_HOST_LENGTH = 253
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5
private const val IPV4_PARTS = 4
private const val FORBIDDEN_URL_CHARACTERS = "\"<>\\^`{|}[]"
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
private const val SCHEME_END = "://"

private val URL_SCHEMES = setOf("http", "https")
private val URL_LABEL_RE = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private val IPV4_PART_RE = Regex("0|[1-9][0-9]{0,2}")

internal fun isManifestUrl(url: String): Boolean {
    if (!hasOnlyUrlCharacters(url)) return false

    val schemeEnd = url.indexOf(SCHEME_END)
    if (schemeEnd < 0 || url.substring(0, schemeEnd).lowercase() !in URL_SCHEMES) return false

    val afterScheme = url.substring(schemeEnd + SCHEME_END.length)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in authority) return false

    val host = authority.substringBefore(':')
    if (!isUrlHost(host)) return false
    return authority.length == host.length || isUrlPort(authority.substring(host.length + 1))
}

private fun hasOnlyUrlCharacters(url: String): Boolean {
    if (url.any { it.code !in 0x21..0x7e || it in FORBIDDEN_URL_CHARACTERS }) return false
    if (url.count { it == '#' } > 1) return false
    return url.indices.none { i ->
        url[i] == '%' && !(i + 2 < url.length && url[i + 1] in HEX_DIGITS && url[i + 2] in HEX_DIGITS)
    }
}

private fun isUrlHost(host: String): Boolean {
    if (host.length > MAX_URL_HOST_LENGTH) return false
    val labels = host.split('.')
    if (!labels.all(URL_LABEL_RE::matches)) return false
    return labels.last()[0].isAsciiLetter() || isStrictIpv4(labels)
}

private fun isStrictIpv4(parts: List<String>): Boolean =
    parts.size == IPV4_PARTS && parts.all { IPV4_PART_RE.matches(it) && it.toInt() <= 255 }

private fun isUrlPort(port: String): Boolean =
    port.length in 1..MAX_PORT_DIGITS && port.all { it in '0'..'9' } && port.toInt() <= MAX_PORT

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
