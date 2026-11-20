package com.gamebooster.app.core

import com.gamebooster.app.data.ProbeResult

/** One comparable "cost" number for a measured path. Lower is better. Pure Kotlin (JVM-testable). */
object PathScore {
    const val LOSS_WEIGHT = 5.0      // each 1 % loss costs like 5 ms
    const val JITTER_WEIGHT = 1.5
    const val UNREACHABLE = 1_000_000.0

    fun cost(r: ProbeResult): Double {
        val avg = r.avgMs ?: return UNREACHABLE
        return avg + r.lossPct * LOSS_WEIGHT + (r.jitterMs ?: 0.0) * JITTER_WEIGHT
    }
}
