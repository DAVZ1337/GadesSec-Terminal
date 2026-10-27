package com.example.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RealNetworkEngine {

    private val COMMON_SERVICES = mapOf(
        21 to "ftp",
        22 to "ssh",
        23 to "telnet",
        25 to "smtp",
        53 to "domain",
        80 to "http",
        110 to "pop3",
        143 to "imap",
        443 to "https",
        445 to "microsoft-ds",
        8080 to "http-proxy",
        8443 to "https-alt",
        9050 to "tor-socks",
        9051 to "tor-control"
    )

    val DEFAULT_SCAN_PORTS = listOf(21, 22, 23, 25, 53, 80, 110, 143, 443, 445, 8080, 8443, 9050, 9051)

    /**
     * Checks if local Tor SOCKS5 daemon is active AND responds to RFC 1928 SOCKS5 handshake.
     * Prevents false positives from non-SOCKS5 open ports.
     */
    fun isTorSocksReachable(port: Int = 9050): Boolean {
        return try {
            val socket = Socket()
            socket.soTimeout = 800
            socket.connect(InetSocketAddress("127.0.0.1", port), 600)

            val out: OutputStream = socket.getOutputStream()
            val inStream: InputStream = socket.getInputStream()

            // Send SOCKS5 handshake: VER=5, NMETHODS=1, METHOD=0 (No Auth)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()

            val reply = ByteArray(2)
            val readBytes = inStream.read(reply)
            socket.close()

            // Verify SOCKS5 response: VER=5, METHOD=0
            readBytes == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads genuine network interfaces directly from Android's Linux network stack.
     * Formats output identically to standard Linux ifconfig.
     */
    fun getRealIfconfigOutput(): List<String> {
        val lines = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            if (interfaces.isEmpty()) {
                return listOf("lo: flags=73<UP,LOOPBACK,RUNNING>  mtu 65536", "        inet 127.0.0.1  netmask 255.0.0.0")
            }

            for (netIf in interfaces) {
                val flags = buildString {
                    append("<")
                    if (netIf.isUp) append("UP,")
                    if (netIf.isLoopback) append("LOOPBACK,")
                    if (netIf.isPointToPoint) append("POINTOPOINT,")
                    if (netIf.supportsMulticast()) append("MULTICAST,")
                    append("RUNNING>")
                }.replace(",>", ">")

                lines.add("${netIf.name}: flags=${if (netIf.isUp) 4163 else 4098}$flags  mtu ${netIf.mtu}")

                for (addr in netIf.interfaceAddresses) {
                    val inetAddr = addr.address
                    if (inetAddr is Inet4Address) {
                        val ip = inetAddr.hostAddress
                        val prefix = addr.networkPrefixLength
                        val mask = prefixLengthToSubnet(prefix.toInt())
                        val broadcast = addr.broadcast?.hostAddress ?: "0.0.0.0"
                        lines.add("        inet $ip  netmask $mask  broadcast $broadcast")
                    } else {
                        val ip6 = inetAddr.hostAddress?.substringBefore("%")
                        lines.add("        inet6 $ip6  prefixlen ${addr.networkPrefixLength}  scopeid 0x20<link>")
                    }
                }

                val mac = netIf.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: "00:00:00:00:00:00"
                lines.add("        ether $mac  txqueuelen 1000  (${if (netIf.isLoopback) "Local Loopback" else "Ethernet"})")
                lines.add("")
            }
        } catch (e: Exception) {
            lines.add("lo: flags=73<UP,LOOPBACK,RUNNING>  mtu 65536")
            lines.add("        inet 127.0.0.1  netmask 255.0.0.0")
        }
        return lines
    }

    private fun prefixLengthToSubnet(prefix: Int): String {
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return "${(mask shr 24) and 0xff}.${(mask shr 16) and 0xff}.${(mask shr 8) and 0xff}.${mask and 0xff}"
    }

    /**
     * Executes real TCP connect port scan on target host.
     * ZERO DNS LEAKS: When Tor is enabled, host resolution is strictly delegated to Tor SOCKS5
     * using InetSocketAddress.createUnresolved. Never resolves DNS via local resolver when Tor is active.
     */
    suspend fun runRealPortScan(
        target: String,
        portsToScan: List<Int> = DEFAULT_SCAN_PORTS,
        isTorEnabled: Boolean = false,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US)
        onLog("Starting GadesSec TCP Connect Scanner at ${dateFormat.format(Date())}")

        // Strict Fail-Closed Killswitch enforcement
        var proxy: Proxy? = null
        if (isTorEnabled) {
            if (!isTorSocksReachable(9050)) {
                onLog("\u001B[31m[KILLSWITCH ENGAGED]\u001B[0m Direct network egress blocked.")
                onLog("Cause: Tor SOCKS5 daemon (127.0.0.1:9050) is offline or failed protocol handshake.")
                onLog("Safeguard: Connection aborted by terminal killswitch.")
                return@withContext
            } else {
                proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
                onLog("\u001B[32m[TOR ACTIVE]\u001B[0m Routing scan through SOCKS5 127.0.0.1:9050 (Remote DNS resolution via proxy)")
            }
        }

        if (isTorEnabled) {
            onLog("Scan report for $target [Remote Tor SOCKS5 resolution]")
        } else {
            try {
                val startTime = System.currentTimeMillis()
                val addr = InetAddress.getByName(target)
                val latency = (System.currentTimeMillis() - startTime) / 1000.0
                onLog("Scan report for $target (${addr.hostAddress})")
                onLog("Host is up (${"%.3f".format(latency)}s latency).")
            } catch (e: Exception) {
                onLog("Failed to resolve \"$target\": ${e.localizedMessage ?: "Unknown host"}")
                return@withContext
            }
        }

        onLog("PORT     STATE SERVICE")
        val openPorts = mutableListOf<Pair<Int, String>>()
        var closedCount = 0

        for (port in portsToScan) {
            val socket = if (proxy != null) Socket(proxy) else Socket()
            try {
                // When Tor is enabled, use createUnresolved to delegate DNS resolution exclusively to Tor
                val endpoint = if (proxy != null) {
                    InetSocketAddress.createUnresolved(target, port)
                } else {
                    InetSocketAddress(InetAddress.getByName(target), port)
                }

                socket.connect(endpoint, 1000)
                val service = COMMON_SERVICES[port] ?: "unknown"
                openPorts.add(port to service)
                val portStr = "$port/tcp".padEnd(9)
                onLog("\u001B[32m$portStr open  $service\u001B[0m")
            } catch (_: Exception) {
                closedCount++
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        if (closedCount > 0) {
            onLog("Not shown: $closedCount closed tcp ports")
        }
        if (openPorts.isEmpty()) {
            onLog("All ${portsToScan.size} scanned ports on $target are closed or filtered.")
        }
        onLog("Scan completed: 1 host scanned.")
    }
}
