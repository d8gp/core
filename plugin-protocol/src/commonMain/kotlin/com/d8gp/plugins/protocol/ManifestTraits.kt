package com.d8gp.plugins.protocol

// Sign-in is needed when the plugin uses the host auth vault or runs on the
// webview substrate (the site's own login/pairing page).
fun needsSignIn(manifest: PluginManifest): Boolean =
    manifest.permissions.auth == true || manifest.webview != null

fun providesFeed(manifest: PluginManifest): Boolean =
    manifest.capabilities.any { it is FeedCapabilityDecl }

fun providesInterface(manifest: PluginManifest, iface: String): ConnectorCapabilityDecl? =
    connectorFor(manifest, ConnectorRole.Provides, iface)

fun requiresInterface(manifest: PluginManifest, iface: String): ConnectorCapabilityDecl? =
    connectorFor(manifest, ConnectorRole.Requires, iface)

private fun connectorFor(manifest: PluginManifest, role: ConnectorRole, iface: String): ConnectorCapabilityDecl? =
    manifest.capabilities
        .filterIsInstance<ConnectorCapabilityDecl>()
        .firstOrNull { it.role == role && it.iface == iface }

// Why a manifest that parses may still not be installed as a connector, or
// null when it may.
fun connectorInstallRefusal(manifest: PluginManifest): String? {
    val provided = manifest.capabilities
        .filterIsInstance<ConnectorCapabilityDecl>()
        .filter { it.role == ConnectorRole.Provides }
    if (provided.isEmpty()) return null
    val permissions = manifest.permissions
    return when {
        permissions.net?.contains("*") == true -> "provides an interface with unrestricted network"
        provided.any { it.iface == "d8gp.llm" } && permissions.storage == true ->
            "provides d8gp.llm and asks for storage"
        provided.any { it.iface == "d8gp.task" } && !permissions.exec.isNullOrEmpty() ->
            "provides d8gp.task and asks to run programs"
        else -> null
    }
}

// Why a manifest published on D8gp Net may not be installed from there, or
// null when it may. Network installs get the sandboxed substrate and a
// host-bounded net allowlist only: a real WebView, the auth vault, the
// contacts bridge, net:["*"] and running programs stay reviewed privileges.
fun networkInstallRefusal(manifest: PluginManifest): String? = when {
    manifest.webview != null -> "runs on the WebView substrate"
    manifest.permissions.auth == true -> "asks for the auth vault"
    manifest.permissions.contacts == true -> "asks for contacts"
    manifest.permissions.net?.contains("*") == true -> "asks for unrestricted network"
    manifest.permissions.exec?.isNotEmpty() == true -> "asks to run programs on this device"
    else -> null
}
