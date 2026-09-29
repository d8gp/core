package com.d8gp.plugins.protocol

import com.d8gp.plugins.protocol.connections.interfaces.LlmInterface
import com.d8gp.plugins.protocol.connections.interfaces.TaskInterface

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

private const val WILDCARD_NET = "*"

// `code` is the contract shared with the TypeScript twin. `sentence` is what
// this host shows and may change freely.
enum class ConnectorInstallRefusal(val code: String, val sentence: String) {
    LlmStorage("llm-storage", "provides d8gp.llm and asks for storage"),
    WildcardNet("wildcard-net", "provides an interface with unrestricted network"),
    TaskExec("task-exec", "provides d8gp.task and asks to run programs"),
}

enum class NetworkInstallRefusal(val code: String, val sentence: String) {
    WebView("webview", "runs on the WebView substrate"),
    Auth("auth", "asks for the auth vault"),
    Contacts("contacts", "asks for contacts"),
    WildcardNet("wildcard-net", "asks for unrestricted network"),
    Exec("exec", "asks to run programs on this device"),
}

// The first rule that keeps a manifest that parses from being installed as a
// connector provider, or null when it may be.
fun connectorInstallRefusal(manifest: PluginManifest): ConnectorInstallRefusal? {
    val provided = manifest.capabilities
        .filterIsInstance<ConnectorCapabilityDecl>()
        .filter { it.role == ConnectorRole.Provides }
        .map { it.iface }
    if (provided.isEmpty()) return null
    val permissions = manifest.permissions
    return when {
        LlmInterface.ID in provided && permissions.storage == true -> ConnectorInstallRefusal.LlmStorage
        permissions.net.orEmpty().contains(WILDCARD_NET) -> ConnectorInstallRefusal.WildcardNet
        TaskInterface.ID in provided && !permissions.exec.isNullOrEmpty() -> ConnectorInstallRefusal.TaskExec
        else -> null
    }
}

// The first rule that keeps a manifest published on D8gp Net from being
// installed from there, or null when it may be. Network installs get the
// sandboxed substrate and a host-bounded net allowlist only: a real WebView,
// the auth vault, the contacts bridge, net:["*"] and running programs stay
// reviewed privileges.
fun networkInstallRefusal(manifest: PluginManifest): NetworkInstallRefusal? = when {
    manifest.webview != null -> NetworkInstallRefusal.WebView
    manifest.permissions.auth == true -> NetworkInstallRefusal.Auth
    manifest.permissions.contacts == true -> NetworkInstallRefusal.Contacts
    manifest.permissions.net.orEmpty().contains(WILDCARD_NET) -> NetworkInstallRefusal.WildcardNet
    !manifest.permissions.exec.isNullOrEmpty() -> NetworkInstallRefusal.Exec
    else -> null
}
