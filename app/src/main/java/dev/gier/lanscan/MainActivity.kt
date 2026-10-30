package dev.gier.lanscan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7DD3FC),
                    onPrimary = Color(0xFF06202E),
                    background = Color(0xFF0B1220),
                    surface = Color(0xFF0B1220),
                    surfaceVariant = Color(0xFF16223A),
                )
            ) {
                LanScanApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScanApp() {
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<Device>() }
    var scanning by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableStateOf(0) }
    var expandedIp by remember { mutableStateOf<String?>(null) }
    var prefix by remember { mutableStateOf(Scanner.subnetPrefix(Scanner.localIp())) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Basti LAN Scan") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Subnetz:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    modifier = Modifier.width(150.dp),
                    singleLine = true,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "eigene IP: ${Scanner.localIp() ?: "keine"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!scanning) {
                        scanning = true
                        scannedCount = 0
                        devices.clear()
                        scope.launch {
                            Scanner.scan(
                                prefix,
                                onDevice = { devices.add(it) },
                                onProgress = { scannedCount = it },
                            )
                            scanning = false
                        }
                    }
                },
            ) {
                Text(if (scanning) "Scan läuft…" else "Netzwerk scannen")
            }
            Spacer(Modifier.height(12.dp))
            if (scanning) {
                LinearProgressIndicator(
                    progress = { scannedCount / 254f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "$scannedCount / 254 geprüft · ${devices.size} Geräte gefunden",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!scanning && scannedCount > 0 && devices.isEmpty()) {
                Text("Keine aktiven Geräte gefunden.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(devices, key = { it.ip }) { d ->
                    DeviceCard(
                        d,
                        expanded = expandedIp == d.ip,
                        onToggle = {
                            expandedIp = if (expandedIp == d.ip) null else d.ip
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceCard(d: Device, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(0xFF4ADE80), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(d.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                d.pingMs?.let { Text("$it ms", style = MaterialTheme.typography.bodySmall) }
            }
            d.hostname?.let { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (d.openPorts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ctx = LocalContext.current
                    d.openPorts.sorted().forEach { p ->
                        val url = if (p == 443) "https://${d.ip}:$p" else "http://${d.ip}:$p"
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            shape = CircleShape,
                        ) {
                            Text(
                                "$p",
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clickable {
                                        try {
                                            ctx.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                            )
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(
                                                ctx,
                                                "Kein Browser gefunden",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "alive · Ping ${d.pingMs ?: "?"} ms · Ports: ${d.openPorts.sorted().joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
                d.mac?.let {
                    Text(
                        "MAC: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
