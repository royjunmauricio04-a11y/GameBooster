package com.gamebooster.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun LoginScreen(st: UiState, vm: MainViewModel) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val canSubmit = !st.busy && st.configured && email.isNotBlank() && pw.length >= 6
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Game Booster", fontSize = 30.sp, color = Neon)
        Spacer(Modifier.height(20.dp))
        if (!st.configured) Banner("Backend not configured yet. Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties and rebuild.", true)
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

@Composable
fun Dashboard(st: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Game Booster", fontSize = 22.sp, color = Neon)
            TextButton({ vm.signOut() }) { Text("Sign out") }
        }
        st.error?.let { Banner(it, true) }

        Panel {
            Text(st.status, fontSize = 18.sp, color = if (st.connected) Neon else Color.Gray)
            if (st.connected) Text("${st.activeServer?.name ?: ""} • ${fmtDur(st.durationSec)}", color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            Button({ vm.toggle() }, enabled = (!st.busy || st.reconnecting) && st.selectedGame != null,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (st.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text(if (st.connected || st.reconnecting) "DISCONNECT" else "CONNECT")
            }
        }

        Panel {
            Picker("Game", st.selectedGame?.displayName ?: if (st.games.isEmpty()) "No supported games installed" else "Select",
                st.games.map { it.displayName }, !st.connected && !st.reconnecting) { vm.selectGame(st.games[it]) }
            Spacer(Modifier.height(8.dp))
            val rtt = st.serverRtts
            Picker("Server", st.selectedServer?.name ?: "Automatic (lowest measured latency)",
                listOf("Automatic") + st.servers.map { s ->
                    "${s.name} — ${s.location}" + (rtt[s.id]?.avgMs?.let { " (${"%.0f".format(it)} ms)" } ?: "")
                }, !st.connected && !st.reconnecting) { vm.selectServer(if (it == 0) null else st.servers[it - 1]) }
        }

        Panel {
            Text("Network quality", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            MetricRow("", "Before", "Now")
            MetricRow("Latency", ms(st.before?.avgMs), ms(st.live?.avgMs))
            MetricRow("Jitter", ms(st.before?.jitterMs), ms(st.live?.jitterMs))
            MetricRow("Packet loss", pct(st.before), pct(st.live))
            Text("Measured as TCP connect time to the game's probe host. Results vary; no improvement is guaranteed.",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
        }

        Panel {
            Text("Traffic", fontSize = 16.sp)
            MetricRow("Downloaded", fmtBytes(st.rxBytes), "")
            MetricRow("Uploaded", fmtBytes(st.txBytes), "")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.testServers() }, enabled = !st.busy) { Text("Test servers") }
            OutlinedButton({ vm.refreshCatalog() }, enabled = !st.busy) { Text("Refresh") }
        }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) =
    Surface(color = CardBg, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }

@Composable private fun Banner(text: String, error: Boolean) =
    Text(text, color = if (error) MaterialTheme.colorScheme.error else Neon, fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 8.dp))

@Composable private fun MetricRow(label: String, a: String, b: String) =
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1.4f), color = Color.Gray)
        Text(a, Modifier.weight(1f)); Text(b, Modifier.weight(1f), color = Neon)
    }

@Composable private fun Picker(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, fontSize = 12.sp, color = Color.Gray)
    Box {
        OutlinedButton({ open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(value, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

private fun ms(v: Double?) = v?.let { "%.0f ms".format(it) } ?: "—"
private fun pct(r: ProbeResult?) = r?.let { "%.0f%%".format(it.lossPct) } ?: "—"
private fun fmtDur(s: Long) = "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
private fun fmtBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.0f KB".format(b / 1024.0)
}
