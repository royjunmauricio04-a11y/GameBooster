package com.gamebooster.app.core

import com.gamebooster.app.data.ProbeResult
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** One finished tunnel session. Latency numbers are TCP connect times to the game's probe host (not in-game ping). */
data class SessionRecord(
    val startMs: Long, val endMs: Long,
    val gamePackage: String, val gameName: String,
    val servers: List<String>,
    val beforeAvgMs: Double?, val beforeLossPct: Double?,
    val avgMs: Double?, val minMs: Double?, val maxMs: Double?, val p95Ms: Double?,
    val avgJitterMs: Double?, val avgLossPct: Double?, val samples: Int,
    val rxBytes: Long, val txBytes: Long,
    val switches: Int, val reconnects: Int,
    val endReason: String,
    val series: List<Float>                  // downsampled TCP connect times, for the chart
) {
    val durationSec: Long get() = ((endMs - startMs) / 1000).coerceAtLeast(0)

    /** (tunnel - direct) / direct * 100. Negative = lower than direct. null if either side is unknown. */
    val latencyDeltaPct: Double? get() {
        val b = beforeAvgMs; val a = avgMs
        return if (b == null || a == null || b <= 0.0) null else (a - b) / b * 100.0
    }
}

/** Accumulates samples for the running session. Not thread-safe; use from one thread (the engine's main scope). */
class SessionRecorder(
    private val startMs: Long, private val gamePackage: String, private val gameName: String,
    firstServer: String, private val before: ProbeResult?
) {
    private val servers = LinkedHashSet<String>().apply { add(firstServer) }
    private val pings = ArrayList<Double>()
    private val jitters = ArrayList<Double>()
    private val losses = ArrayList<Double>()
    private var switches = 0
    private var reconnects = 0
    private var rx = 0L
    private var tx = 0L

    fun addSample(r: ProbeResult) {
        losses += r.lossPct
        r.avgMs?.let { pings += it }
        r.jitterMs?.let { jitters += it }
    }
    fun noteServer(name: String) { servers += name }
    fun noteSwitch() { switches++ }
    fun noteReconnect() { reconnects++ }
    /** WireGuard counters restart with every tunnel; add the old tunnel's totals before bringing it down. */
    fun addTraffic(rxBytes: Long, txBytes: Long) { rx += rxBytes; tx += txBytes }

    fun finish(endMs: Long, reason: String): SessionRecord {
        val sorted = pings.sorted()
        return SessionRecord(
            startMs, endMs, gamePackage, gameName, servers.toList(),
            before?.avgMs, before?.lossPct,
            pings.averageOrNull(), sorted.firstOrNull(), sorted.lastOrNull(), percentile(sorted, 95.0),
            jitters.averageOrNull(), losses.averageOrNull(), losses.size,
            rx, tx, switches, reconnects, reason, downsample(pings, 60)
        )
    }

    companion object {
        /** Nearest-rank percentile of an ascending list. */
        fun percentile(sorted: List<Double>, p: Double): Double? {
            if (sorted.isEmpty()) return null
            val idx = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[idx]
        }

        /** Average into at most [max] buckets. */
        fun downsample(values: List<Double>, max: Int): List<Float> {
            if (values.size <= max) return values.map { it.toFloat() }
            return (0 until max).map { i ->
                val from = i * values.size / max
                val to = ((i + 1) * values.size / max).coerceAtLeast(from + 1)
                values.subList(from, to).average().toFloat()
            }
        }
        private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    }
}

data class HistorySummary(
    val sessions: Int, val totalSec: Long, val avgLatencyDeltaPct: Double?,
    val switches: Int, val reconnects: Int, val rxBytes: Long, val txBytes: Long
) {
    companion object {
        fun of(list: List<SessionRecord>): HistorySummary {
            val deltas = list.mapNotNull { it.latencyDeltaPct }
            return HistorySummary(
                list.size, list.sumOf { it.durationSec },
                if (deltas.isEmpty()) null else deltas.average(),
                list.sumOf { it.switches }, list.sumOf { it.reconnects },
                list.sumOf { it.rxBytes }, list.sumOf { it.txBytes })
        }
    }
}

/** One JSON object per session (stored as JSON lines). Contains no tokens, keys or email. */
object HistoryCodec {
    fun encode(r: SessionRecord): String = JSONObject()
        .put("s", r.startMs).put("e", r.endMs).put("gp", r.gamePackage).put("gn", r.gameName)
        .put("sv", JSONArray(r.servers))
        .putOpt("ba", r.beforeAvgMs).putOpt("bl", r.beforeLossPct)
        .putOpt("a", r.avgMs).putOpt("mn", r.minMs).putOpt("mx", r.maxMs).putOpt("p95", r.p95Ms)
        .putOpt("j", r.avgJitterMs).putOpt("l", r.avgLossPct).put("n", r.samples)
        .put("rx", r.rxBytes).put("tx", r.txBytes).put("sw", r.switches).put("rc", r.reconnects)
        .put("end", r.endReason)
        .put("ser", JSONArray(r.series.map { it.toDouble() }))
        .toString()

    fun decode(line: String): SessionRecord {
        val o = JSONObject(line)
        fun d(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        val sv = o.getJSONArray("sv"); val ser = o.getJSONArray("ser")
        return SessionRecord(
            o.getLong("s"), o.getLong("e"), o.getString("gp"), o.getString("gn"),
            (0 until sv.length()).map { sv.getString(it) },
            d("ba"), d("bl"), d("a"), d("mn"), d("mx"), d("p95"), d("j"), d("l"), o.getInt("n"),
            o.getLong("rx"), o.getLong("tx"), o.getInt("sw"), o.getInt("rc"), o.getString("end"),
            (0 until ser.length()).map { ser.getDouble(it).toFloat() })
    }
}
