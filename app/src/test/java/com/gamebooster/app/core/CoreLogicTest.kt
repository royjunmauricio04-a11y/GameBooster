package com.gamebooster.app.core

import com.gamebooster.app.data.ProbeResult
import org.junit.Assert.*
import org.junit.Test

class SmartSwitchPolicyTest {
    private fun ok(ms: Double, loss: Double = 0.0) = ProbeResult(ms, 2.0, loss)
    private val dead = ProbeResult(null, null, 100.0)

    @Test fun healthyTrafficNeverTriggers() {
        val p = SmartSwitchPolicy()
        repeat(20) { assertNull(p.onSample(it * 10_000L, ok(50.0))) }
    }
    @Test fun needsConsecutiveBadSamples() {
        val p = SmartSwitchPolicy()
        repeat(5) { p.onSample(it * 10_000L, ok(50.0)) }
        assertNull(p.onSample(100_000, ok(200.0)))
        assertNull(p.onSample(110_000, ok(200.0)))
        assertNotNull(p.onSample(120_000, ok(200.0)))
    }
    @Test fun oneGoodSampleResetsTheStreak() {
        val p = SmartSwitchPolicy()
        repeat(5) { p.onSample(it * 10_000L, ok(50.0)) }
        p.onSample(100_000, ok(200.0)); p.onSample(110_000, ok(200.0))
        p.onSample(120_000, ok(50.0))
        assertNull(p.onSample(130_000, ok(200.0)))
    }
    @Test fun lossTriggersWithoutBaseline() {
        val p = SmartSwitchPolicy()
        assertNull(p.onSample(0, ok(50.0, loss = 20.0))); assertNull(p.onSample(1, ok(50.0, loss = 20.0)))
        assertTrue(p.onSample(2, ok(50.0, loss = 20.0))!!.contains("loss"))
    }
    @Test fun deadPathTriggers() {
        val p = SmartSwitchPolicy()
        p.onSample(0, dead); p.onSample(1, dead)
        assertEquals("no response", p.onSample(2, dead))
    }
    @Test fun smallLatencyBumpIsNotDegradation() {
        val p = SmartSwitchPolicy()
        repeat(5) { p.onSample(it * 10_000L, ok(20.0)) }
        assertFalse(p.isDegraded(ok(50.0)))      // 2.5x but only +30 ms (< 40 ms floor)
        assertTrue(p.isDegraded(ok(100.0)))
    }
    @Test fun evalCooldownLimitsReProbing() {
        val p = SmartSwitchPolicy()
        repeat(3) { p.onSample(it.toLong(), dead) }
        p.markEvaluated(10_000)
        assertNull(p.onSample(20_000, dead))
        assertNotNull(p.onSample(80_000, dead))
    }
    @Test fun switchCooldownBlocksSwitching() {
        val p = SmartSwitchPolicy()
        p.onSwitched(0)
        repeat(5) { assertNull(p.onSample(1_000L + it, dead)) }
        assertNotNull(p.onSample(130_000, dead))
    }
    @Test fun switchNeedsClearMargin() {
        val p = SmartSwitchPolicy()
        assertFalse(p.shouldSwitch(100.0, 90.0))      // < 25 ms needed
        assertFalse(p.shouldSwitch(100.0, 80.0))      // 20 < 25
        assertTrue(p.shouldSwitch(100.0, 70.0))
        assertTrue(p.shouldSwitch(PathScore.UNREACHABLE, 120.0))
        assertFalse(p.shouldSwitch(100.0, PathScore.UNREACHABLE))
        assertFalse(p.shouldSwitch(30.0, 20.0))       // below min gain of 20 ms
    }
}

class RouteHealthTest {
    @Test fun tripsAfterTwoFailuresAndRecovers() {
        val h = RouteHealth(2, 1000)
        h.recordFailure("a", 0); assertFalse(h.isBlocked("a", 1))
        h.recordFailure("a", 10); assertTrue(h.isBlocked("a", 500))
        assertFalse(h.isBlocked("a", 1500))           // cool-down over: half-open
        h.recordFailure("a", 1600); assertTrue(h.isBlocked("a", 1700))   // one failure re-trips
    }
    @Test fun successClears() {
        val h = RouteHealth(2, 1000)
        h.recordFailure("a", 0); h.recordFailure("a", 1); h.recordSuccess("a")
        assertFalse(h.isBlocked("a", 2))
    }
    @Test fun serversAreIndependent() {
        val h = RouteHealth(1, 1000)
        h.recordFailure("a", 0)
        assertTrue(h.isBlocked("a", 1)); assertFalse(h.isBlocked("b", 1))
    }
    @Test fun failoverSkipsBlockedAndAlreadyTriedRoutes() {
        val h = RouteHealth(1, 1000)
        h.recordFailure("blocked", 0)
        assertEquals("backup", h.firstEligible(listOf("blocked", "tried", "backup"), setOf("tried"), 500))
        assertEquals("blocked", h.firstEligible(listOf("blocked", "backup"), emptySet(), 1000))
    }
}

class SessionRecorderTest {
    private fun rec() = SessionRecorder(0, "com.x.game", "Game", "SG", ProbeResult(100.0, 5.0, 0.0))

    @Test fun aggregatesSamples() {
        val r = rec()
        listOf(40.0, 50.0, 60.0, 70.0).forEach { r.addSample(ProbeResult(it, 4.0, 0.0)) }
        r.addSample(ProbeResult(null, null, 100.0))
        val s = r.finish(60_000, "user disconnected")
        assertEquals(55.0, s.avgMs!!, 0.001); assertEquals(40.0, s.minMs!!, 0.001); assertEquals(70.0, s.maxMs!!, 0.001)
        assertEquals(5, s.samples); assertEquals(20.0, s.avgLossPct!!, 0.001)
        assertEquals(60L, s.durationSec)
        assertEquals(-45.0, s.latencyDeltaPct!!, 0.001)    // 55 vs 100 direct
    }
    @Test fun emptySessionHasNoStats() {
        val s = rec().finish(1000, "x")
        assertNull(s.avgMs); assertNull(s.p95Ms); assertNull(s.latencyDeltaPct); assertTrue(s.series.isEmpty())
    }
    @Test fun trafficAccumulatesAcrossTunnels() {
        val r = rec(); r.addTraffic(100, 10); r.addTraffic(50, 5)
        val s = r.finish(1000, "x"); assertEquals(150L, s.rxBytes); assertEquals(15L, s.txBytes)
    }
    @Test fun countsSwitchesReconnectsAndServers() {
        val r = rec(); r.noteSwitch(); r.noteReconnect(); r.noteReconnect(); r.noteServer("JP"); r.noteServer("JP")
        val s = r.finish(1000, "x")
        assertEquals(1, s.switches); assertEquals(2, s.reconnects); assertEquals(listOf("SG", "JP"), s.servers)
    }
    @Test fun percentileNearestRank() {
        val v = (1..100).map { it.toDouble() }
        assertEquals(95.0, SessionRecorder.percentile(v, 95.0)!!, 0.0)
        assertEquals(7.0, SessionRecorder.percentile(listOf(7.0), 95.0)!!, 0.0)
        assertNull(SessionRecorder.percentile(emptyList(), 95.0))
    }
    @Test fun downsampleKeepsShapeAndLimit() {
        val v = (1..300).map { it.toDouble() }
        val d = SessionRecorder.downsample(v, 60)
        assertEquals(60, d.size); assertTrue(d.first() < d.last())
        assertEquals(5, SessionRecorder.downsample(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 60).size)
    }
    @Test fun summaryAverages() {
        val a = rec().apply { addSample(ProbeResult(50.0, 1.0, 0.0)) }.finish(10_000, "x")    // -50 %
        val b = rec().apply { addSample(ProbeResult(150.0, 1.0, 0.0)) }.finish(20_000, "x")   // +50 %
        val s = HistorySummary.of(listOf(a, b))
        assertEquals(2, s.sessions); assertEquals(30L, s.totalSec); assertEquals(0.0, s.avgLatencyDeltaPct!!, 0.001)
    }
}

class HistoryCodecTest {
    @Test fun roundTrip() {
        val r = SessionRecorder(5, "com.x.game", "Game \"Q\"", "SG", ProbeResult(100.0, 5.0, 1.0)).apply {
            addSample(ProbeResult(50.0, 3.0, 0.0)); noteServer("JP"); noteSwitch(); addTraffic(1, 2)
        }.finish(9_000, "user disconnected")
        assertEquals(r, HistoryCodec.decode(HistoryCodec.encode(r)))
    }
    @Test fun nullsSurviveRoundTrip() {
        val r = SessionRecorder(0, "p.q", "G", "S", null).finish(1, "x")
        assertEquals(r, HistoryCodec.decode(HistoryCodec.encode(r)))
    }
    @Test fun corruptLineThrows() { try { HistoryCodec.decode("{nope"); fail() } catch (e: Exception) { } }
}

class OverlayTextTest {
    private fun b(live: ProbeResult?, enabled: Boolean = true, active: Boolean = true, rec: Boolean = false, draw: Boolean = true) =
        OverlayText.build(enabled, active, rec, draw, live, "Singapore")

    @Test fun hiddenWhenDisabledOrNoPermissionOrIdle() {
        assertFalse(b(null, enabled = false).visible); assertFalse(b(null, draw = false).visible); assertFalse(b(null, active = false).visible)
    }
    @Test fun formatsLiveNumbers() {
        val m = b(ProbeResult(42.4, 3.2, 0.0))
        assertTrue(m.visible); assertEquals("42 ms  ±3  0% loss\nSingapore", m.text); assertEquals(QualityLevel.GOOD, m.level)
    }
    @Test fun levels() {
        assertEquals(QualityLevel.OK, OverlayText.level(ProbeResult(90.0, 1.0, 0.0)))
        assertEquals(QualityLevel.OK, OverlayText.level(ProbeResult(30.0, 1.0, 2.0)))
        assertEquals(QualityLevel.BAD, OverlayText.level(ProbeResult(30.0, 1.0, 6.0)))
        assertEquals(QualityLevel.BAD, OverlayText.level(ProbeResult(200.0, 1.0, 0.0)))
        assertEquals(QualityLevel.BAD, OverlayText.level(ProbeResult(null, null, 100.0)))
    }
    @Test fun reconnectingAndMeasuring() {
        assertEquals("Reconnecting…", b(null, active = false, rec = true).text)
        assertTrue(b(null).text.startsWith("Measuring"))
        assertTrue(b(ProbeResult(null, null, 100.0)).text.startsWith("No response"))
    }
}
