package org.openhotspot.common.phone

/** Persisted limiter state, so a restart cannot reset a lockout (SECURITY.md section 3). */
data class RateLimitSnapshot(val level: Int, val lockedUntilMs: Long, val lastFailureMs: Long)

interface RateLimitPersistence {
    fun load(): RateLimitSnapshot?
    fun save(snapshot: RateLimitSnapshot)
}

/**
 * Global failed-attempt limiter: [FAILURES] failures within [WINDOW_MS] start a lockout of
 * [BASE_MS], doubling for each consecutive lockout up to [MAX_MS]. While locked out the caller
 * must answer RATE_LIMITED without attempting any decryption. After [QUIET_RESET_MS] without a
 * failure, or one successful authenticated request, the level resets.
 */
class RateLimiter(
    private val clock: Clock,
    private val persistence: RateLimitPersistence? = null,
) {
    private val failures = ArrayDeque<Long>()
    private var level = 0
    private var lockedUntilMs = 0L
    private var lastFailureMs = 0L

    init {
        persistence?.load()?.let {
            level = it.level; lockedUntilMs = it.lockedUntilMs; lastFailureMs = it.lastFailureMs
        }
    }

    /** Seconds until the lockout ends (rounded up), or 0 when not locked out. */
    @Synchronized
    fun retryAfterSeconds(): Int {
        val now = clock.wallMs()
        // A wall-clock jump backwards must not extend a lockout beyond its maximum.
        val remaining = minOf(lockedUntilMs - now, MAX_MS)
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    @Synchronized
    fun recordFailure() {
        val now = clock.wallMs()
        if (level > 0 && now - lastFailureMs >= QUIET_RESET_MS) level = 0
        lastFailureMs = now
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > WINDOW_MS) failures.removeFirst()
        if (failures.size >= FAILURES) {
            level++
            val duration = minOf(BASE_MS shl minOf(level - 1, 20), MAX_MS)
            lockedUntilMs = now + duration
            failures.clear()
        }
        persist()
    }

    @Synchronized
    fun recordSuccess() {
        if (level != 0 || failures.isNotEmpty()) {
            level = 0
            failures.clear()
            persist()
        }
    }

    private fun persist() = persistence?.save(RateLimitSnapshot(level, lockedUntilMs, lastFailureMs))

    companion object {
        const val FAILURES = 5
        const val WINDOW_MS = 60_000L
        const val BASE_MS = 30_000L
        const val MAX_MS = 3_600_000L
        const val QUIET_RESET_MS = 3_600_000L
        const val CONNECTION_MAX_FAILURES = 3
    }
}
