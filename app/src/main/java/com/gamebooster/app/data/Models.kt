package com.gamebooster.app.data

data class Server(
    val id: String,
    val name: String,
    val location: String,
    val host: String,
    val wgPort: Int,
    val publicKey: String,     // server WireGuard PUBLIC key (not secret)
    val apiUrl: String,        // https URL of the gateway registration agent
    val probePort: Int,
    val country: String? = null,
    val region: String? = null,
    val provider: String? = null,
    val bandwidthMbps: Int? = null,
    val capacityDevices: Int? = null,
    val healthStatus: String? = null
)

data class GameProfile(
    val id: String,
    val packageName: String,
    val displayName: String,
    val probeHost: String,
    val probePort: Int,
    val preferredServerId: String?,
    val manual: Boolean = false          // user-added (stored in user_games)
)

data class InstalledApp(val packageName: String, val label: String)

data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)

data class ProbeResult(val avgMs: Double?, val jitterMs: Double?, val lossPct: Double) {
    val ok get() = avgMs != null
}

data class Registration(val address: String, val dns: String)
