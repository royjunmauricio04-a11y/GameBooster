package com.gamebooster.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamebooster.app.data.*
import com.gamebooster.app.vpn.Probe
import com.gamebooster.app.vpn.TunnelController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

 data class UiState(
    val configured: Boolean = true,
    val signedIn: Boolean = false,
    val email: String = "",
    val busy: Boolean = false,
    val status: String = "Disconnected",
    val error: String? = null,
    val info: String? = null,
    val servers: List<Server> = emptyList(),
    val games: List<GameProfile> = emptyList(),
    val selectedGame: GameProfile? = null,
    val selectedServer: Server? = null,          // null = automatic
    val activeServer: Server? = null,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val before: ProbeResult? = null,
    val live: ProbeResult? = null,
    val serverRtts: Map<String, ProbeResult> = emptyMap(),
    val durationSec: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = SupabaseApi(store)
    private val tunnel = TunnelController(app)
    private val initialSession = runCatching { store.load() }

    private val _s = MutableStateFlow(UiState(
        configured = api.configured,
        signedIn = initialSession.getOrNull() != null,
        email = initialSession.getOrNull()?.email ?: ""
    ))
    val state: StateFlow<UiState> = _s

    private val _needConsent = MutableStateFlow<Intent?>(null)
    val needConsent: StateFlow<Intent?> = _needConsent
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var pendingConnect = false
    private val tunnelMutation = Mutex()

    init {
        initialSession.exceptionOrNull()?.let { e ->
            upd { it.copy(error = e.message ?: "Encrypted storage is unavailable.") }
        }
        // This controller uses a fixed, app-owned tunnel name. Report its actual state, but
        // do not infer a server or game from an unidentified/stale configuration.
        if (runCatching { tunnel.isUp() }.getOrDefault(false)) {
            upd {
                it.copy(
                    connected = true,
                    status = "Connected (server details unavailable)",
                    info = "An existing Game Booster tunnel was detected. Disconnect before reconnecting."
                )
            }
        }
        if (_s.value.signedIn && api.configured) refreshCatalog()
    }

    private fun upd(f: (UiState) -> UiState) = _s.update(f)

    fun signIn(email: String, pw: String) { launchBusy {
        val s = api.signIn(email.trim(), pw)
        upd { it.copy(signedIn = true, email = s.email) }
        loadCatalog()
    } }

    fun signUp(email: String, pw: String) { launchBusy {
        val s = api.signUp(email.trim(), pw)
        if (s == null) upd { it.copy(info = "Check your email to confirm your account, then sign in.") }
        else { upd { it.copy(signedIn = true, email = s.email) }; loadCatalog() }
    } }

    fun signOut() {
        disconnect()
        val cleared = runCatching { store.clearSession() }
        upd { UiState(configured = api.configured, error = cleared.exceptionOrNull()?.message) }
    }

    fun refreshCatalog() { launchBusy { loadCatalog() } }

    private suspend fun loadCatalog() {
        val servers = api.servers()
        val installed = installedPackages()
        val games = api.games().filter { it.packageName in installed }
        upd { it.copy(servers = servers, games = games, selectedGame = it.selectedGame ?: games.firstOrNull()) }
    }

    private fun installedPackages(): Set<String> {
        val pm = getApplication<Application>().packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }

    fun selectGame(g: GameProfile) = upd { it.copy(selectedGame = g) }
    fun selectServer(s: Server?) = upd { it.copy(selectedServer = s) }

    fun testServers() { launchBusy {
        val res = coroutineScope {
            _s.value.servers.map { s -> async { s.id to Probe.run(s.host, s.probePort, 5) } }.awaitAll().toMap()
        }
        upd { it.copy(serverRtts = res) }
    } }

    fun toggle() { if (_s.value.connected || _s.value.reconnecting) disconnect() else connect() }

    fun onConsentResult(granted: Boolean) {
        val shouldConnect = pendingConnect
        pendingConnect = false
        _needConsent.value = null
        upd { it.copy(busy = false) }
        if (granted && shouldConnect) connect()
        else if (!granted && shouldConnect) {
            upd { it.copy(error = "VPN permission was denied.", status = "Disconnected") }
        }
    }

    private fun connect() {
        val st = _s.value
        if (connectJob?.isActive == true || disconnectJob?.isActive == true || st.busy || st.connected || st.reconnecting) return
        val game = st.selectedGame ?: return upd { it.copy(error = "Pick a game first.") }
        if (st.servers.isEmpty()) return upd { it.copy(error = "No servers available in the server directory.") }
        tunnel.consentIntent()?.let {
            pendingConnect = true
            upd { it.copy(busy = true, error = null, status = "Waiting for VPN permission…") }
            _needConsent.value = it
            return
        }
        pendingConnect = false
        upd { it.copy(busy = true, error = null, status = "Measuring baseline…") }

        connectJob = viewModelScope.launch {
            var server: Server? = null
            try {
                val before = Probe.run(game.probeHost, game.probePort)       // direct, no tunnel yet
                upd { it.copy(before = before, status = "Selecting server…") }
                val chosenServer = st.selectedServer ?: pickBest(st.servers, game)
                server = chosenServer
                upd { it.copy(status = "Registering device…") }
                val keys = store.deviceKeyPair()
                val reg = api.register(chosenServer, keys.publicKey.toBase64())
                upd { it.copy(status = "Connecting…") }
                // Our own package is included so the live probes measure the tunnelled path.
                tunnelMutation.withLock {
                    tunnel.up(chosenServer, reg, keys, listOf(game.packageName, getApplication<Application>().packageName))
                }
                upd { it.copy(connected = true, busy = false, activeServer = chosenServer, status = "Connected") }
                api.logConnection(chosenServer.id, game.packageName, true)
                startMonitor(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { tunnelMutation.withLock { tunnel.down() } }
                server?.let { api.logConnection(it.id, game.packageName, false) }
                if (e is ApiException && e.code == 401) signOut()
                upd { it.copy(busy = false, connected = false, status = "Disconnected", error = e.message ?: "Connection failed") }
            } finally {
                if (connectJob === coroutineContext[Job]) connectJob = null
            }
        }
    }

    /** Servers that answered latency probes, best first (latency + loss penalty). */
    private suspend fun rankServers(servers: List<Server>): List<Server> = coroutineScope {
        val results = servers.map { s -> async { s to Probe.run(s.host, s.probePort, 4) } }.awaitAll()
        upd { it.copy(serverRtts = results.associate { (s, r) -> s.id to r }) }
        results.filter { it.second.ok }.sortedBy { it.second.avgMs!! + it.second.lossPct * 5 }.map { it.first }
    }

    private suspend fun pickBest(servers: List<Server>, game: GameProfile): Server {
        val ranked = rankServers(servers)
        game.preferredServerId?.let { id -> ranked.firstOrNull { it.id == id }?.let { return it } }
        return ranked.firstOrNull() ?: throw ApiException("No server responded to latency tests.")
    }

    private fun startMonitor(game: GameProfile) {
        monitorJob?.cancel()
        val t0 = System.currentTimeMillis()
        monitorJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    val (rx, tx) = tunnel.traffic()
                    upd { it.copy(durationSec = (System.currentTimeMillis() - t0) / 1000, rxBytes = rx, txBytes = tx) }
                    val server = _s.value.activeServer
                    val up = tunnel.isUp()
                    // Dead tunnel: backend reports down, or no handshake after a 45 s grace period.
                    val age = server?.let { tunnel.handshakeAgeSec(it.publicKey) }
                    val stale = System.currentTimeMillis() - t0 > 45_000 && (age == null || age > 300)
                    if (!up || stale) { reconnect(game); break }
                    delay(1000)
                }
            }
            while (isActive) {
                val r = Probe.run(game.probeHost, game.probePort, 5)
                upd { it.copy(live = r) }
                delay(10_000)
            }
        }
    }

    /** Retry with backoff: same server first, then the next-best measured servers (failover). */
    private fun reconnect(game: GameProfile) {
        if (reconnectJob?.isActive == true || disconnectJob?.isActive == true) return
        monitorJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val st = _s.value
            val current = st.activeServer
            upd { it.copy(connected = false, reconnecting = true, busy = false, live = null, status = "Reconnecting…") }
            tunnelMutation.withLock { tunnel.down() }
            val ranked = runCatching { rankServers(st.servers) }.getOrDefault(emptyList())
            val order = (listOfNotNull(current) + ranked.filter { it.id != current?.id }).take(4)
            val keys = try { store.deviceKeyPair() } catch (e: Exception) {
                upd { it.copy(reconnecting = false, status = "Disconnected", error = e.message ?: "Secure storage is unavailable.") }
                return@launch
            }
            for ((i, server) in order.withIndex()) {
                delay(1500L * (i + 1))
                upd { it.copy(status = "Reconnecting (${i + 1}/${order.size}) – ${server.name}") }
                try {
                    val reg = api.register(server, keys.publicKey.toBase64())
                    tunnelMutation.withLock {
                        tunnel.up(server, reg, keys, listOf(game.packageName, getApplication<Application>().packageName))
                    }
                    upd { it.copy(connected = true, reconnecting = false, activeServer = server, status = "Connected") }
                    api.logConnection(server.id, game.packageName, true)
                    startMonitor(game)
                    return@launch
                } catch (e: CancellationException) { throw e
                } catch (e: ApiException) {
                    if (e.code == 401) { signOut(); return@launch }
                } catch (e: Exception) { /* try next server */ }
            }
            tunnelMutation.withLock { tunnel.down() }
            upd { it.copy(reconnecting = false, connected = false, status = "Disconnected",
                error = "Connection lost and could not be restored. Check your network and try again.") }
        }
    }

    private fun disconnect() {
        pendingConnect = false
        _needConsent.value = null
        connectJob?.cancel()
        monitorJob?.cancel()
        reconnectJob?.cancel()
        if (disconnectJob?.isActive == true) return
        upd { it.copy(connected = false, reconnecting = false, busy = true, status = "Disconnecting…", live = null) }
        disconnectJob = viewModelScope.launch {
            try {
                withContext(NonCancellable) { tunnelMutation.withLock { tunnel.down() } }
            } finally {
                upd { it.copy(connected = false, reconnecting = false, busy = false, status = "Disconnected", activeServer = null) }
                disconnectJob = null
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        upd { it.copy(busy = true, error = null) }
        try { block() }
        catch (e: ApiException) { if (e.code == 401) signOut(); upd { it.copy(error = e.message) } }
        catch (e: Exception) { upd { it.copy(error = e.message ?: "Something went wrong") } }
        finally { upd { it.copy(busy = false) } }
    }
}
