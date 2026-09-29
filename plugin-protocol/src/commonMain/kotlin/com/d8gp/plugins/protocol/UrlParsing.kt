package com.d8gp.plugins.protocol

// Each platform parses with the same URL parser its network stack connects
// with, so hostAllowed judges the host that is actually contacted.
internal expect fun parseSchemeAndHost(rawUrl: String): Pair<String, String>?
