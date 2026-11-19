package com.gamebooster.app.vpn

import android.content.Context
import com.gamebooster.app.data.Registration
import com.gamebooster.app.data.Server
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** WireGuard tunnel limited to chosen app packages via Android per-app VPN routing. */
class TunnelController(context: Context) {
    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)

    private val tunnel = object : Tunnel {
        override fun getName() = "gamebooster"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    /** Non-null when the user still has to grant Android's VPN consent dialog. */
    fun consentIntent() = android.net.VpnService.prepare(appContext)

    suspend fun up(server: Server, reg: Registration, keys: KeyPair, packages: Collection<String>) =
        withContext(Dispatchers.IO) {
            val iface = Interface.Builder()
                .setKeyPair(keys)
                .addAddress(InetNetwork.parse(reg.address))
                .addDnsServer(InetAddress.getByName(reg.dns))   // tunnelled apps resolve DNS through the tunnel
                .includeApplications(packages)
                .build()
            val peer = Peer.Builder()
                .setPublicKey(Key.fromBase64(server.publicKey))
                .setEndpoint(InetEndpoint.parse("${server.host}:${server.wgPort}"))
                .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                .addAllowedIp(InetNetwork.parse("::/0"))        // no v6 tunnel address: v6 is dropped, not leaked
                .setPersistentKeepalive(25)
                .build()
            backend.setState(tunnel, Tunnel.State.UP, Config.Builder().setInterface(iface).addPeer(peer).build())
        }

    suspend fun down() = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        Unit
    }

    fun isUp() = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** Seconds since the last WireGuard handshake with the server, or null if none yet. */
    fun handshakeAgeSec(serverKey: String): Long? = runCatching {
        val ms = backend.getStatistics(tunnel).peer(Key.fromBase64(serverKey))?.latestHandshakeEpochMillis() ?: 0L
        if (ms <= 0L) null else (System.currentTimeMillis() - ms) / 1000
    }.getOrNull()

    /** (rxBytes, txBytes) */
    fun traffic(): Pair<Long, Long> = runCatching {
        val s = backend.getStatistics(tunnel); s.totalRx() to s.totalTx()
    }.getOrDefault(0L to 0L)
}
