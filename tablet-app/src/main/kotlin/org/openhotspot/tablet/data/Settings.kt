package org.openhotspot.tablet.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class JoinMethod {
    /** WifiNetworkSpecifier: immediate, system prompt, usable by THIS app only. */
    SPECIFIER,
    /** WifiNetworkSuggestion: the system joins when it decides to; the connection is used by all apps. */
    SUGGESTION,
}

data class AppSettings(
    val joinMethod: JoinMethod = JoinMethod.SPECIFIER,
    /** Treat the hotspot as hidden (directed probe). The protocol has no hidden flag (PROTOCOL.md R9). */
    val hiddenSsid: Boolean = false,
    /** Watch for "no internet and a paired phone nearby" and connect by itself. Off by default. */
    val autoEnabled: Boolean = false,
    /** Minimum time between automatic attempts after one was started. */
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_S,
) {
    companion object {
        const val DEFAULT_COOLDOWN_S = 300
        val COOLDOWN_CHOICES_S = listOf(60, 300, 900, 1800, 3600)
    }
}

/** Observable settings. Not secret, so plain SharedPreferences. */
interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun update(change: (AppSettings) -> AppSettings)
}

class InMemorySettings(initial: AppSettings = AppSettings()) : SettingsStore {
    private val flow = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> get() = flow
    override fun update(change: (AppSettings) -> AppSettings) { flow.value = change(flow.value) }
}

class PrefsSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(read())
    override val settings: StateFlow<AppSettings> get() = flow

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            joinMethod = runCatching { JoinMethod.valueOf(prefs.getString("join", null) ?: "") }.getOrDefault(d.joinMethod),
            hiddenSsid = prefs.getBoolean("hidden", d.hiddenSsid),
            autoEnabled = prefs.getBoolean("auto", d.autoEnabled),
            cooldownSeconds = prefs.getInt("cooldown", d.cooldownSeconds).coerceIn(30, 24 * 3600),
        )
    }

    @Synchronized
    override fun update(change: (AppSettings) -> AppSettings) {
        val s = change(flow.value)
        prefs.edit()
            .putString("join", s.joinMethod.name).putBoolean("hidden", s.hiddenSsid)
            .putBoolean("auto", s.autoEnabled).putInt("cooldown", s.cooldownSeconds)
            .apply()
        flow.value = s
    }
}
