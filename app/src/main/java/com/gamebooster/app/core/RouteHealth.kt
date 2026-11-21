package com.gamebooster.app.core

/**
 * Circuit breaker per server. After [failuresToTrip] failures a server is "blocked" (sorted last, skipped for
 * switching) for [coolDownMs]; after that it gets exactly one more chance (half-open). Success clears it.
 * In-memory only: a process restart resets it.
 */
class RouteHealth(private val failuresToTrip: Int = 2, private val coolDownMs: Long = 300_000) {
    private class Entry(var failures: Int, var trippedAt: Long?)
    private val m = HashMap<String, Entry>()

    @Synchronized fun recordFailure(id: String, now: Long) {
        val e = m.getOrPut(id) { Entry(0, null) }
        e.failures++
        if (e.failures >= failuresToTrip) e.trippedAt = now
    }

    @Synchronized fun recordSuccess(id: String) { m.remove(id) }

    @Synchronized fun isBlocked(id: String, now: Long): Boolean {
        val e = m[id] ?: return false
        val t = e.trippedAt ?: return false
        if (now - t >= coolDownMs) { e.trippedAt = null; e.failures = failuresToTrip - 1; return false }
        return true
    }

    /** Selects the highest-ranked route that has not been tried in this attempt and is not currently tripped. */
    fun firstEligible(orderedIds: List<String>, triedIds: Set<String>, now: Long): String? =
        orderedIds.firstOrNull { it !in triedIds && !isBlocked(it, now) }

    @Synchronized fun clear() = m.clear()
}
