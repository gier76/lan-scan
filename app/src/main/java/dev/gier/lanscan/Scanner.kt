package dev.gier.lanscan

import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class Device(
    val ip: String,
    var alive: Boolean = false,
    var pingMs: Int? = null,
    var hostname: String? = null,
    var mac: String? = null,
    val openPorts: MutableSet<Int> = mutableSetOf(),
)

/**
 * LAN-Scanner: Ping-Sweep ueber das aktuelle Subnetz, ARP-Tabelle,
 * TCP-Port-Probing auf bekannte Dienste und Reverse-DNS fuer Hostnamen.
 * Kein Tracking, keine Werbung, keine Server — alles bleibt im Netzwerk.
 */
object Scanner {
    val COMMON_PORTS = listOf(22, 53, 80, 443, 1883, 5000, 8080, 8123, 9584)

    fun localIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress &&
                        addr is java.net.Inet4Address &&
                        addr.isSiteLocalAddress
                    ) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun subnetPrefix(ip: String?): String {
        val safe = ip ?: "10.0.2.15"
        val parts = safe.split(".")
        if (parts.size != 4) return "10.0.2."
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun readArp(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").bufferedReader().forEachLine { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4 &&
                    p[0].matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                    p[3].matches(Regex("[0-9a-fA-F:]{17}"))
                ) {
                    out[p[0]] = p[3].uppercase()
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private suspend fun ping(ip: String): Int? {
        var proc: Process? = null
        return try {
            withTimeoutOrNull(2500) {
                proc = ProcessBuilder("/system/bin/ping", "-c", "1", ip)
                    .redirectErrorStream(true)
                    .start()
                val text = proc!!.inputStream.bufferedReader().readText()
                val code = proc!!.waitFor()
                if (code == 0) {
                    Regex("time[=<]\\s*([\\d.]+)\\s*ms")
                        .find(text)?.groupValues?.get(1)?.toFloat()?.toInt() ?: 0
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            proc?.destroy()
        }
    }

    private fun probePort(ip: String, port: Int): Boolean {
        return try {
            val sock = Socket()
            sock.connect(java.net.InetSocketAddress(ip, port), 250)
            sock.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun reverseDns(ip: String): String? {
        return try {
            val name = InetAddress.getByName(ip).getCanonicalHostName()
            if (name != ip && !name.startsWith("10.0.2")) name else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scan(prefix: String, onDevice: (Device) -> Unit, onProgress: (Int) -> Unit) {
        val arp = readArp()
        val sem = Semaphore(40)
        withContext(Dispatchers.IO) {
            coroutineScope {
                repeat(254) { i ->
                    val ip = prefix + (i + 1)
                    launch {
                        sem.withPermit {
                            val dev = Device(ip, mac = arp[ip])
                            dev.pingMs = ping(ip)
                            for (port in COMMON_PORTS) {
                                if (probePort(ip, port)) dev.openPorts.add(port)
                            }
                            dev.alive = dev.pingMs != null || dev.openPorts.isNotEmpty()
                            if (dev.alive) {
                                dev.hostname = reverseDns(ip)
                                onDevice(dev)
                            }
                            onProgress(i + 1)
                        }
                    }
                }
            }
        }
    }
}
