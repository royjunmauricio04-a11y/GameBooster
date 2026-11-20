package com.gamebooster.app.vpn

import com.gamebooster.app.data.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/** Real measurement: TCP connect time to host:port (an RTT proxy, not ICMP). Reported as measured. */
object Probe {
    /** Resolve once (system DNS, before the tunnel) so before/after probes hit the same IP. */
    suspend fun resolve(host: String): String? = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
    }

    /** [network]: bind to this (physical) network so the probe does NOT go through our own tunnel. */
    suspend fun run(host: String, port: Int, samples: Int = 8, timeoutMs: Int = 1500, network: Network? = null): ProbeResult =
        withContext(Dispatchers.IO) {
            val addr = runCatching { if (network != null) network.getByName(host) else InetAddress.getByName(host) }.getOrNull()
                ?: return@withContext ProbeResult(null, null, 100.0)
            val rtts = ArrayList<Double>()
            repeat(samples) {
                val t0 = System.nanoTime()
                val ok = runCatching { (network?.socketFactory?.createSocket() ?: Socket()).use { it.connect(InetSocketAddress(addr, port), timeoutMs) } }.isSuccess
                if (ok) rtts.add((System.nanoTime() - t0) / 1_000_000.0)
                delay(150)
            }
            val loss = (samples - rtts.size) * 100.0 / samples
            if (rtts.isEmpty()) return@withContext ProbeResult(null, null, loss)
            val jitter = if (rtts.size > 1) rtts.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ProbeResult(rtts.average(), jitter, loss)
        }
}
