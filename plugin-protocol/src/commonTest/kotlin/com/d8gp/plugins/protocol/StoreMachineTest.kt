package com.d8gp.plugins.protocol

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

// Port of tests/unit/pluginStoreMachine.test.ts.
class StoreMachineTest {
    private fun plugin(
        enabled: Boolean = true,
        crashCount: Int = 0,
        disabledReason: PluginDisabledReason? = null,
    ): InstalledPlugin = InstalledPlugin(
        id = "feed-reddit",
        manifest = PluginManifest(
            id = "feed-reddit",
            name = "Reddit",
            version = "1.0.0",
            entry = PluginEntry(url = "https://x/y.js", sha256 = "a".repeat(64)),
            capabilities = listOf(
                FeedCapabilityDecl(sourceKey = "reddit", name = "Reddit", icon = "logo-reddit", color = "#f40"),
            ),
        ),
        runtime = PluginRuntimeKind.Sandboxed,
        origin = PluginOrigin.Builtin,
        enabled = enabled,
        installedAt = 0,
        updatedAt = 0,
        crashCount = crashCount,
        disabledReason = disabledReason,
    )

    @Test
    fun `auto-disables after MAX_CRASH_STRIKES consecutive crashes`() {
        var p = plugin()
        for (i in 1 until StoreMachine.MAX_CRASH_STRIKES) {
            p = StoreMachine.recordCrash(p)
            assertTrue(p.enabled)
        }
        p = StoreMachine.recordCrash(p)
        assertEquals(StoreMachine.MAX_CRASH_STRIKES, p.crashCount)
        assertFalse(p.enabled)
        assertEquals(PluginDisabledReason.CrashLoop, p.disabledReason)
        assertFalse(StoreMachine.isActive(p))
    }

    @Test
    fun `a success resets the strike count`() {
        var p = StoreMachine.recordCrash(StoreMachine.recordCrash(plugin()))
        assertEquals(2, p.crashCount)
        p = StoreMachine.recordSuccess(p)
        assertEquals(0, p.crashCount)
    }

    @Test
    fun `success does not clear a crash-loop disable - only reenable does`() {
        val dead = plugin(enabled = false, crashCount = 3, disabledReason = PluginDisabledReason.CrashLoop)
        val afterSuccess = StoreMachine.recordSuccess(dead)
        assertEquals(PluginDisabledReason.CrashLoop, afterSuccess.disabledReason)
        assertFalse(StoreMachine.isActive(afterSuccess))
    }

    @Test
    fun `reenable clears the crash-loop disable`() {
        val dead = plugin(enabled = false, crashCount = 3, disabledReason = PluginDisabledReason.CrashLoop)
        val revived = StoreMachine.reenable(dead)
        assertTrue(revived.enabled)
        assertEquals(0, revived.crashCount)
        assertNull(revived.disabledReason)
        assertTrue(StoreMachine.isActive(revived))
    }

    @Test
    fun `a stale crash-loop reason keeps a row inactive even when enabled`() {
        val stale = plugin(enabled = true, disabledReason = PluginDisabledReason.CrashLoop)
        assertFalse(StoreMachine.isActive(stale))
        val userDisabled = plugin(enabled = false, disabledReason = PluginDisabledReason.User)
        assertFalse(StoreMachine.isActive(userDisabled))
        val userReasonButEnabled = plugin(enabled = true, disabledReason = PluginDisabledReason.User)
        assertTrue(StoreMachine.isActive(userReasonButEnabled))
    }
}
