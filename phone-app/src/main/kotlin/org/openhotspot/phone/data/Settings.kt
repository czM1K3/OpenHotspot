package org.openhotspot.phone.data

import androidx.core.content.edit
import android.content.Context
import org.openhotspot.common.phone.RateLimitPersistence
import org.openhotspot.common.phone.RateLimitSnapshot

/** Non-secret preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The user wants the service running (set by Start/Stop; survives process death and reboot). */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(v) = prefs.edit { putBoolean("service_enabled", v) }

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(v) = prefs.edit { putBoolean("start_on_boot", v) }
}

/** Keeps the lockout state across restarts (SECURITY.md section 3). Contains no secrets. */
class RateLimitPrefs(context: Context) : RateLimitPersistence {
    private val prefs = context.getSharedPreferences("rate_limit", Context.MODE_PRIVATE)

    override fun load(): RateLimitSnapshot? =
        if (prefs.contains("level")) RateLimitSnapshot(
            prefs.getInt("level", 0), prefs.getLong("locked_until", 0), prefs.getLong("last_failure", 0),
        ) else null

    override fun save(snapshot: RateLimitSnapshot) {
        // commit = true: synchronous, the lockout must survive a process kill.
        prefs.edit(commit = true) {
            putInt("level", snapshot.level).putLong("locked_until", snapshot.lockedUntilMs).putLong("last_failure", snapshot.lastFailureMs)
        }
    }
}
