package com.gamebooster.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import com.gamebooster.app.BoosterService
import com.gamebooster.app.BuildConfig
import com.gamebooster.app.core.*
import com.gamebooster.app.data.*
import com.gamebooster.app.vpn.Probe
import com.gamebooster.app.vpn.TunnelController
import com.gamebooster.app.vpn.VpnEngineStatus
import com.gamebooster.app.vpn.WireGuardConfig
import com.gamebooster.app.vpn.WireGuardEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val configured: Boolean = true,
    val signedIn: Boolean = false,
    val email: String = "",
    val busy: Boolean = false,
    val status: String = "Disconnected",
    val error: String? = null,
    val info: String? = null,
    val servers: List<Server> = emptyList(),
    val games: List<GameProfile> = emptyList(),               // catalog + manual, installed only
    val installedApps: List<InstalledApp> = emptyList(),      // for the manual-add picker
    val gamePrefs: Map<String, String> = emptyMap(),          // game package -> preferred server id
    val selectedGame: GameProfile? = null,
    val selectedServer: Server? = null,                       // null = automatic
    val activeServer: Server? = null,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val before: ProbeResult? = null,                          // TCP connect to game host, direct
    val live: ProbeResult? = null,                            // same, through the tunnel
    val serverRtts: Map<String, ProbeResult> = emptyMap(),    // phone -> gateway (TCP connect)
    val gatewayToGame: Map<String, ProbeResult> = emptyMap(), // gateway -> game host (TCP connect, measured by the gateway)
    val handshakeAgeSec: Long? = null,
    val dns: String? = null,
    val autoReconnect: Boolean = true,
    val smartSwitch: Boolean = false,                         // auto-switch server when the live path degrades
    val overlayEnabled: Boolean = false,                      // floating in-game HUD (also needs the system permission)
    val overlayPermissionGranted: Boolean = false,
    val backups: List<String> = emptyList(),                  // ranked standby routes (names), best first
    val history: List<SessionRecord> = emptyList(),           // newest first
    val durationSec: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val log: List<String> = emptyList()
)

/** WireGuard did not complete a handshake in time (typically UDP blocked or gateway firewall). */
class HandshakeException(message: String) : Exception(message)

/**
 * Process-scoped connection engine. Owns tunnel lifecycle, monitoring, reconnect and failover so they keep
 * running while the Activity is gone (BoosterService keeps the process alive while connected).
 */
class BoosterEngine(private val app: Application, private val tunnel: WireGuardEngine = TunnelController(app)) {
    private val store = SessionStore(app)
    private val api = SupabaseApi(store)
    private val history = HistoryStore(app)
    private val health = RouteHealth()
    private val policy = SmartSwitchPolicy()
    private val historyMutex = Mutex()
    private var recorder: SessionRecorder? = null
    @Volatile private var switching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private fun freshState() = UiState(configured = api.configured, autoReconnect = store.autoReconnect(),
        smartSwitch = store.smartSwitch(), overlayEnabled = store.overlayEnabled(),
        overlayPermissionGranted = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false))
    private val _s = MutableStateFlow(freshState().copy(signedIn = store.load() != null, email = store.load()?.email ?: ""))
    val state: StateFlow<UiState> = _s

    private val _needConsent = MutableStateFlow<Intent?>(null)
    val needConsent: StateFlow<Intent?> = _needConsent

    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var netJob: Job? = null
    private var pendingConnect = false
    private var probeAddr: String? = null          // game probe IP resolved once, before the tunnel
    private val networkTransitionPolicy = NetworkTransitionPolicy()
    private var sessionStart = 0L
    @Volatile private var uiVisible = true
    private val gwCache = HashMap<String, Pair<Long, ProbeResult?>>()   // gateway->game probes, 60 s

    private fun upd(f: (UiState) -> UiState) = _s.update(f)

    /** Diagnostics log. Never put tokens, keys or emails in here. */
    private fun note(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        upd { it.copy(log = (it.log + "$t $msg").takeLast(40)) }
    }

    init {
        registerNetworkCallback()
        if (_s.value.signedIn) scope.launch { restoreHistory() }
        if (_s.value.signedIn && api.configured) scope.launch { startup() }
    }

    /** Called from Activity onStart/onStop: probing and UI stats pause while nobody is looking (battery). */
    fun setUiVisible(v: Boolean) { uiVisible = v }

    // ---------- startup: reconcile UI with the real tunnel ----------
    private suspend fun startup() {
        val tunnelWasUp = tunnel.status() == VpnEngineStatus.CONNECTED
        try { loadCatalog() }
        catch (e: ApiException) { if (e.code == 401) { forceSignOut(); return }; upd { it.copy(error = e.message) } }
        catch (e: Exception) { upd { it.copy(error = e.message ?: "Could not load servers") } }
        if (!tunnelWasUp) { store.clearLast(); return }

        val last = store.loadLast()
        val st = _s.value
        val game = last?.let { l -> st.games.firstOrNull { it.packageName == l.gamePackage } }
        val server = last?.let { l -> st.servers.firstOrNull { it.id == l.serverId } }
        if (game == null || server == null) {   // can't prove what this tunnel is for: shut it down, don't leave it orphaned
            tunnel.disconnect(); store.clearLast(); note("Shut down an unidentified tunnel at startup"); return
        }
        probeAddr = Probe.resolve(game.probeHost)
        networkTransitionPolicy.reset(underlyingSignature())
        sessionStart = System.currentTimeMillis()
        recorder = SessionRecorder(sessionStart, game.packageName, game.displayName, server.name, null)
        policy.reset()
        val serviceStart = BoosterService.start(app)
        if (serviceStart.isFailure) {
            tunnel.disconnect(); store.clearLast(); sessionStart = 0; recorder = null
            val message = "Could not start the background connection service; the restored tunnel was stopped."
            note(message)
            upd { it.copy(connected = false, reconnecting = false, activeServer = null, status = "Disconnected", error = message) }
            return
        }
        note("Resumed existing tunnel (${server.name})")
        upd { it.copy(selectedGame = game, activeServer = server, connected = true, status = "Connected") }
        startMonitor(game)
    }

    // ---------- auth ----------
    fun signIn(email: String, pw: String) { launchBusy {
        val s = api.signIn(email.trim(), pw)
        upd { it.copy(signedIn = true, email = s.email) }
        restoreHistory()
        loadCatalog()
    } }

    fun signUp(email: String, pw: String) { launchBusy {
        val s = api.signUp(email.trim(), pw)
        if (s == null) upd { it.copy(info = "Check your email to confirm your account, then sign in.") }
        else { upd { it.copy(signedIn = true, email = s.email) }; restoreHistory(); loadCatalog() }
    } }

    fun handleAuthCallback(callback: String?) {
        if (callback.isNullOrBlank()) return
        launchBusy {
            val session = api.completeAuthCallback(callback)
            upd { it.copy(signedIn = true, email = session.email, info = "Email confirmed. You are signed in.") }
            restoreHistory()
            loadCatalog()
        }
    }

    private suspend fun restoreHistory() {
        val records = historyMutex.withLock { withContext(Dispatchers.IO) { history.load() } }
        upd { it.copy(history = records.asReversed()) }
    }

    /** User-initiated: also asks the gateway to drop this device's peer (best effort). */
    fun signOut() {
        val oldToken = store.load()?.accessToken
        val last = store.loadLast()
        val servers = _s.value.servers
        val pub = runCatching { store.deviceKeyPair().publicKey.toBase64() }.getOrNull()
        recorder = null
        disconnect()
        upd { freshState() }
        clearPersistedHistory()
        scope.launch {
            if (last != null && pub != null)
                servers.firstOrNull { it.id == last.serverId }?.let { runCatching { api.unregister(it, pub) } }
            if (store.load()?.accessToken == oldToken) store.clearSession()   // don't wipe a newer sign-in
        }
    }

    private fun forceSignOut() {
        recorder = null
        disconnect()
        clearPersistedHistory()
        store.clearSession(); store.clearLast()
        upd { freshState() }
    }

    // ---------- catalog & games ----------
    fun refreshCatalog() { launchBusy { loadCatalog() } }

    private suspend fun loadCatalog() {
        val servers = api.servers()
        val installed = installedPackages()
        val merged = LinkedHashMap<String, GameProfile>()
        api.games().forEach { merged[it.packageName] = it }
        // user_games is optional (older schema): never let it break the catalog; the user's own entry wins
        val mine = try { api.userGames() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        mine.forEach { merged[it.packageName] = it }
        val games = merged.values.filter { it.packageName in installed }.sortedBy { it.displayName.lowercase() }
        val prefs = store.gameServerPrefs()
        upd { st ->
            val keep = st.connected || st.reconnecting            // never swap the game under a live tunnel
            val sel = if (keep) st.selectedGame
                else st.selectedGame?.let { sg -> games.firstOrNull { it.packageName == sg.packageName } } ?: games.firstOrNull()
            st.copy(servers = servers, games = games, gamePrefs = prefs, selectedGame = sel)
        }
    }

    private fun installedPackages(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return app.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }

    fun loadInstalledApps() { scope.launch {
        val apps = withContext(Dispatchers.Default) {
            val pm = app.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.packageName != app.packageName }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
        upd { it.copy(installedApps = apps) }
    } }

    fun addManualGame(a: InstalledApp, host: String, port: Int) { launchBusy {
        val label = a.label.take(80)
        GameInput.validate(a.packageName, label, host, port)?.let { throw ApiException(it) }
        api.addUserGame(a.packageName, label, host, port)
        loadCatalog()
    } }

    fun removeManualGame(g: GameProfile) { launchBusy {
        api.deleteUserGame(g.id)
        store.setGameServerPref(g.packageName, null)
        loadCatalog()
    } }

    fun setGameServer(g: GameProfile, s: Server?) {
        store.setGameServerPref(g.packageName, s?.id)
        upd { it.copy(gamePrefs = store.gameServerPrefs()) }
    }

    fun selectGame(g: GameProfile) = upd { it.copy(selectedGame = g) }
    fun selectServer(s: Server?) = upd { it.copy(selectedServer = s) }
    fun dismissError() = upd { it.copy(error = null, info = null) }

    fun setAutoReconnect(on: Boolean) { store.setAutoReconnect(on); upd { it.copy(autoReconnect = on) } }

    fun setSmartSwitch(on: Boolean) { store.setSmartSwitch(on); policy.resetBaseline(); upd { it.copy(smartSwitch = on) } }
    fun setOverlay(on: Boolean) { store.setOverlayEnabled(on); upd { it.copy(overlayEnabled = on) } }
    fun refreshOverlayPermission() {
        val granted = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
        upd { if (it.overlayPermissionGranted == granted) it else it.copy(overlayPermissionGranted = granted) }
    }
    fun clearHistory() { upd { it.copy(history = emptyList()) }; clearPersistedHistory() }

    private fun clearPersistedHistory() {
        scope.launch { historyMutex.withLock { withContext(Dispatchers.IO) { history.clear() } } }
    }
    private fun overlayActive() = _s.value.overlayEnabled && runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)

    /** Ends the running session, stores it locally and uploads the summary (best effort). Call BEFORE tunnel.disconnect(). */
    private fun finishSession(reason: String) {
        val rec = recorder ?: return
        recorder = null
        val (rx, tx) = tunnel.traffic(); rec.addTraffic(rx, tx)
        val r = rec.finish(System.currentTimeMillis(), reason)
        if (r.durationSec < 10 && r.samples == 0) return          // nothing worth keeping
        upd { it.copy(history = (listOf(r) + it.history).take(200)) }
        scope.launch {
            historyMutex.withLock { withContext(Dispatchers.IO) { history.append(r) } }
            api.uploadSession(r)
        }
    }

    /** Fresh phone->gateway and gateway->game measurements for every server. */
    fun testServers() { launchBusy {
        gwCache.clear()
        rankServers(_s.value.servers, _s.value.selectedGame)
    } }

    /** Plain-text report for support. Contains no tokens, keys or email. */
    fun diagnosticsReport(): String {
        val s = _s.value
        fun ms(v: Double?) = v?.let { "%.0f ms".format(it) } ?: "n/a"
        fun pct(r: ProbeResult?) = r?.let { "%.0f%%".format(it.lossPct) } ?: "n/a"
        return buildString {
            appendLine("GameBooster ${BuildConfig.VERSION_NAME} | Android API ${Build.VERSION.SDK_INT}")
            appendLine("Status: ${s.status} | auto-reconnect: ${s.autoReconnect}")
            appendLine("Game: ${s.selectedGame?.packageName ?: "-"} | Server: ${s.activeServer?.name ?: "-"} | DNS in tunnel: ${s.dns ?: "-"}")
            appendLine("Smart switch: ${s.smartSwitch} | overlay: ${s.overlayEnabled} | backups: ${s.backups.ifEmpty { listOf("-") }.joinToString()}")
            appendLine("Handshake age: ${s.handshakeAgeSec?.let { "${it}s" } ?: "none"}")
            appendLine("TCP connect to game host, direct:  ${ms(s.before?.avgMs)} jitter ${ms(s.before?.jitterMs)} loss ${pct(s.before)}")
            appendLine("TCP connect to game host, tunnel:  ${ms(s.live?.avgMs)} jitter ${ms(s.live?.jitterMs)} loss ${pct(s.live)}")
            s.servers.forEach { sv ->
                appendLine("Server ${sv.name}: you->gateway ${ms(s.serverRtts[sv.id]?.avgMs)}, gateway->game ${ms(s.gatewayToGame[sv.id]?.avgMs)}")
            }
            appendLine("--- log ---"); s.log.forEach { appendLine(it) }
        }
    }

    // ---------- connect / disconnect ----------
    fun toggle() { if (_s.value.connected || _s.value.reconnecting) disconnect() else connect() }

    fun onConsentResult(granted: Boolean) {
        _needConsent.value = null
        if (granted && pendingConnect) connect()
        else if (!granted) {
            pendingConnect = false
            upd { it.copy(error = "VPN permission was denied.", busy = false, status = "Disconnected") }
        }
    }

    /** Explicit server > per-game preference > catalog preference > automatic (null). */
    private fun preferredFor(game: GameProfile): Server? {
        val st = _s.value
        val id = st.gamePrefs[game.packageName] ?: game.preferredServerId
        return id?.let { i -> st.servers.firstOrNull { it.id == i } }
    }

    private fun connect() {
        val st = _s.value
        if (st.busy || st.connected || st.reconnecting) return          // duplicate-tunnel guard
        val game = st.selectedGame ?: return upd { it.copy(error = "Pick a game first.") }
        if (st.servers.isEmpty()) return upd { it.copy(error = "No servers available in the server directory.") }
        tunnel.consentIntent()?.let { pendingConnect = true; _needConsent.value = it; return }
        pendingConnect = false

        scope.launch {
            upd { it.copy(busy = true, error = null, status = "Measuring baseline…", live = null) }
            note("Connecting: ${game.displayName}")
            try {
                BoosterService.start(app).getOrElse { throw IllegalStateException("Could not start background connection service.", it) }
                probeAddr = Probe.resolve(game.probeHost)                       // system DNS, before the tunnel
                val before = Probe.run(probeAddr ?: game.probeHost, game.probePort)
                upd { it.copy(before = before, status = "Selecting server…") }
                val explicit = st.selectedServer
                // An explicit choice is honoured with no silent failover; otherwise try preference/ranking with failover.
                val preferred = explicit ?: preferredFor(game)?.takeUnless { health.isBlocked(it.id, System.currentTimeMillis()) }
                val server = establish(game, preferred, allowFailover = explicit == null,
                    maxAttempts = 3, firstDelayMs = 0, label = "Connecting")
                onEstablished(game, server)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                tunnel.disconnect(); store.clearLast(); BoosterService.stop(app); sessionStart = 0
                if (e is ApiException && e.code == 401) { forceSignOut(); return@launch }
                note("Failed: ${e.message}")
                upd { it.copy(busy = false, connected = false, status = "Disconnected", error = e.message ?: "Connection failed") }
            }
        }
    }

    private fun disconnect() {
        finishSession("user disconnected")
        monitorJob?.cancel(); reconnectJob?.cancel()
        scope.launch {
            tunnel.disconnect(); store.clearLast(); BoosterService.stop(app); sessionStart = 0
            upd { it.copy(connected = false, reconnecting = false, busy = false, status = "Disconnected",
                live = null, activeServer = null, handshakeAgeSec = null, dns = null) }
        }
    }

    /**
     * Register + bring the tunnel up + require a WireGuard handshake. On failure (or no handshake) optionally
     * fail over to the next-best server. Throws the last error when all attempts fail; 401 propagates at once.
     */
    private suspend fun establish(game: GameProfile, first: Server?, allowFailover: Boolean, maxAttempts: Int,
                                  firstDelayMs: Long, label: String): Server {
        val keys = store.deviceKeyPair()
        val servers = _s.value.servers
        val tried = HashSet<String>()
        var candidate = first
        var lastError: Exception? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            val server = candidate ?: run {
                val ranked = rankServers(servers, game)
                val eligibleId = health.firstEligible(ranked.map { it.id }, tried, System.currentTimeMillis())
                eligibleId?.let { id -> ranked.firstOrNull { it.id == id } }
            } ?: break
            val wait = if (attempt == 0) firstDelayMs else 1500L * (attempt + 1)
            if (wait > 0) delay(wait)
            upd { it.copy(status = "$label (${attempt + 1}/$maxAttempts) – ${server.name}") }
            var reason = "other"
            try {
                val reg = api.register(server, keys.publicKey.toBase64())
                tunnel.connect(WireGuardConfig(server, reg, keys, listOf(game.packageName, app.packageName)))
                if (!awaitHandshake(server)) {
                    tunnel.disconnect()
                    throw HandshakeException("No WireGuard handshake from ${server.name} within ${HANDSHAKE_TIMEOUT_MS / 1000}s. " +
                        "UDP ${server.wgPort} may be blocked by this network or the gateway firewall.")
                }
                upd { it.copy(dns = reg.dns) }
                note("Handshake OK with ${server.name}")
                health.recordSuccess(server.id)
                return server
            } catch (e: CancellationException) { throw e
            } catch (e: HandshakeException) { lastError = e; reason = "handshake"; note(e.message ?: "handshake failed")
            } catch (e: ApiException) {
                if (e.code == 401) throw e
                lastError = e; reason = "register"; note("${server.name}: ${e.message}")
            } catch (e: Exception) { lastError = e; note("${server.name}: ${e.message ?: e.javaClass.simpleName}") }
            health.recordFailure(server.id, System.currentTimeMillis())
            api.logConnection(server.id, game.packageName, false, reason)
            tried += server.id; attempt++; candidate = null
            if (!allowFailover) break
        }
        throw lastError ?: ApiException("No server available.")
    }

    private suspend fun awaitHandshake(server: Server): Boolean {
        val end = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            if (tunnel.handshakeAgeSec(server.publicKey) != null) return true
            delay(500)
        }
        return false
    }

    private suspend fun onEstablished(game: GameProfile, server: Server) {
        store.saveLast(game.packageName, server.id)
        if (sessionStart == 0L) sessionStart = System.currentTimeMillis()
        networkTransitionPolicy.reset(underlyingSignature())
        val rec = recorder
        if (rec == null) {
            policy.reset()
            recorder = SessionRecorder(sessionStart, game.packageName, game.displayName, server.name, _s.value.before)
        } else { rec.noteServer(server.name); policy.resetBaseline() }
        upd { it.copy(connected = true, reconnecting = false, busy = false, activeServer = server, status = "Connected") }
        startMonitor(game)
        updateBackups()
        api.registerDevice(server.id, store.deviceKeyPair().publicKey.toBase64())
        api.logConnection(server.id, game.packageName, true)
    }

    // ---------- server ranking ----------
    /** Gateway->game probe (measured by the gateway), cached 60 s to avoid duplicate requests. */
    private suspend fun gatewayToGame(server: Server, game: GameProfile): ProbeResult? {
        val key = "${server.id}|${game.probeHost}:${game.probePort}"
        gwCache[key]?.let { (t, r) -> if (System.currentTimeMillis() - t < 60_000) return r }
        val r = try { api.gatewayProbe(server, game.probeHost, game.probePort) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        gwCache[key] = System.currentTimeMillis() to r
        return r
    }

    private data class Measured(val server: Server, val own: ProbeResult, val gw: ProbeResult?, val cost: Double)

    /**
     * Phone->gateway + gateway->game for each server. With [bound] the phone->gateway probe is bound to the
     * physical network, so it works (and is honest) while our own tunnel is up.
     */
    private suspend fun measureServers(servers: List<Server>, game: GameProfile?, bound: Boolean): List<Measured> = coroutineScope {
        val net = if (bound) underlyingNetwork() else null
        val res = servers.map { s -> async {
            val own = Probe.run(s.host, s.probePort, 4, network = net)
            val gw = if (game != null && own.ok) gatewayToGame(s, game) else null
            Measured(s, own, gw, if (own.ok) score(own, gw, game != null) else PathScore.UNREACHABLE)
        } }.awaitAll()
        val ids = res.map { it.server.id }.toSet()
        upd { st -> st.copy(
            serverRtts = st.serverRtts + res.associate { it.server.id to it.own },
            gatewayToGame = st.gatewayToGame.filterKeys { it !in ids } +
                res.mapNotNull { m -> m.gw?.takeIf { it.ok }?.let { m.server.id to it } }.toMap()) }
        res
    }

    /**
     * Reachable servers first, then servers whose circuit breaker is not tripped, then by an ESTIMATE of path cost:
     * phone->gateway + gateway->game (+ loss/jitter penalty). An estimate, not an end-to-end measurement;
     * servers without a gateway->game number get a fixed penalty.
     */
    private suspend fun rankServers(servers: List<Server>, game: GameProfile? = null): List<Server> {
        val results = measureServers(servers, game, bound = false)
        val now = System.currentTimeMillis()
        return results.sortedWith(compareBy<Measured>({ !it.own.ok }, { health.isBlocked(it.server.id, now) }, { it.cost }))
            .map { it.server }
    }

    private fun score(own: ProbeResult, gw: ProbeResult?, wantGw: Boolean): Double {
        val gwPart = when {
            gw != null && gw.ok -> PathScore.cost(gw)
            wantGw -> UNKNOWN_GW_PENALTY_MS
            else -> 0.0
        }
        return PathScore.cost(own) + gwPart
    }

    /** Standby routes shown in the UI, derived from the latest measurements (no extra probing). */
    private fun updateBackups() {
        val st = _s.value
        val active = st.activeServer ?: return
        val now = System.currentTimeMillis()
        val names = st.servers.filter { it.id != active.id && st.serverRtts[it.id]?.ok == true }
            .sortedWith(compareBy<Server>({ health.isBlocked(it.id, now) }, { score(st.serverRtts.getValue(it.id), st.gatewayToGame[it.id], true) }))
            .take(3).map { it.name }
        upd { it.copy(backups = names) }
    }

    // ---------- smart switching ----------
    /** Look for a clearly better route while connected; switch only past the policy's margin. */
    private fun evaluateSwitch(game: GameProfile, reason: String) {
        if (switching) return
        switching = true
        scope.launch {
            try {
                val st = _s.value
                val active = st.activeServer
                if (!st.connected || st.reconnecting || active == null) return@launch
                if (underlyingNetwork() == null) return@launch       // can't probe candidates without going through our own tunnel
                policy.markEvaluated(System.currentTimeMillis())
                note("Smart switch: ${active.name} looks bad ($reason); checking alternatives")
                val now = System.currentTimeMillis()
                val measurements = measureServers(st.servers, game, bound = true)
                val activeRoute = measurements.firstOrNull { it.server.id == active.id && it.own.ok }
                val best = measurements.filter { it.server.id != active.id && it.own.ok && !health.isBlocked(it.server.id, now) }
                    .minByOrNull { it.cost }
                updateBackups()
                if (best == null) { note("Smart switch: no reachable alternative"); return@launch }
                if (_s.value.activeServer?.id != active.id || !_s.value.connected) return@launch   // state moved on meanwhile
                if (activeRoute == null) { note("Smart switch: could not measure the active route for comparison"); return@launch }
                if (policy.shouldSwitch(activeRoute.cost, best.cost)) {
                    note("Smart switch: ${active.name} -> ${best.server.name} (estimated cost %.0f -> %.0f)".format(Locale.US, activeRoute.cost, best.cost))
                    recorder?.noteSwitch()
                    policy.onSwitched(System.currentTimeMillis())
                    reconnect(game, immediate = true, target = best.server)
                } else note("Smart switch: staying on ${active.name}; ${best.server.name} is not clearly better")
            } finally { switching = false }
        }
    }

    // ---------- monitoring (cheap while hidden: liveness check every 5 s, no probes) ----------
    private fun startMonitor(game: GameProfile) {
        monitorJob?.cancel()
        val t0 = System.currentTimeMillis()
        var lastProbe = 0L
        var probing = false
        monitorJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val server = _s.value.activeServer
                val age = server?.let { tunnel.handshakeAgeSec(it.publicKey) }
                // Dead tunnel: service gone, or no handshake for 4 min after a 45 s grace period.
                // (Persistent keepalive makes WireGuard re-handshake roughly every 2 min while up.)
                val up = tunnel.status() == VpnEngineStatus.CONNECTED
                if (!up || (now - t0 > 45_000 && (age == null || age > 240))) {
                    onTunnelLost(game, if (!up) "VPN service stopped" else "no recent handshake")
                    return@launch
                }
                val overlayOn = overlayActive()
                val smartOn = _s.value.smartSwitch
                val showStats = uiVisible || overlayOn
                if (showStats) {
                    val (rx, tx) = tunnel.traffic()
                    upd { it.copy(durationSec = (now - sessionStart) / 1000, rxBytes = rx, txBytes = tx, handshakeAgeSec = age) }
                }
                // Probe cadence: 5 s with the overlay, 10 s with the UI open, 15 s for smart switching while hidden,
                // otherwise a light 3-sample probe every 60 s so session history still gets data.
                val every = when { overlayOn -> 5_000L; uiVisible -> 10_000L; smartOn -> 15_000L; else -> 60_000L }
                if (!probing && now - lastProbe >= every) {
                    probing = true; lastProbe = now
                    launch {
                        try {
                            val r = Probe.run(probeAddr ?: game.probeHost, game.probePort, if (showStats || smartOn) 5 else 3)
                            upd { it.copy(live = r) }
                            recorder?.addSample(r)
                            if (smartOn) policy.onSample(System.currentTimeMillis(), r)?.let { evaluateSwitch(game, it) }
                        } finally { probing = false }
                    }
                }
                delay(if (showStats) 1000 else 5000)
            }
        }
    }

    private fun onTunnelLost(game: GameProfile, reason: String, immediate: Boolean = false) {
        note("Tunnel lost: $reason")
        // a dead tunnel that is not just a network change counts against the server (circuit breaker)
        if (!immediate) _s.value.activeServer?.let { health.recordFailure(it.id, System.currentTimeMillis()) }
        if (_s.value.autoReconnect) { reconnect(game, immediate); return }
        monitorJob?.cancel()
        finishSession("connection lost")
        scope.launch {
            tunnel.disconnect(); store.clearLast(); BoosterService.stop(app); sessionStart = 0
            upd { it.copy(connected = false, status = "Disconnected", activeServer = null, live = null,
                error = "Connection lost ($reason). Auto-reconnect is off.") }
        }
    }

    // ---------- network change ----------
    /** Best non-VPN validated network (prefers Wi-Fi). The signature changes when the underlying link changes. */
    private fun underlyingPick(): Pair<Network, NetworkCapabilities>? = runCatching {
        val nets = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }.filter { (_, c) ->
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val pick = nets.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: nets.firstOrNull()
        pick
    }.getOrNull()

    private fun underlyingSignature(): String? = underlyingPick()?.let { (n, c) ->
        (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi:" else "other:") + n
    }

    private fun underlyingNetwork(): Network? = underlyingPick()?.first

    private fun registerNetworkCallback() {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetChange()
                override fun onLost(network: Network) = onNetChange()
            })
        }
    }

    private fun onNetChange() {
        netJob?.cancel()
        netJob = scope.launch {
            delay(2000)                                   // debounce flapping
            val reconnectRequested = networkTransitionPolicy.observe(underlyingSignature())
            val st = _s.value
            val game = st.selectedGame
            if (st.connected && !st.reconnecting && game != null && reconnectRequested) {
                onTunnelLost(game, "network changed", immediate = true)
            }
        }
    }

    // ---------- reconnect ----------
    /** [target] = a specific server to move to (smart switch); null = retry the current one, then fail over. */
    private fun reconnect(game: GameProfile, immediate: Boolean = false, target: Server? = null) {
        monitorJob?.cancel(); reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val st = _s.value
            val (rx, tx) = tunnel.traffic(); recorder?.addTraffic(rx, tx)       // counters restart with the next tunnel
            if (target == null) recorder?.noteReconnect()
            upd { it.copy(connected = false, reconnecting = true, live = null, handshakeAgeSec = null,
                status = if (target != null) "Switching route…" else "Reconnecting…") }
            tunnel.disconnect()
            try {
                // A server whose breaker is tripped is not retried first: go straight to the best backup.
                val first = target ?: st.activeServer?.takeUnless { health.isBlocked(it.id, System.currentTimeMillis()) }
                val server = establish(game, first, allowFailover = true, maxAttempts = 4,
                    firstDelayMs = if (immediate) 300L else 1500L, label = if (target != null) "Switching" else "Reconnecting")
                onEstablished(game, server)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (e is ApiException && e.code == 401) { forceSignOut(); return@launch }
                finishSession("reconnect failed")
                tunnel.disconnect(); store.clearLast(); BoosterService.stop(app); sessionStart = 0
                note("Reconnect failed: ${e.message}")
                upd { it.copy(reconnecting = false, connected = false, status = "Disconnected", activeServer = null,
                    error = "Connection lost and could not be restored. ${e.message ?: ""}".trim()) }
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) = scope.launch {
        upd { it.copy(busy = true, error = null) }
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.code == 401) forceSignOut(); upd { it.copy(error = e.message) } }
        catch (e: IllegalArgumentException) { upd { it.copy(error = "Invalid request.") } }
        catch (e: Exception) { upd { it.copy(error = e.message ?: "Something went wrong") } }
        finally { upd { it.copy(busy = false) } }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        const val UNKNOWN_GW_PENALTY_MS = 150.0
    }
}
