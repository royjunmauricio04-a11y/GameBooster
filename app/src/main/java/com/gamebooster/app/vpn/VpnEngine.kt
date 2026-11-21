package com.gamebooster.app.vpn

import android.content.Intent
import com.gamebooster.app.data.Registration
import com.gamebooster.app.data.Server
import com.wireguard.crypto.KeyPair

/** Coarse lifecycle state reported by a platform VPN engine. */
enum class VpnEngineStatus { DISCONNECTED, CONNECTED }

/** Transport-neutral lifecycle contract. Concrete engines own their platform tunnel implementation. */
interface VpnEngine<in Config> {
    suspend fun connect(config: Config)
    suspend fun disconnect()
    fun status(): VpnEngineStatus
}

/** WireGuard capabilities currently required by the Android coordinator. */
interface WireGuardEngine : VpnEngine<WireGuardConfig> {
    /** Non-null when Android still requires the user's VPN consent. */
    fun consentIntent(): Intent?

    /** Seconds since the last handshake, or null if no handshake has been recorded. */
    fun handshakeAgeSec(serverKey: String): Long?

    /** (rxBytes, txBytes). */
    fun traffic(): Pair<Long, Long>
}

/** Ephemeral connection input; its private key is deliberately omitted from toString(). */
class WireGuardConfig(
    val server: Server,
    val registration: Registration,
    val keys: KeyPair,
    packages: Collection<String>
) {
    val packages: List<String> = packages.toList()

    override fun toString(): String = "WireGuardConfig(serverId=${server.id}, packages=${packages.size})"
}
