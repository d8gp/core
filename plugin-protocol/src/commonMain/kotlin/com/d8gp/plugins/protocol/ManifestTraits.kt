package com.d8gp.plugins.protocol

// Sign-in is needed when the plugin uses the host auth vault or runs on the
// webview substrate (the site's own login/pairing page).
fun needsSignIn(manifest: PluginManifest): Boolean =
    manifest.permissions.auth == true || manifest.webview != null

fun providesFeed(manifest: PluginManifest): Boolean =
    manifest.capabilities.any { it is FeedCapabilityDecl }
