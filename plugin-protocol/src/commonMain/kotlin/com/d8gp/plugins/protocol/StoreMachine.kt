package com.d8gp.plugins.protocol

// Pure reducers over a plugin's crash state — the kill switch. A plugin that
// strikes out (RPC timeout + dead watchdog, an uncaught error in its bundle,
// or an eval throw) is auto-disabled; a successful call clears the count.
object StoreMachine {
    const val MAX_CRASH_STRIKES = 3

    fun recordCrash(plugin: InstalledPlugin): InstalledPlugin {
        val crashCount = plugin.crashCount + 1
        if (crashCount >= MAX_CRASH_STRIKES) {
            return plugin.copy(
                crashCount = crashCount,
                enabled = false,
                disabledReason = PluginDisabledReason.CrashLoop,
            )
        }
        return plugin.copy(crashCount = crashCount)
    }

    fun recordSuccess(plugin: InstalledPlugin): InstalledPlugin =
        if (plugin.crashCount == 0) plugin else plugin.copy(crashCount = 0)

    // User (or install flow) re-enables a crash-looped plugin: clear the strike
    // count and the disabled reason so it gets another chance.
    fun reenable(plugin: InstalledPlugin): InstalledPlugin =
        plugin.copy(enabled = true, crashCount = 0, disabledReason = null)

    // A plugin is live only when enabled and not tripped.
    fun isActive(plugin: InstalledPlugin): Boolean =
        plugin.enabled && plugin.disabledReason != PluginDisabledReason.CrashLoop
}
