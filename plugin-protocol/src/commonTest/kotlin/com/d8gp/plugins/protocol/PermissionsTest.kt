package com.d8gp.plugins.protocol

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

// Port of tests/unit/pluginPermissions.test.ts.
class PermissionsTest {
    private val net = PluginPermissions(net = listOf("reddit.com", "redd.it"))

    @Test
    fun `allows the domain and its subdomains`() {
        assertTrue(hostAllowed("https://reddit.com/r/all.rss", net))
        assertTrue(hostAllowed("https://www.reddit.com/r/all.rss", net))
        assertTrue(hostAllowed("https://old.reddit.com/", net))
        assertTrue(hostAllowed("https://v.redd.it/abc/DASH_720.mp4", net))
        assertTrue(hostAllowed("http://reddit.com/", net))
    }

    @Test
    fun `rejects lookalike and parent-suffix tricks`() {
        assertFalse(hostAllowed("https://evil-reddit.com/", net))
        assertFalse(hostAllowed("https://reddit.com.evil.com/", net))
        assertFalse(hostAllowed("https://notreddit.com/", net))
        // Suffix match on the parsed hostname, never substring on the raw URL.
        assertFalse(hostAllowed("https://evil.com/reddit.com", net))
    }

    @Test
    fun `judges the host after userinfo not before it`() {
        assertFalse(hostAllowed("https://reddit.com@evil.com/", net))
        assertTrue(hostAllowed("https://evil.com@reddit.com/", net))
    }

    @Test
    fun `ignores reddit dot com after a fragment or query delimiter`() {
        assertFalse(hostAllowed("https://evil.com#@reddit.com/", net))
        assertFalse(hostAllowed("https://evil.com?@reddit.com/", net))
    }

    @Test
    fun `denies backslashes and control characters that parsers disagree on`() {
        assertFalse(hostAllowed("https://evil.com\\@reddit.com/", net))
        assertFalse(hostAllowed("https:\\\\evil.com\\@reddit.com", net))
        assertFalse(hostAllowed("https://evil.com\t@reddit.com/", net))
        assertFalse(hostAllowed("https://evil.com\n.reddit.com/", net))
    }

    @Test
    fun `rejects non-http schemes and unparseable URLs`() {
        assertFalse(hostAllowed("ftp://reddit.com/", net))
        assertFalse(hostAllowed("file:///etc/passwd", net))
        assertFalse(hostAllowed("not a url", net))
    }

    @Test
    fun `denies when no net permission is present`() {
        assertFalse(hostAllowed("https://reddit.com/", PluginPermissions()))
        assertFalse(hostAllowed("https://reddit.com/", PluginPermissions(net = emptyList())))
    }

    @Test
    fun `wildcard allows any host`() {
        assertTrue(hostAllowed("https://anything.example/", PluginPermissions(net = listOf("*"))))
    }

    @Test
    fun `tolerates a leading dot in the suffix`() {
        assertTrue(hostAllowed("https://v.redd.it/x", PluginPermissions(net = listOf(".redd.it"))))
    }

    @Test
    fun `matches case-insensitively`() {
        assertTrue(hostAllowed("https://WWW.Reddit.COM/r/all", net))
        assertTrue(hostAllowed("https://v.redd.it/x", PluginPermissions(net = listOf("REDD.IT"))))
    }

    @Test
    fun `reads coarse boolean permissions`() {
        assertTrue(hasPermission(PluginPermissions(media = true), "media"))
        assertFalse(hasPermission(PluginPermissions(media = true), "storage"))
        assertFalse(hasPermission(PluginPermissions(), "ui"))
        assertFalse(hasPermission(PluginPermissions(net = listOf("*")), "net")) // net is not a coarse key
    }

    @Test
    fun `contacts is a coarse permission and denied by default`() {
        assertTrue(hasPermission(PluginPermissions(contacts = true), "contacts"))
        assertFalse(hasPermission(PluginPermissions(contacts = false), "contacts"))
        assertFalse(hasPermission(PluginPermissions(), "contacts"))
    }
}
