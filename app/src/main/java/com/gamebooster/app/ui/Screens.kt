package com.gamebooster.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamebooster.app.data.GameProfile
import com.gamebooster.app.data.InstalledApp
import com.gamebooster.app.data.ProbeResult

private val Neon = Color(0xFF00E5A8)
private val Bg = Color(0xFF0E1117)
private val CardBg = Color(0xFF181D27)

@Composable
fun BoosterTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Neon, onPrimary = Color.Black, background = Bg,
        surface = CardBg, onSurface = Color.White, onBackground = Color.White, error = Color(0xFFFF6B6B)),
    content = { Surface(Modifier.fillMaxSize(), color = Bg) { content() } }
)

// ============================== Login ==============================
@Composable
fun LoginScreen(st: UiState, vm: MainViewModel) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val canSubmit = !st.busy && st.configured && email.isNotBlank() && pw.length >= 6
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Game Booster", fontSize = 30.sp, color = Neon)
        Spacer(Modifier.height(20.dp))
        if (!st.configured) Banner(
            "This APK lacks Supabase build settings. Download the configured APK from the private GitHub release after Actions succeeds. Use only the public anon/publishable key—never a service-role key.",
            true
        )
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password (6+ chars)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.signIn(email, pw) }, enabled = canSubmit) { Text("Sign in") }
            OutlinedButton({ vm.signUp(email, pw) }, enabled = canSubmit) { Text("Create account") }
        }
        st.error?.let { Banner(it, true) }
        st.info?.let { Banner(it, false) }
    }
}

// ============================== Shell ==============================
@Composable
fun MainScreen(st: UiState, vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Game Booster", fontSize = 22.sp, color = Neon)
            TextButton({ vm.signOut() }) { Text("Sign out") }
        }
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Neon) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Connect") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Games") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Diagnostics") })
        }
        AnimatedVisibility(visible = st.error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(st.error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton({ vm.dismissError() }) { Text("Dismiss") }
            }
        }
        when (tab) {
            0 -> ConnectTab(st, vm)
            1 -> GamesTab(st, vm, onUse = { vm.selectGame(it); tab = 0 })
            else -> DiagnosticsTab(st, vm)
        }
    }
}

// ============================== Connect ==============================
@Composable
private fun ConnectTab(st: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val statusColor by animateColorAsState(if (st.connected) Neon else if (st.reconnecting) Color(0xFFFFC857) else Color.Gray, label = "status")
    val locked = st.connected || st.reconnecting
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text(st.status, fontSize = 18.sp, color = statusColor)
            if (st.connected) Text("${st.activeServer?.name ?: ""} • ${fmtDur(st.durationSec)}", color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Button({ vm.toggle() }, enabled = (!st.busy || st.reconnecting) && st.selectedGame != null,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (st.busy && !st.reconnecting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text(if (locked) "DISCONNECT" else "CONNECT")
            }
            val g = st.selectedGame
            if (st.connected && g != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ launchGame(ctx, g.packageName) }, modifier = Modifier.fillMaxWidth()) { Text("Launch ${g.displayName}") }
            }
        }

        Panel {
            Picker("Game", st.selectedGame?.displayName ?: if (st.games.isEmpty()) "No supported games installed" else "Select",
                st.games.map { it.displayName }, !locked) { vm.selectGame(st.games[it]) }
            Spacer(Modifier.height(8.dp))
            val pref = st.selectedGame?.let { g -> st.servers.firstOrNull { it.id == st.gamePrefs[g.packageName] }?.name }
            ServerBrowser(st, vm, locked, pref)
        }

        Panel {
            Text("Network quality", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            MetricRow("", "Before", "Now")
            MetricRow("TCP connect", ms(st.before?.avgMs), ms(st.live?.avgMs))
            MetricRow("Jitter", ms(st.before?.jitterMs), ms(st.live?.jitterMs))
            MetricRow("Packet loss", pct(st.before), pct(st.live))
            Text("TCP connect time to one resolved IP of the game's probe host. Not an ICMP ping and not in-game latency. " +
                "Results vary; no improvement is guaranteed.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
        }

        Panel {
            Text("Traffic", fontSize = 16.sp)
            MetricRow("Downloaded", fmtBytes(st.rxBytes), "")
            MetricRow("Uploaded", fmtBytes(st.txBytes), "")
        }
    }
}

private enum class ServerOrder { BEST_PING, LOWEST_LOSS, MANUAL }

@Composable
private fun ServerBrowser(st: UiState, vm: MainViewModel, locked: Boolean, preferredName: String?) {
    var order by remember { mutableStateOf(ServerOrder.BEST_PING) }
    val servers = when (order) {
        ServerOrder.BEST_PING -> st.servers.sortedWith(compareBy({ st.serverRtts[it.id]?.avgMs == null }, { st.serverRtts[it.id]?.avgMs ?: Double.MAX_VALUE }))
        ServerOrder.LOWEST_LOSS -> st.servers.sortedWith(compareBy({ st.serverRtts[it.id]?.lossPct == null }, { st.serverRtts[it.id]?.lossPct ?: 100.0 }, { st.serverRtts[it.id]?.avgMs ?: Double.MAX_VALUE }))
        ServerOrder.MANUAL -> st.servers
    }
    Text("Server", fontSize = 12.sp, color = Color.Gray)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(selected = order == ServerOrder.BEST_PING, onClick = { order = ServerOrder.BEST_PING }, label = { Text("Best ping") })
        FilterChip(selected = order == ServerOrder.LOWEST_LOSS, onClick = { order = ServerOrder.LOWEST_LOSS }, label = { Text("Lowest loss") })
        FilterChip(selected = order == ServerOrder.MANUAL, onClick = { order = ServerOrder.MANUAL }, label = { Text("Manual") })
    }
    Spacer(Modifier.height(8.dp))
    ServerChoice(
        name = "Automatic route",
        detail = preferredName?.let { "Preferred: $it • measured best path" } ?: "Measures ping and packet loss before connecting",
        ping = null,
        loss = null,
        selected = st.selectedServer == null,
        enabled = !locked,
        onClick = { vm.selectServer(null) }
    )
    if (servers.isEmpty()) {
        Text("No servers configured yet. Add enabled rows to public.servers in Supabase.", color = Color.Gray, fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp))
    } else {
        servers.forEach { server ->
            val probe = st.serverRtts[server.id]
            ServerChoice(
                name = server.name,
                detail = listOfNotNull(server.country, server.region ?: server.location, server.provider).joinToString(" • ").ifBlank { server.location },
                ping = probe?.avgMs,
                loss = probe?.lossPct,
                selected = st.selectedServer?.id == server.id,
                enabled = !locked,
                onClick = { vm.selectServer(server) }
            )
        }
    }
    OutlinedButton({ vm.testServers() }, enabled = !locked && !st.busy && st.servers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (st.serverRtts.isEmpty()) "Measure server ping" else "Refresh ping measurements")
    }
}

@Composable
private fun ServerChoice(name: String, detail: String, ping: Double?, loss: Double?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val quality = when {
        ping == null -> Color.Gray
        ping < 80 && (loss ?: 0.0) < 5 -> Neon
        ping < 160 && (loss ?: 0.0) < 10 -> Color(0xFFFFC857)
        else -> Color(0xFFFF6B6B)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) Color(0xFF203A3A) else Color(0xFF202532),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = if (selected) Neon else Color.White, fontSize = 15.sp)
                Text(detail, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(ping?.let { "${it.formatPing()} ms" } ?: "— ms", color = quality, fontSize = 16.sp)
                Text(loss?.let { "${it.formatLoss()}% loss" } ?: "not measured", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

// ============================== Games ==============================
@Composable
private fun GamesTab(st: UiState, vm: MainViewModel, onUse: (GameProfile) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<InstalledApp?>(null) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    val locked = st.connected || st.reconnecting
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (st.games.isEmpty()) Panel {
            Text("No supported games installed.", fontSize = 16.sp)
            Text("Install a game from the catalog, or add any installed app manually below.", color = Color.Gray, fontSize = 12.sp)
        }
        st.games.forEach { g ->
            val selected = st.selectedGame?.packageName == g.packageName
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.displayName, fontSize = 16.sp, color = if (selected) Neon else Color.White)
                        Text(g.packageName + if (g.manual) " • manual" else "", fontSize = 11.sp, color = Color.Gray)
                    }
                    if (selected) Text(if (st.connected) "Connected" else "Selected", color = Neon, fontSize = 12.sp)
                    else TextButton({ onUse(g) }, enabled = !locked) { Text("Use") }
                }
                Spacer(Modifier.height(8.dp))
                val prefName = st.servers.firstOrNull { it.id == st.gamePrefs[g.packageName] }?.name
                Picker("Preferred server", prefName ?: "Automatic", listOf("Automatic") + st.servers.map { it.name }, true) { i ->
                    vm.setGameServer(g, if (i == 0) null else st.servers[i - 1])
                }
                if (g.manual) TextButton({ vm.removeManualGame(g) }, enabled = !st.busy && !(locked && selected)) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ adding = !adding; if (adding) vm.loadInstalledApps() }) { Text(if (adding) "Cancel" else "Add game manually") }
            OutlinedButton({ vm.refreshCatalog() }, enabled = !st.busy) { Text("Refresh") }
        }
        if (adding) Panel {
            Picker("App", pick?.label ?: "Select installed app", st.installedApps.map { it.label }, true) { pick = st.installedApps[it] }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(host, { host = it }, label = { Text("Game server host (for measurement)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("TCP port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button({
                pick?.let { vm.addManualGame(it, host.trim(), port.toIntOrNull() ?: 0) }
                adding = false; pick = null; host = ""
            }, enabled = pick != null && host.isNotBlank() && !st.busy) { Text("Save") }
            Text("Routing is per app: only this app's traffic uses the tunnel. The host is only used to measure TCP connect time.",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ============================== Diagnostics ==============================
@Composable
private fun DiagnosticsTab(st: UiState, vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("Connection", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            MetricRow("Status", st.status, "")
            MetricRow("Gateway", st.activeServer?.name ?: "—", "")
            MetricRow("Handshake", when {
                !st.connected -> "—"
                st.handshakeAgeSec == null -> "none yet"
                else -> "OK, ${st.handshakeAgeSec}s ago"
            }, "")
            MetricRow("Tunnel DNS", st.dns ?: "—", "")
            MetricRow("Routing", st.selectedGame?.let { "per-app: ${it.displayName}" } ?: "—", "")
            MetricRow("IPv6", "dropped, not tunnelled", "")
        }

        Panel {
            Text("Servers", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            MetricRow("", "You→gw", "gw→game")
            st.servers.forEach { s ->
                MetricRow(s.name, ms(st.serverRtts[s.id]?.avgMs), ms(st.gatewayToGame[s.id]?.avgMs))
            }
            Text("Both are TCP connect times; gw→game is measured by the gateway for the selected game's host. " +
                "Automatic selection uses their sum as an estimate, not an end-to-end measurement.",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
            OutlinedButton({ vm.testServers() }, enabled = !st.busy && st.servers.isNotEmpty()) { Text("Test servers") }
        }

        Panel {
            Text("Settings", fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-reconnect")
                    Text("Reconnect and fail over when the tunnel drops or the network changes", fontSize = 11.sp, color = Color.Gray)
                }
                Switch(checked = st.autoReconnect, onCheckedChange = { vm.setAutoReconnect(it) })
            }
        }

        Panel {
            Text("Event log", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            if (st.log.isEmpty()) Text("No events yet.", color = Color.Gray, fontSize = 12.sp)
            st.log.asReversed().take(15).forEach { Text(it, fontSize = 11.sp, color = Color.LightGray) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ clipboard.setText(AnnotatedString(vm.report())) }) { Text("Copy report") }
            Text("The report has no tokens, keys or email address.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ============================== Shared bits ==============================
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) =
    Surface(color = CardBg, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }

@Composable private fun Banner(text: String, error: Boolean) =
    Text(text, color = if (error) MaterialTheme.colorScheme.error else Neon, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))

@Composable private fun MetricRow(label: String, a: String, b: String) =
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1.4f), color = Color.Gray)
        Text(a, Modifier.weight(1.2f)); Text(b, Modifier.weight(1f), color = Neon)
    }

@Composable private fun Picker(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, fontSize = 12.sp, color = Color.Gray)
    Box {
        OutlinedButton({ open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(value, maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

private fun launchGame(ctx: Context, pkg: String) {
    runCatching {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun ms(v: Double?) = v?.let { "%.0f ms".format(it) } ?: "—"
private fun pct(r: ProbeResult?) = r?.let { "%.0f%%".format(it.lossPct) } ?: "—"
private fun Double.formatPing() = "%.0f".format(this)
private fun Double.formatLoss() = "%.1f".format(this)
private fun fmtDur(s: Long) = "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
private fun fmtBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.0f KB".format(b / 1024.0)
}
