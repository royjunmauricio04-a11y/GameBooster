package com.gamebooster.app.vpn

import com.gamebooster.app.data.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/** Real measurement: TCP connect time to host:port (an RTT proxy, not ICMP). Reported as measured. */
object Probe {
    suspend fun run(host: String, port: Int, samples: Int = 8, timeoutMs: Int = 1500): ProbeResult =
        withContext(Dispatchers.IO) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return@withContext ProbeResult(null, null, 100.0)
            val rtts = ArrayList<Double>()
            repeat(samples) {
                val t0 = System.nanoTime()
                val ok = runCatching { Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) } }.isSuccess
                if (ok) rtts.add((System.nanoTime() - t0) / 1_000_000.0)
                delay(150)
            }
            val loss = (samples - rtts.size) * 100.0 / samples
            if (rtts.isEmpty()) return@withContext ProbeResult(null, null, loss)
            val jitter = if (rtts.size > 1) rtts.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ProbeResult(rtts.average(), jitter, loss)
        }
}
