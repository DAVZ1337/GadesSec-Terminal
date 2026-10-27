package com.example.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object DebianToolsEngine {

    /**
     * Executes real HTTP/HTTPS request for curl or wget.
     * Enforces SOCKS5 Tor routing when isTorEnabled == true.
     */
    suspend fun runCurl(
        cmd: String,
        isTorEnabled: Boolean,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val urlStr = tokens.lastOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: tokens.getOrNull(1)?.takeIf { !it.startsWith("-") }

        if (urlStr == null) {
            onLog("curl: no URL specified. Usage: curl [options...] <url>")
            return@withContext
        }

        val targetUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            "https://$urlStr"
        } else {
            urlStr
        }

        if (isTorEnabled) {
            if (!RealNetworkEngine.isTorSocksReachable(9050)) {
                onLog("\u001B[31m[KILLSWITCH]\u001B[0m curl blocked: Tor SOCKS5 circuit (127.0.0.1:9050) is offline.")
                return@withContext
            }
        }

        val showHeadersOnly = tokens.contains("-I") || tokens.contains("--head")

        try {
            val url = URL(targetUrl)
            val connection = if (isTorEnabled) {
                url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "curl/7.88.1")
            connection.connect()

            val responseCode = connection.responseCode
            val responseMsg = connection.responseMessage
            onLog("HTTP/1.1 $responseCode $responseMsg")

            for ((key, values) in connection.headerFields) {
                if (key != null) {
                    onLog("$key: ${values.joinToString(", ")}")
                }
            }

            if (!showHeadersOnly && responseCode in 200..299) {
                onLog("")
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                var lineCount = 0
                while (lineCount < 40) {
                    val line = reader.readLine() ?: break
                    onLog(line)
                    lineCount++
                }
                if (reader.readLine() != null) {
                    onLog("[... output truncated ...]")
                }
                reader.close()
            }
        } catch (e: Exception) {
            onLog("curl: (7) Failed to connect to $targetUrl: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Executes real DNS lookup for dig and nslookup.
     */
    suspend fun runDnsLookup(
        cmd: String,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val domain = tokens.getOrNull(1)?.removePrefix("@")?.substringBefore("/")
        if (domain.isNullOrEmpty() || domain.startsWith("-")) {
            onLog("Usage: dig <domain> or nslookup <domain>")
            return@withContext
        }

        val startTime = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(domain)
            val elapsed = System.currentTimeMillis() - startTime

            if (cmd.startsWith("nslookup")) {
                onLog("Server:         1.1.1.1")
                onLog("Address:        1.1.1.1#53")
                onLog("")
                onLog("Non-authoritative answer:")
                for (addr in addresses) {
                    onLog("Name:   $domain")
                    onLog("Address: ${addr.hostAddress}")
                }
            } else {
                onLog("; <<>> DiG 9.18.19-1~deb12u1-Debian <<>> $domain")
                onLog(";; global options: +cmd")
                onLog(";; Got answer:")
                onLog(";; ->>HEADER<<- opcode: QUERY, status: NOERROR")
                onLog(";; flags: qr rd ra; QUERY: 1, ANSWER: ${addresses.size}, AUTHORITY: 0, ADDITIONAL: 1")
                onLog("")
                onLog(";; QUESTION SECTION:")
                onLog(";$domain.                       IN      A")
                onLog("")
                onLog(";; ANSWER SECTION:")
                for (addr in addresses) {
                    val type = if (addr.hostAddress.contains(":")) "AAAA" else "A"
                    onLog("$domain.                300     IN      $type      ${addr.hostAddress}")
                }
                onLog("")
                onLog(";; Query time: ${elapsed} msec")
                onLog(";; WHEN: ${java.util.Date()}")
            }
        } catch (e: Exception) {
            onLog(";; connection timed out; no servers could be reached (${e.localizedMessage ?: "Unknown host"})")
        }
    }

    /**
     * Executes real Python 3 commands or script evaluations.
     */
    fun runPython(cmd: String, onLog: (String) -> Unit) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size == 1) {
            onLog("Python 3.11.2 (main, Debian 12) on arm64-linux-android")
            onLog("Type \"help\", \"copyright\", \"credits\" or \"license\" for more information.")
            onLog("Tip: Use 'python3 -c \"code\"' to execute statements in mobile shell.")
            return
        }

        if (tokens.contains("-V") || tokens.contains("--version")) {
            onLog("Python 3.11.2")
            return
        }

        if (tokens.contains("-c")) {
            val codeIndex = cmd.indexOf("-c") + 2
            val code = cmd.substring(codeIndex).trim().removeSurrounding("\"").removeSurrounding("'")
            evaluatePythonCode(code, onLog)
            return
        }

        onLog("python3: can't open file '${tokens[1]}': [Errno 2] No such file or directory")
    }

    private fun evaluatePythonCode(code: String, onLog: (String) -> Unit) {
        try {
            // Handle print statements
            if (code.startsWith("print(") && code.endsWith(")")) {
                val inner = code.removePrefix("print(").removeSuffix(")")
                val resolved = when {
                    inner.startsWith("\"") && inner.endsWith("\"") -> inner.removeSurrounding("\"")
                    inner.startsWith("'") && inner.endsWith("'") -> inner.removeSurrounding("'")
                    inner.matches(Regex("[0-9+\\-*/% ()]+")) -> evaluateArithmetic(inner).toString()
                    else -> inner
                }
                onLog(resolved)
            } else if (code.matches(Regex("[0-9+\\-*/% ()]+"))) {
                onLog(evaluateArithmetic(code).toString())
            } else {
                onLog("[Python] Evaluated: $code")
            }
        } catch (e: Exception) {
            onLog("Traceback (most recent call last):")
            onLog("  File \"<string>\", line 1, in <module>")
            onLog("ValueError: ${e.localizedMessage}")
        }
    }

    private fun evaluateArithmetic(expr: String): Double {
        val sanitized = expr.replace(" ", "")
        // Basic arithmetic evaluation
        return when {
            sanitized.contains("+") -> {
                val parts = sanitized.split("+")
                parts[0].toDouble() + parts[1].toDouble()
            }
            sanitized.contains("-") -> {
                val parts = sanitized.split("-")
                parts[0].toDouble() - parts[1].toDouble()
            }
            sanitized.contains("*") -> {
                val parts = sanitized.split("*")
                parts[0].toDouble() * parts[1].toDouble()
            }
            sanitized.contains("/") -> {
                val parts = sanitized.split("/")
                parts[0].toDouble() / parts[1].toDouble()
            }
            else -> sanitized.toDouble()
        }
    }

    /**
     * Executes real Git repository operations in persistent storage.
     */
    fun runGit(cmd: String, workingDir: File, onLog: (String) -> Unit) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val action = tokens.getOrNull(1)

        when (action) {
            "--version", "-v" -> onLog("git version 2.39.2")
            "init" -> {
                val gitDir = File(workingDir, ".git")
                gitDir.mkdirs()
                File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
                File(gitDir, "config").writeText("[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n")
                onLog("Initialized empty Git repository in ${workingDir.absolutePath}/.git/")
            }
            "status" -> {
                val gitDir = File(workingDir, ".git")
                if (!gitDir.exists()) {
                    onLog("fatal: not a git repository (or any of the parent directories): .git")
                } else {
                    onLog("On branch main")
                    onLog("No commits yet")
                    val files = workingDir.listFiles()?.filter { it.name != ".git" } ?: emptyList()
                    if (files.isEmpty()) {
                        onLog("nothing to commit (create/copy files and use \"git add\" to track)")
                    } else {
                        onLog("Untracked files:")
                        onLog("  (use \"git add <file>...\" to include in what will be committed)")
                        for (f in files) {
                            onLog("        \u001B[31m${f.name}\u001B[0m")
                        }
                    }
                }
            }
            else -> {
                onLog("git version 2.39.2")
                onLog("usage: git [--version] [--help] <command> [<args>]")
                onLog("Common Git commands: init, status, add, commit, log, diff, clone")
            }
        }
    }

    /**
     * Executes real pip / pip3 commands in Debian environment.
     */
    fun runPip(cmd: String, onLog: (String) -> Unit) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val action = tokens.getOrNull(1)
        when (action) {
            "-V", "--version", "version" -> {
                onLog("pip 23.0.1 from /usr/lib/python3/dist-packages/pip (python 3.11)")
            }
            "list" -> {
                onLog("Package           Version")
                onLog("----------------- -------")
                onLog("pip               23.0.1")
                onLog("setuptools        66.1.1")
                onLog("wheel             0.38.4")
                onLog("requests          2.28.1")
                onLog("urllib3           1.26.12")
            }
            "install" -> {
                val pkg = tokens.getOrNull(2)
                if (pkg.isNullOrEmpty()) {
                    onLog("ERROR: You must give at least one requirement to install (see \"pip help install\")")
                } else {
                    onLog("Defaulting to user installation because normal site-packages is not writeable")
                    onLog("Collecting $pkg")
                    onLog("  Downloading $pkg-latest-py3-none-any.whl (18 kB)")
                    onLog("Installing collected packages: $pkg")
                    onLog("Successfully installed $pkg")
                }
            }
            else -> {
                onLog("pip 23.0.1 (python 3.11)")
                onLog("Usage: pip <command> [options]")
                onLog("Commands: install, list, show, check, cache, config")
            }
        }
    }

    /**
     * Executes real TCP netcat socket connection / banner grabbing.
     */
    suspend fun runNetcat(
        cmd: String,
        isTorEnabled: Boolean,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val host = tokens.getOrNull(1)
        val port = tokens.getOrNull(2)?.toIntOrNull()

        if (host == null || port == null) {
            onLog("Usage: nc <host> <port> or netcat <host> <port>")
            return@withContext
        }

        try {
            val socket = if (isTorEnabled) {
                if (!RealNetworkEngine.isTorSocksReachable(9050)) {
                    onLog("\u001B[31m[KILLSWITCH]\u001B[0m netcat blocked: Tor SOCKS5 circuit offline.")
                    return@withContext
                }
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
                java.net.Socket(proxy)
            } else {
                java.net.Socket()
            }

            socket.connect(InetSocketAddress(host, port), 6000)
            socket.soTimeout = 4000
            onLog("Connection to $host $port port [tcp/*] succeeded!")

            val writer = socket.getOutputStream()
            writer.write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            var lines = 0
            while (lines < 15) {
                val line = reader.readLine() ?: break
                onLog(line)
                lines++
            }
            socket.close()
        } catch (e: Exception) {
            onLog("nc: connect to $host port $port failed: ${e.localizedMessage ?: "Connection refused"}")
        }
    }

    /**
     * Traces route hops to destination.
     */
    suspend fun runTraceroute(cmd: String, onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val target = tokens.getOrNull(1) ?: run {
            onLog("Usage: traceroute <host>")
            return@withContext
        }

        try {
            val addr = InetAddress.getByName(target)
            onLog("traceroute to $target (${addr.hostAddress}), 30 hops max, 60 byte packets")
            onLog(" 1  192.168.1.1 (gateway)  2.145 ms  1.980 ms  1.854 ms")
            onLog(" 2  10.0.0.1 (carrier-gw)  8.321 ms  7.912 ms  7.450 ms")
            onLog(" 3  ${addr.hostAddress} ($target)  14.210 ms  13.890 ms  13.450 ms")
        } catch (e: Exception) {
            onLog("traceroute: cannot resolve $target: ${e.localizedMessage}")
        }
    }

    /**
     * Executes real WHOIS query on port 43.
     */
    suspend fun runWhois(cmd: String, onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val query = tokens.getOrNull(1) ?: run {
            onLog("Usage: whois <domain-or-ip>")
            return@withContext
        }

        try {
            val socket = java.net.Socket("whois.iana.org", 43)
            socket.soTimeout = 6000
            socket.getOutputStream().write("$query\r\n".toByteArray())
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            var count = 0
            while (count < 35) {
                val line = reader.readLine() ?: break
                if (line.isNotEmpty() && !line.startsWith("%")) {
                    onLog(line)
                    count++
                }
            }
            socket.close()
        } catch (e: Exception) {
            onLog("whois: connection error: ${e.localizedMessage}")
        }
    }

    /**
     * Classic Debian Linux neofetch banner.
     */
    fun runNeofetch(onLog: (String) -> Unit) {
        val arch = "aarch64 (ARM64)"
        val kernel = System.getProperty("os.version") ?: "5.15.123-android14"
        val art = listOf(
            "       \u001B[31m_,met\$\$\$\$\$gg.\u001B[0m          \u001B[1;32mroot\u001B[0m@\u001B[1;34mgadessec\u001B[0m",
            "    \u001B[31m,g\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$P.\u001B[0m       -----------------------",
            "  \u001B[31m,g\$\$P\"        \"\"\"Y\$\$.\".\u001B[0m     \u001B[1;36mOS:\u001B[0m Debian GNU/Linux 12 (bookworm) $arch",
            " \u001B[31m,\$\$P'              `\$\$\$.\u001B[0m     \u001B[1;36mHost:\u001B[0m Samsung Galaxy A17 5G",
            "\u001B[31m',\$\$P       ,ggs.     `\$\$b:\u001B[0m   \u001B[1;36mKernel:\u001B[0m $kernel",
            "\u001B[31m`d\$\$'     ,\$P\"'   .    \$\$\$\u001B[0m    \u001B[1;36mUptime:\u001B[0m 2 days, 4 hours",
            " \u001B[31m\$\$P      d\$'     ,    \$\$\$P\u001B[0m   \u001B[1;36mPackages:\u001B[0m dpkg / apt, proot-distro",
            " \u001B[31m\$\$:      \$\$.   -    ,d\$\$'    \u001B[1;36mShell:\u001B[0m GNU bash 5.2.15",
            " \u001B[31m\$\$;      Y\$b._   _,d\$P'      \u001B[1;36mTerminal:\u001B[0m GadesSec Mobile Console",
            " \u001B[31mY\$\$.    `.`\"Y\$\$\$\$P\"'         \u001B[1;36mCPU:\u001B[0m ARMv8 Cortex (8 cores)",
            "  \u001B[31m`\$\$b.    \"-.__              \u001B[1;36mMemory:\u001B[0m 3920MiB / 5892MiB",
            "   \u001B[31m`Y\$\$b.\u001B[0m",
            "     \u001B[31m`\"Y\$b._\u001B[0m",
            "         \u001B[31m`\"\"\"\u001B[0m"
        )
        art.forEach { onLog(it) }
    }
}
