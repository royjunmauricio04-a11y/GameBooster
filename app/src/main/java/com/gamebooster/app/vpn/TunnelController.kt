package com.gamebooster.app.vpn

import android.content.Context
import com.gamebooster.app.core.DnsServerAddress
import com.gamebooster.app.data.Server
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WireGuard tunnel limited to chosen app packages via Android per-app VPN routing. */
class TunnelController(context: Context) : WireGuardEngine {
    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)

    private val tunnel = object : Tunnel {
        override fun getName() = "gamebooster"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    override fun consentIntent() = android.net.VpnService.prepare(appContext)

    override suspend fun connect(config: WireGuardConfig) = withContext(Dispatchers.IO) {
        val iface = Interface.Builder()
            .setKeyPair(config.keys)
            .addAddress(InetNetwork.parse(config.registration.address))
            .addDnsServer(DnsServerAddress.parseLiteral(config.registration.dns)) // no hostname lookup before tunnel activation
            .includeApplications(config.packages)
            .build()
        val peer = Peer.Builder()
            .setPublicKey(Key.fromBase64(config.server.publicKey))
            .setEndpoint(InetEndpoint.parse("${config.server.host}:${config.server.wgPort}"))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .addAllowedIp(InetNetwork.parse("::/0")) // no v6 tunnel address: v6 is intended to be dropped, not leaked
            .setPersistentKeepalive(25)
            .build()
        backend.setState(tunnel, Tunnel.State.UP, Config.Builder().setInterface(iface).addPeer(peer).build())
        Unit
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        Unit
    }

    override fun status() = runCatching {
        if (backend.getState(tunnel) == Tunnel.State.UP) VpnEngineStatus.CONNECTED else VpnEngineStatus.DISCONNECTED
    }.getOrDefault(VpnEngineStatus.DISCONNECTED)

    /** Seconds since the last WireGuard handshake with the server, or null if none yet. */
    override fun handshakeAgeSec(serverKey: String): Long? = runCatching {
        val ms = backend.getStatistics(tunnel).peer(Key.fromBase64(serverKey))?.latestHandshakeEpochMillis() ?: 0L
        if (ms <= 0L) null else (System.currentTimeMillis() - ms) / 1000
    }.getOrNull()

    /** (rxBytes, txBytes) */
    override fun traffic(): Pair<Long, Long> = runCatching {
        val s = backend.getStatistics(tunnel); s.totalRx() to s.totalTx()
    }.getOrDefault(0L to 0L)
}
