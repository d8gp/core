package com.d8gp.core.remote

import kotlinx.serialization.Serializable

/**
 * What the pairing QR carries. The remote learns where the server is, which
 * certificate to trust, and a one-time token proving it saw the screen.
 *
 * The fingerprint is the pinning: on a LAN there is no CA to ask, so the phone
 * trusts exactly the certificate whose SHA-256 the QR showed, and nothing else.
 */
@Serializable
data class PairingOffer(
    val host: String,
    val port: Int,
    /** Lowercase hex SHA-256 of the server certificate's DER encoding. */
    val certSha256: String,
    val token: String,
    val serverName: String,
) {
    fun toUri(): String = buildString {
        append(SCHEME)
        append("://pair?h=").append(host)
        append("&p=").append(port)
        append("&f=").append(certSha256)
        append("&t=").append(token)
        append("&n=").append(encode(serverName))
    }

    companion object {
        const val SCHEME = "d8gp"

        fun parse(uri: String): PairingOffer? {
            if (!uri.startsWith("$SCHEME://pair?")) return null
            val params = uri.substringAfter('?').split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key.isEmpty()) null else key to decode(value)
            }.toMap()
            val host = params["h"] ?: return null
            val port = params["p"]?.toIntOrNull() ?: return null
            val fingerprint = params["f"]?.lowercase() ?: return null
            val token = params["t"] ?: return null
            if (host.isBlank() || token.isBlank()) return null
            if (fingerprint.length != 64 || fingerprint.any { it !in "0123456789abcdef" }) return null
            return PairingOffer(host, port, fingerprint, token, params["n"] ?: "d8gp server")
        }

        // Only what a QR payload needs; the fields are ours, not user text,
        // apart from the server name.
        private fun encode(value: String): String = value.replace("%", "%25").replace("&", "%26").replace(" ", "%20")

        private fun decode(value: String): String = value.replace("%20", " ").replace("%26", "&").replace("%25", "%")
    }
}
