package com.example.steamstream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

fun openMoonlight(ctx: Context) {
    val intent = ctx.packageManager.getLaunchIntentForPackage("com.limelight")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.limelight"))
    ctx.startActivity(intent)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var mac by remember { mutableStateOf(prefs.getString("mac", "") ?: "") }
    var setup by remember { mutableStateOf(host.isBlank() || token.isBlank()) }

    Surface(Modifier.fillMaxSize()) {
        if (setup) {
            SetupScreen(host, token, mac) { h, t, m ->
                prefs.edit().putString("host", h).putString("token", t).putString("mac", m).apply()
                host = h; token = t; mac = m; setup = false
            }
        } else {
            val api = remember(host, token) { HostApi(host, token) }
            LibraryScreen(api, mac,
                onMac = { m -> mac = m; prefs.edit().putString("mac", m).apply() },
                onSettings = { setup = true })
        }
    }
}

@Composable
fun SetupScreen(h0: String, t0: String, m0: String, onSave: (String, String, String) -> Unit) {
    var h by remember { mutableStateOf(h0) }
    var t by remember { mutableStateOf(t0) }
    var m by remember { mutableStateOf(m0) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Connect to your PC", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the address and token shown in the SteamStream Host window. Use the Tailscale IP to connect from anywhere.")
        OutlinedTextField(h, { h = it }, label = { Text("Host (e.g. 100.64.0.5)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(t, { t = it }, label = { Text("Token") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(m, { m = it }, label = { Text("PC MAC address (optional, auto-filled)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(h.trim(), t.trim(), m.trim()) }, enabled = h.isNotBlank() && t.isNotBlank()) {
            Text("Save")
        }
    }
}

@Composable
fun LibraryScreen(api: HostApi, mac: String, onMac: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Status?>(null) }
    var games by remember { mutableStateOf<List<Game>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    suspend fun loadLibrary() {
        runCatching { withContext(Dispatchers.IO) { api.library() } }
            .onSuccess { games = it; loaded = true }
            .onFailure { msg = "Library: ${it.message}" }
    }

    LaunchedEffect(api) {
        while (true) {
            val s = withContext(Dispatchers.IO) { runCatching { api.status() }.getOrNull() }
            status = s
            if (s != null) {
                if (s.mac.isNotBlank() && s.mac != mac) onMac(s.mac)
                if (!loaded) loadLibrary()
            }
            delay(10_000)
        }
    }

    fun run(label: String, block: () -> Unit, after: (() -> Unit)? = null) {
        scope.launch {
            msg = "$label…"
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            if (r.isSuccess) { msg = "$label ✓"; after?.invoke() } else msg = "Failed: ${r.exceptionOrNull()?.message}"
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                val s = status
                Text(
                    when {
                        s == null -> "● PC offline"
                        !s.steam -> "● ${s.hostname} online (Steam not running)"
                        !s.sunshine -> "● ${s.hostname} online (Sunshine not running)"
                        else -> "● ${s.hostname} ready"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { scope.launch { loadLibrary() } }) { Text("Refresh") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        if (status == null) {
            Button(onClick = { run("Sending wake signal", { sendWol(mac) }) }, enabled = mac.isNotBlank()) {
                Text("Wake PC")
            }
            if (mac.isBlank()) Text("Connect once while the PC is on so the app learns its MAC address.",
                style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(query, { query = it }, label = { Text("Search") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)

        LazyColumn(Modifier.weight(1f)) {
            items(games.filter { it.name.contains(query, ignoreCase = true) }, key = { it.appid }) { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, Modifier.weight(1f))
                    if (g.installed) {
                        Button(onClick = {
                            run("Launching ${g.name}", { api.launch(g.appid) }, { openMoonlight(ctx) })
                        }) { Text("Play") }
                        TextButton(onClick = { run("Uninstall prompt", { api.uninstall(g.appid) }) }) { Text("Remove") }
                    } else {
                        OutlinedButton(onClick = {
                            run("Installing ${g.name}", { api.install(g.appid) })
                        }) { Text("Install") }
                    }
                }
            }
        }
    }
}
