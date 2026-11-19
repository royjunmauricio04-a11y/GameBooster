package com.gamebooster.app.data

data class Server(
    val id: String,
    val name: String,
    val location: String,
    val host: String,
    val wgPort: Int,
    val publicKey: String,     // server WireGuard PUBLIC key (not secret)
    val apiUrl: String,        // https URL of the gateway registration agent
    val probePort: Int
)

data class GameProfile(
    val id: String,
    val packageName: String,
    val displayName: String,
    val probeHost: String,
    val probePort: Int,
    val preferredServerId: String?
)

data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)

data class ProbeResult(val avgMs: Double?, val jitterMs: Double?, val lossPct: Double) {
    val ok get() = avgMs != null
}

data class Registration(val address: String, val dns: String)
