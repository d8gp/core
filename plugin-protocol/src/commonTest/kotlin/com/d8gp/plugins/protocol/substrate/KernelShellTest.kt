package com.d8gp.plugins.protocol.substrate

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ported from d8gp-app-android plugin-host ShellTest. The HTML parser ends a
// <script> element at the FIRST close-tag sequence anywhere in its text — even
// inside a JS string or comment — which would truncate the kernel and silently
// kill the plugin host (this happened on-device in the RN app: a code comment
// mentioning the tag broke boot). Assembled from halves so this file never
// contains the sequence.
class KernelShellTest {
    private val closeTag = "</scr" + "ipt>"

    @Test
    fun shell_contains_exactly_one_script_close_tag_at_the_document_tail() {
        val parts = SHELL_HTML.split(closeTag)
        assertEquals(2, parts.size)
        assertEquals("</body></html>", parts[1])
    }

    @Test
    fun kernel_stays_inside_a_single_inline_script() {
        // If the kernel leaked out of the script element, its srcdoc CSP meta
        // tag would become a real element; it must stay inside the script text.
        val scriptBody = SHELL_HTML.substring(
            SHELL_HTML.indexOf("<script>") + "<script>".length,
            SHELL_HTML.indexOf(closeTag),
        )
        assertTrue(scriptBody.contains("window.__kernel"))
        assertTrue(scriptBody.contains("__d8gpBridge"))
    }

    @Test
    fun kernel_and_bridge_sources_contain_no_close_tag_sequence() {
        assertFalse(KERNEL.contains(closeTag))
        assertFalse(IFRAME_BRIDGE.contains(closeTag))
    }

    @Test
    fun kernel_escapes_manifest_json_and_srcdoc_script_close() {
        // Every '<' in the manifest JSON becomes the six chars backslash-u003c
        // (JSON semantics unchanged) before srcdoc interpolation.
        assertTrue(KERNEL.contains("""replace(/</g, '\\u003c')"""))
        // The srcdoc's own script close is written with an escaped slash.
        assertTrue(KERNEL.contains("'<\\/script>"))
    }

    @Test
    fun kernel_uses_a_transport_neutral_host_global() {
        assertTrue(KERNEL.contains("window.__d8gpHost || window.ReactNativeWebView"))
    }

    @Test
    fun srcdoc_sandbox_is_allow_scripts_only() {
        assertTrue(KERNEL.contains("setAttribute('sandbox', 'allow-scripts')"))
        assertFalse(KERNEL.contains("allow-same-origin"))
    }

    @Test
    fun shell_csp_denies_everything_by_default() {
        assertTrue(
            SHELL_HTML.contains(
                "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; frame-src 'self'",
            ),
        )
    }

    @Test
    fun bridge_is_embedded_as_a_string_literal_that_round_trips() {
        val quoted = jsStringLiteral(IFRAME_BRIDGE)
        assertTrue(KERNEL.contains(quoted))
        assertEquals(IFRAME_BRIDGE, Json.decodeFromString(String.serializer(), quoted))
    }

    @Test
    fun site_bridge_embeds_the_manifest_and_pins_itself() {
        val script = webViewBridgeScript("""{"id":"plugin-x"}""")
        assertTrue(script.contains("""var MANIFEST = {"id":"plugin-x"};"""))
        assertTrue(script.contains("Object.defineProperty(window, '__d8gp'"))
        // The site bridge must never install a global crash reporter: the page's
        // own scripts throw routinely and would crash-loop the plugin.
        assertFalse(script.contains("unhandledrejection"))
    }
}
