package com.gamebooster.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamebooster.app.core.HistorySummary
import com.gamebooster.app.core.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

private val Neon = Color(0xFF45F0C2)
private val Bg = Color(0xFF090D15)
private val CardBg = Color(0xFF121A28)
private val CardRaised = Color(0xFF1A2638)
private val Muted = Color(0xFF97A6BA)
private val Border = Color(0xFF263449)
private val Violet = Color(0xFF8B7CFF)

@Composable
fun BoosterTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Neon, onPrimary = Color(0xFF06120F), secondary = Violet,
        background = Bg, surface = CardBg, surfaceVariant = CardRaised, onSurface = Color(0xFFF4F7FC),
        onSurfaceVariant = Muted, onBackground = Color(0xFFF4F7FC), error = Color(0xFFFF7188)),
    content = { Surface(Modifier.fillMaxSize(), color = Bg) { content() } }
)

// ============================== Login ==============================
@Composable
fun LoginScreen(st: UiState, vm: MainViewModel) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val canSubmit = !st.busy && st.configured && email.isNotBlank() && pw.length >= 6
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(color = CardBg, shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF172B37), CardBg, Color(0xFF17172D)))).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Color(0xFF173B3A), shape = RoundedCornerShape(17.dp), modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Neon, modifier = Modifier.padding(13.dp))
                }
                Text("PLAY ON A\nBETTER ROUTE", fontSize = 26.sp, color = Color.White, lineHeight = 31.sp, letterSpacing = .5.sp)
                Text("Sign in to manage your game routes and sync session summaries.", fontSize = 13.sp, color = Muted)
                if (!st.configured) Banner(
                    "This build is missing its public Supabase URL/key. Use the configured private APK; never put a service-role key in the app.", true
                )
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button({ vm.signIn(email, pw) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("SIGN IN", letterSpacing = 1.sp)
                }
                OutlinedButton({ vm.signUp(email, pw) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("CREATE ACCOUNT", letterSpacing = 1.sp)
                }
                st.error?.let { Banner(it, true) }
                st.info?.let { Banner(it, false) }
            }
        }
    }
}

// ============================== Shell ==============================
@Composable
fun MainScreen(st: UiState, vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = Color(0xFF173B3A), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Neon, modifier = Modifier.padding(9.dp).size(23.dp))
                }
                Column {
                    Text("GAMEBOOSTER", fontSize = 16.sp, color = Color.White, letterSpacing = 1.6.sp)
                    Text("SMART GAME TUNNEL", fontSize = 9.sp, color = Muted, letterSpacing = 1.2.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (st.connected) "PROTECTED" else if (st.reconnecting) "SWITCHING" else "OFFLINE",
                    if (st.connected) Neon else if (st.reconnecting) Color(0xFFFFC857) else Muted)
                TextButton({ vm.signOut() }) { Text("Sign out", color = Muted, fontSize = 12.sp) }
            }
        }
        HorizontalDivider(color = Border.copy(alpha = .7f))
        AnimatedVisibility(visible = st.error != null) {
            Surface(color = Color(0xFF351C2A), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.error ?: "", color = Color(0xFFFF9BA9), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 5.dp))
                    TextButton({ vm.dismissError() }) { Text("Close", color = Color(0xFFFFB5C0), fontSize = 12.sp) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ConnectTab(st, vm)
                1 -> GamesTab(st, vm, onUse = { vm.selectGame(it); tab = 0 })
                2 -> HistoryTab(st, vm)
                else -> DiagnosticsTab(st, vm)
            }
        }
        NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
            val entries = listOf("Home", "Games", "History", "Settings")
            val icons = listOf(Icons.Filled.Bolt, Icons.Filled.SportsEsports, Icons.Filled.History, Icons.Filled.Settings)
            entries.forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(icons[index], contentDescription = label) },
                    label = { Text(label, fontSize = 10.sp) }, alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Neon, selectedTextColor = Neon,
                        indicatorColor = Color(0xFF173B3A), unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }
}

// ============================== Connect ==============================
@Composable
private fun ConnectTab(st: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val locked = st.connected || st.reconnecting
    val canBoost = st.selectedGame != null && st.servers.isNotEmpty()
    val statusColor by animateColorAsState(when {
        st.connected -> Neon
        st.reconnecting -> Color(0xFFFFC857)
        canBoost -> Neon
        else -> Color(0xFFFFC857)
    }, label = "status")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = CardBg, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF172B37), CardBg, Color(0xFF17172D)))).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SECURE GAME ROUTE", fontSize = 10.sp, color = Muted, letterSpacing = 1.5.sp)
                        Text(if (st.connected) "Tunnel active" else if (st.reconnecting) "Optimizing route" else if (canBoost) "Ready when you are" else "Setup needed",
                            fontSize = 20.sp, color = statusColor)
                    }
                    StatusPill(if (st.connected) "LIVE" else if (st.reconnecting) "SWITCHING" else if (canBoost) "READY" else "SETUP",
                        if (canBoost || locked) statusColor else Color(0xFFFFC857))
                }
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(176.dp)) {
                        drawCircle(Neon.copy(alpha = if (locked) .20f else .10f), radius = size.minDimension * .49f, style = Stroke(2.dp.toPx()))
                        drawCircle(Violet.copy(alpha = .12f), radius = size.minDimension * .39f, style = Stroke(1.dp.toPx()))
                    }
                    Button(onClick = { vm.toggle() }, enabled = (!st.busy || st.reconnecting) && (canBoost || locked),
                        modifier = Modifier.size(126.dp), shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = if (locked) Color(0xFF573040) else Neon,
                            contentColor = if (locked) Color.White else Color(0xFF06120F), disabledContainerColor = Color(0xFF273344))) {
                        if (st.busy && !st.reconnecting) CircularProgressIndicator(Modifier.size(27.dp), color = Color.White, strokeWidth = 2.dp)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(28.dp))
                            Text(if (locked) "STOP" else if (canBoost) "BOOST" else "SETUP", fontSize = 13.sp, letterSpacing = 1.sp)
                        }
                    }
                }
                Text(if (st.connected) "${st.activeServer?.name ?: "Secure route"}  •  ${fmtDur(st.durationSec)}"
                    else if (st.selectedGame == null) "Choose a game to start your secure route"
                    else if (st.servers.isEmpty()) "Game selected • waiting for an authorized live gateway"
                    else "Ready to protect ${st.selectedGame.displayName}",
                    fontSize = 13.sp, color = Muted)
                val g = st.selectedGame
                if (st.connected && g != null) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton({ launchGame(ctx, g.packageName) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon)) { Text("LAUNCH ${g.displayName.uppercase(Locale.getDefault())}") }
                }
            }
        }

        Panel {
            SectionHeading("Your route", "Choose a game and preferred server")
            Spacer(Modifier.height(12.dp))
            Picker("Game", st.selectedGame?.displayName ?: if (st.games.isEmpty()) "No supported games installed" else "Select",
                st.games.map { it.displayName }, !locked) { vm.selectGame(st.games[it]) }
            Spacer(Modifier.height(8.dp))
            val pref = st.selectedGame?.let { g -> st.servers.firstOrNull { it.id == st.gamePrefs[g.packageName] }?.name }
            ServerBrowser(st, vm, locked, pref)
        }

        Panel {
            SectionHeading("Live network quality", "Measured TCP connection quality • not in-game ping")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("LATENCY", ms(st.live?.avgMs), "direct ${ms(st.before?.avgMs)}", Neon, Modifier.weight(1f))
                MetricTile("JITTER", ms(st.live?.jitterMs), "path variation", Violet, Modifier.weight(1f))
                MetricTile("LOSS", pct(st.live), "packet estimate", if ((st.live?.lossPct ?: 0.0) < 5) Neon else Color(0xFFFFC857), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            MetricRow("Direct / tunnel TCP", "${ms(st.before?.avgMs)} direct", "${ms(st.live?.avgMs)} route")
            Text("TCP connect time to one resolved IP of the game's probe host. Not an ICMP ping and not in-game latency. " +
                "Results vary; no improvement is guaranteed.", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }

        if (st.connected || st.reconnecting) Panel {
            SectionHeading("Adaptive route", "Automatic failover and smart switching")
            Spacer(Modifier.height(6.dp))
            MetricRow("Active", st.activeServer?.name ?: "—", "")
            MetricRow("Backups", if (st.backups.isEmpty()) "not ranked yet" else st.backups.joinToString(" › "), "")
            MetricRow("Smart switch", if (st.smartSwitch) "on" else "off", "")
            Text("Backups come from the latest server measurements (Diagnostics → Test servers refreshes them). " +
                "A server that fails twice is skipped for 5 minutes.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
        }

        Panel {
            SectionHeading("Session traffic", "Usage for the current connection")
            Spacer(Modifier.height(8.dp))
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
        Surface(color = Color(0xFF211F32), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Violet.copy(alpha = .3f)),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("SETUP REQUIRED", Color(0xFFFFC857))
                Text("No live gateways available", color = Color.White, fontSize = 15.sp)
                Text("A secure tunnel can start only after an enabled server is published and its authorized GameBooster gateway passes a health check.",
                    color = Muted, fontSize = 11.sp)
            }
        }
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
        SectionHeading("Your games", "Choose which apps use the secure game route")
        if (st.games.isEmpty()) Panel {
            StatusPill("NO GAMES ADDED", Violet)
            Spacer(Modifier.height(8.dp))
            Text("Build your game list", fontSize = 16.sp)
            Text("Refresh the catalog or add an installed app manually. Per-app routing keeps other apps outside the game tunnel.", color = Muted, fontSize = 12.sp)
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
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Diagnostics & settings", "Inspect the tunnel, tune failover and manage the overlay")
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
            SettingRow("Smart server switching",
                "While connected, watch the live path and move to a clearly better server if it stays bad. " +
                "A switch interrupts the tunnel for a few seconds and probes more often (uses more battery).",
                st.smartSwitch) { vm.setSmartSwitch(it) }
            SettingRow("In-game overlay",
                "Floating ping / jitter / loss readout over your game. Needs the \"Display over other apps\" permission.",
                st.overlayEnabled) { on ->
                vm.setOverlay(on)
                if (on && !Settings.canDrawOverlays(ctx)) runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            if (st.overlayEnabled && !Settings.canDrawOverlays(ctx))
                Text("Permission not granted yet: the overlay stays hidden until you allow it.", fontSize = 11.sp, color = Color(0xFFFFC857))
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

// ============================== History ==============================
@Composable
private fun HistoryTab(st: UiState, vm: MainViewModel) {
    val sum = remember(st.history) { HistorySummary.of(st.history) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Session insights", "A local record of your recent game routes")
        Panel {
            Text("Overview", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            MetricRow("Sessions", "${sum.sessions}", "")
            MetricRow("Total time", fmtDur(sum.totalSec), "")
            MetricRow("TCP connect vs direct", sum.avgLatencyDeltaPct?.let { "%+.0f%%".format(it) } ?: "—", "")
            MetricRow("Route switches", "${sum.switches}", "")
            MetricRow("Reconnects", "${sum.reconnects}", "")
            MetricRow("Data", fmtBytes(sum.rxBytes + sum.txBytes), "")
            Text("Negative = lower TCP connect time through the tunnel than direct. This is not in-game ping, results vary, " +
                "and a session with few samples is a rough picture. Sessions are stored on this device (last 200) and " +
                "a numbers-only summary is uploaded when you are signed in.",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
        }
        if (st.history.isEmpty()) Panel { Text("No sessions yet. Connect to a game; each session is saved when you disconnect.", color = Color.Gray) }
        st.history.take(30).forEach { SessionCard(it) }
        if (st.history.isNotEmpty()) OutlinedButton({ vm.clearHistory() }) { Text("Clear history") }
    }
}

@Composable
private fun SessionCard(r: SessionRecord) {
    val whenText = remember(r.startMs) { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(r.startMs)) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(r.gameName, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(whenText, fontSize = 12.sp, color = Color.Gray)
        }
        Text("${fmtDur(r.durationSec)} • ${r.servers.joinToString(" › ")}", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        MetricRow("", "Direct", "Tunnel")
        MetricRow("TCP connect", ms(r.beforeAvgMs), ms(r.avgMs))
        MetricRow("Best / p95", ms(r.minMs), ms(r.p95Ms))
        MetricRow("Jitter", "", ms(r.avgJitterMs))
        MetricRow("Loss", r.beforeLossPct?.let { "%.0f%%".format(it) } ?: "—", r.avgLossPct?.let { "%.1f%%".format(it) } ?: "—")
        Sparkline(r.series, Modifier.padding(vertical = 6.dp))
        Text("${r.samples} samples • ${r.switches} switches • ${r.reconnects} reconnects • ${fmtBytes(r.rxBytes + r.txBytes)} • ended: ${r.endReason}",
            fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Neon, style = Stroke(width = 3f))
    }
}

// ============================== Shared bits ==============================
@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = .10f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = .35f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("●", fontSize = 7.sp, color = color)
            Text(label, fontSize = 9.sp, color = color, letterSpacing = .8.sp)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 16.sp, color = Color.White)
        Text(subtitle, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun MetricTile(label: String, value: String, hint: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CardRaised, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 9.sp, color = Muted, letterSpacing = .8.sp, maxLines = 1)
            Text(value, fontSize = 17.sp, color = color, maxLines = 1)
            Text(hint, fontSize = 9.sp, color = Muted, maxLines = 1)
        }
    }
}

@Composable private fun SettingRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(desc, fontSize = 11.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) =
    Surface(color = CardBg, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), content = content)
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
