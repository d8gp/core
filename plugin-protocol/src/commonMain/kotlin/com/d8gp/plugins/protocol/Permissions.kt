package com.d8gp.plugins.protocol

// The real network control: the host checks this before every http.fetch /
// http.download. Suffix-match on the parsed hostname — NEVER a substring test
// on the raw URL, which 'evil-reddit.com' or '.../reddit.com' would defeat.
//
// Backslashes and control characters are denied before parsing: URL parsers
// disagree about them (WHATWG reads "\" as "/", Foundation percent-encodes it,
// java.net rejects it), and the host this check sees must be the host the
// platform then connects to.
//
// Honest limit (same as the RN host): an allowlisted host can still receive
// whatever the plugin sends it, and cross-host redirects are not re-checked.
// This bounds *who* a plugin can talk to, not what it says.
fun hostAllowed(rawUrl: String, permissions: PluginPermissions): Boolean {
    val allow = permissions.net
    if (allow.isNullOrEmpty()) return false
    if ("*" in allow) return true

    if (rawUrl.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) return false
    val (scheme, host) = parseSchemeAndHost(rawUrl) ?: return false
    if (scheme != "http" && scheme != "https") return false

    return allow.any { suffix ->
        val s = suffix.lowercase().trimStart('.')
        host == s || host.endsWith(".$s")
    }
}

// Whether a plugin holds a coarse (boolean) permission. `net` is handled by
// hostAllowed, not here. Unknown keys are denied.
fun hasPermission(permissions: PluginPermissions, key: String): Boolean = when (key) {
    "storage" -> permissions.storage == true
    "media" -> permissions.media == true
    "saved" -> permissions.saved == true
    "ui" -> permissions.ui == true
    "auth" -> permissions.auth == true
    "contacts" -> permissions.contacts == true
    else -> false
}

