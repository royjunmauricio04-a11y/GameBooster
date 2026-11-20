package com.gamebooster.app.core

import com.gamebooster.app.data.ProbeResult
import java.util.Locale

/**
 * Decides WHEN a live tunnel looks bad enough to look for a better server, and whether an alternative is
 * clearly better. Deliberately conservative: switching tears the tunnel down for a few seconds, which can drop
 * a game's connection, so a switch needs sustained degradation + a large margin + a cool-down.
 */
class SmartSwitchPolicy(
    private val degradeSamples: Int = 3,           // consecutive bad samples before we react
    private val lossTriggerPct: Double = 5.0,
    private val latencyRatio: Double = 1.8,        // bad if avg > baseline * ratio ...
    private val latencyFloorMs: Double = 40.0,     // ... AND > baseline + floor
    private val minGainMs: Double = 20.0,          // candidate must beat the active path by at least this ...
    private val minGainRatio: Double = 0.25,       // ... and by this fraction of the active cost
    private val switchCooldownMs: Long = 120_000,  // no switch within 2 min of the last one
    private val evalCooldownMs: Long = 60_000      // don't re-probe all servers more than once a minute
) {
    private var baselineMs: Double? = null
    private var bad = 0
    private var lastSwitchAt: Long? = null
    private var lastEvalAt: Long? = null

    fun reset() { resetBaseline(); lastSwitchAt = null; lastEvalAt = null }

    /** Baseline belongs to one path; clear it when the path changes (new server / network). */
    fun resetBaseline() { baselineMs = null; bad = 0 }

    fun markEvaluated(now: Long) { lastEvalAt = now }
    fun onSwitched(now: Long) { lastSwitchAt = now; resetBaseline() }

    private fun within(since: Long?, now: Long, window: Long) = since != null && now - since < window

    fun isDegraded(r: ProbeResult): Boolean {
        if (!r.ok) return true
        if (r.lossPct >= lossTriggerPct) return true
        val b = baselineMs ?: return false
        return r.avgMs!! > maxOf(b * latencyRatio, b + latencyFloorMs)
    }

    /** Feed one live sample. Returns a human-readable reason once degradation has persisted, else null. */
    fun onSample(now: Long, r: ProbeResult): String? {
        if (!isDegraded(r)) {
            bad = 0
            val a = r.avgMs!!
            baselineMs = baselineMs?.let { it * 0.8 + a * 0.2 } ?: a
            return null
        }
        bad++
        if (bad < degradeSamples) return null
        if (within(lastSwitchAt, now, switchCooldownMs) || within(lastEvalAt, now, evalCooldownMs)) return null
        return when {
            !r.ok -> "no response"
            r.lossPct >= lossTriggerPct -> "%.0f%% loss".format(Locale.US, r.lossPct)
            else -> "latency %.0f ms vs baseline %.0f ms".format(Locale.US, r.avgMs, baselineMs)
        }
    }

    /** True when [candidateCost] is better than [activeCost] by a clear margin. */
    fun shouldSwitch(activeCost: Double, candidateCost: Double): Boolean {
        if (candidateCost >= PathScore.UNREACHABLE) return false
        return activeCost - candidateCost >= maxOf(minGainMs, activeCost * minGainRatio)
    }
}
