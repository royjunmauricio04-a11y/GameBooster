package com.gamebooster.app.core

import com.gamebooster.app.data.ProbeResult
import java.util.Locale

enum class QualityLevel { GOOD, OK, BAD, UNKNOWN }

data class OverlayModel(val visible: Boolean, val text: String, val level: QualityLevel)

/** Pure formatting for the in-game HUD, so it can be unit-tested. */
object OverlayText {
    fun level(r: ProbeResult): QualityLevel {
        val a = r.avgMs ?: return QualityLevel.BAD
        return when {
            r.lossPct >= 5.0 || a >= 150.0 -> QualityLevel.BAD
            r.lossPct >= 1.0 || a >= 80.0 -> QualityLevel.OK
            else -> QualityLevel.GOOD
        }
    }

    fun build(enabled: Boolean, active: Boolean, reconnecting: Boolean, canDraw: Boolean,
              live: ProbeResult?, serverName: String?): OverlayModel {
        if (!enabled || !canDraw || !(active || reconnecting)) return OverlayModel(false, "", QualityLevel.UNKNOWN)
        val srv = serverName?.take(18) ?: ""
        return when {
            reconnecting -> OverlayModel(true, "Reconnecting…", QualityLevel.UNKNOWN)
            live == null -> OverlayModel(true, "Measuring…\n$srv".trimEnd(), QualityLevel.UNKNOWN)
            !live.ok -> OverlayModel(true, "No response\n$srv".trimEnd(), QualityLevel.BAD)
            else -> OverlayModel(true,
                "%.0f ms  ±%.0f  %.0f%% loss\n%s".format(Locale.US, live.avgMs, live.jitterMs ?: 0.0, live.lossPct, srv).trimEnd(),
                level(live))
        }
    }
}
