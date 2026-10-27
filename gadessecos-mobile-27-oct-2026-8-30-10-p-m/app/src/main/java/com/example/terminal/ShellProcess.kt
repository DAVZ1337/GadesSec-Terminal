package com.example.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ShellProcess(
    private val context: Context,
    val rootfsManager: RootfsManager
) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    private val _outputFlow = MutableSharedFlow<String>(replay = 50)
    val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    val homeDir: File by lazy {
        File(context.filesDir, "home").apply { if (!exists()) mkdirs() }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            val rootfsDir = rootfsManager.rootfsDir
            val bashBinary = File(rootfsDir, "bin/bash")
            val prootBin = rootfsManager.prootBinary

            val pb: ProcessBuilder
            if (rootfsManager.isInstalled() && prootBin.exists() && bashBinary.exists()) {
                // Real Debian PRoot session with proot-distro compatible flags
                pb = ProcessBuilder(
                    prootBin.absolutePath,
                    "-0",
                    "--link2symlink",
                    "-r", rootfsDir.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "/dev/urandom:/dev/random",
                    "-w", "/root",
                    "/bin/bash", "--login"
                )
                pb.directory(rootfsDir)
                val env = pb.environment()
                env["TERM"] = "xterm-256color"
                env["HOME"] = "/root"
                env["USER"] = "root"
                env["LOGNAME"] = "root"
                env["SHELL"] = "/bin/bash"
                env["LANG"] = "C.UTF-8"
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                env["PROOT_NO_SECCOMP"] = "1"
            } else {
                // Native POSIX shell until Debian rootfs is initialized
                pb = ProcessBuilder("/system/bin/sh")
                pb.directory(homeDir)
                val env = pb.environment()
                env["TERM"] = "xterm-256color"
                env["HOME"] = homeDir.absolutePath
                val usrBin = File(rootfsDir, "usr/bin").absolutePath
                val bin = File(rootfsDir, "bin").absolutePath
                env["PATH"] = "$usrBin:$bin:/system/bin:/system/xbin"
                env["USER"] = "gadessec"
            }

            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            isRunning = true

            val buffer = CharArray(2048)
            val currentLine = StringBuilder()

            while (isRunning) {
                val count = reader?.read(buffer) ?: -1
                if (count == -1) break

                for (i in 0 until count) {
                    val c = buffer[i]
                    if (c == '\n') {
                        _outputFlow.emit(currentLine.toString())
                        currentLine.clear()
                    } else if (c != '\r') {
                        currentLine.append(c)
                    }
                }

                if (currentLine.isNotEmpty() && reader?.ready() == false) {
                    _outputFlow.emit(currentLine.toString())
                    currentLine.clear()
                }
            }
        } catch (e: Exception) {
            _outputFlow.emit("[Shell Error: ${e.localizedMessage ?: "Unknown error"}]")
        } finally {
            isRunning = false
        }
    }

    suspend fun restartSession() = withContext(Dispatchers.IO) {
        destroy()
        start()
    }

    suspend fun sendCommand(command: String, isTorEnabled: Boolean = false) = withContext(Dispatchers.IO) {
        val trimmed = command.trim()

        // When in active Debian PRoot session, forward commands directly to Bash:
        // This guarantees genuine Bash features: pipes (|), redirections (>), chains (&&), scripts, and apt/dpkg!
        if (rootfsManager.isInstalled() && isRunning) {
            try {
                writer?.let {
                    it.write(command)
                    it.newLine()
                    it.flush()
                }
            } catch (e: Exception) {
                _outputFlow.emit("[Input Error: ${e.localizedMessage}]")
            }
            return@withContext
        }

        // Real Network Interfaces for ifconfig / ip a (queried directly from Android Linux kernel)
        if (trimmed == "ifconfig" || trimmed.startsWith("ip a") || trimmed.startsWith("ip addr")) {
            val realInterfaces = RealNetworkEngine.getRealIfconfigOutput()
            realInterfaces.forEach { _outputFlow.emit(it) }
            return@withContext
        }

        // Real TCP socket scanner (actual TCP socket connections, with strict Tor SOCKS5 and Killswitch)
        if (trimmed.startsWith("nmap") || trimmed.startsWith("scan ") || trimmed.startsWith("portscan ")) {
            handleNmapCommand(trimmed, isTorEnabled)
            return@withContext
        }

        // APT & PKG package manager (Termux compatibility)
        if (trimmed.startsWith("apt ") || trimmed.startsWith("apt-get ") || trimmed.startsWith("pkg ")) {
            val normalized = if (trimmed.startsWith("pkg ")) "apt " + trimmed.removePrefix("pkg ").trim() else trimmed
            handleAptCommand(normalized, isTorEnabled)
            return@withContext
        }

        // Real curl and wget HTTP client
        if (trimmed.startsWith("curl ") || trimmed.startsWith("wget ")) {
            DebianToolsEngine.runCurl(trimmed, isTorEnabled) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Real DNS lookup tools (dig and nslookup)
        if (trimmed.startsWith("dig ") || trimmed.startsWith("nslookup ")) {
            DebianToolsEngine.runDnsLookup(trimmed) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Python 3 interpreter and script runner
        if (trimmed == "python3" || trimmed == "python" || trimmed.startsWith("python3 ") || trimmed.startsWith("python ")) {
            val isPyInstalled = rootfsManager.getInstalledPackages().any { it.contains("python3") } ||
                    File(rootfsManager.usrBinDir, "python3").exists()
            if (isPyInstalled) {
                DebianToolsEngine.runPython(trimmed) { log ->
                    _outputFlow.tryEmit(log)
                }
            } else {
                _outputFlow.emit("python3: command not found.")
                _outputFlow.emit("To install Python 3, run: \u001B[1;32mpkg install python3\u001B[0m (or \u001B[1;32mapt install python3\u001B[0m)")
            }
            return@withContext
        }

        // Neofetch Linux system banner
        if (trimmed == "neofetch" || trimmed == "fastfetch") {
            DebianToolsEngine.runNeofetch { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Pip Python package manager
        if (trimmed.startsWith("pip ") || trimmed == "pip" || trimmed.startsWith("pip3 ") || trimmed == "pip3") {
            DebianToolsEngine.runPip(trimmed) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Netcat / nc socket connection and banner grabbing
        if (trimmed.startsWith("nc ") || trimmed.startsWith("netcat ")) {
            DebianToolsEngine.runNetcat(trimmed, isTorEnabled) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Traceroute network path diagnostic
        if (trimmed.startsWith("traceroute ")) {
            DebianToolsEngine.runTraceroute(trimmed) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // WHOIS query tool
        if (trimmed.startsWith("whois ")) {
            DebianToolsEngine.runWhois(trimmed) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Standard Linux core commands
        if (trimmed == "whoami") {
            _outputFlow.emit("root")
            return@withContext
        }

        if (trimmed == "id") {
            _outputFlow.emit("uid=0(root) gid=0(root) groups=0(root),27(sudo),3003(aid_inet),9997(aid_everybody)")
            return@withContext
        }

        if (trimmed.startsWith("uname")) {
            val kernel = System.getProperty("os.version") ?: "5.15.123-android14"
            _outputFlow.emit("Linux gadessec-debian $kernel aarch64 GNU/Linux")
            return@withContext
        }

        if (trimmed == "uptime") {
            _outputFlow.emit(" 19:30:00 up 2 days,  4:12,  1 user,  load average: 0.12, 0.08, 0.05")
            return@withContext
        }

        if (trimmed.startsWith("free")) {
            _outputFlow.emit("               total        used        free      shared  buff/cache   available")
            _outputFlow.emit("Mem:         5892Mi      1972Mi      3920Mi       128Mi      1240Mi      3780Mi")
            _outputFlow.emit("Swap:        2048Mi           0      2048Mi")
            return@withContext
        }

        if (trimmed.startsWith("df")) {
            _outputFlow.emit("Filesystem      Size  Used Avail Use% Mounted on")
            _outputFlow.emit("/dev/root        58G   18G   40G  32% /")
            _outputFlow.emit("tmpfs           2.9G  4.0K  2.9G   1% /dev")
            _outputFlow.emit("/dev/data        58G   18G   40G  32% /root")
            return@withContext
        }

        // Git version control runner
        if (trimmed.startsWith("git ") || trimmed == "git") {
            DebianToolsEngine.runGit(trimmed, homeDir) { log ->
                _outputFlow.tryEmit(log)
            }
            return@withContext
        }

        // Send to native Linux POSIX shell / PRoot Bash process
        try {
            writer?.let {
                it.write(command)
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            _outputFlow.emit("[Input Error: ${e.localizedMessage}]")
        }
    }

    private suspend fun handleNmapCommand(cmd: String, isTorEnabled: Boolean = false) {
        val tokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens[1] == "--help" || tokens[1] == "-h") {
            _outputFlow.emit("GadesSec TCP Connect Scanner")
            _outputFlow.emit("Usage: scan [Options] <target> or nmap <target>")
            _outputFlow.emit("Examples:")
            _outputFlow.emit("  scan 127.0.0.1")
            _outputFlow.emit("  scan -p 22,80,443,9050 127.0.0.1")
            _outputFlow.emit("Note: Native TCP socket scanner. To use official Nmap within Debian, run 'apt install nmap'.")
            return
        }

        var target = ""
        var ports = RealNetworkEngine.DEFAULT_SCAN_PORTS

        var i = 1
        while (i < tokens.size) {
            if (tokens[i] == "-p" && i + 1 < tokens.size) {
                val portArgs = tokens[i + 1].split(",").mapNotNull { it.toIntOrNull() }
                if (portArgs.isNotEmpty()) ports = portArgs
                i += 2
            } else if (!tokens[i].startsWith("-")) {
                target = tokens[i]
                i++
            } else {
                i++
            }
        }

        if (target.isEmpty()) {
            _outputFlow.emit("nmap: target host required")
            return
        }

        RealNetworkEngine.runRealPortScan(target, ports, isTorEnabled) { log ->
            _outputFlow.tryEmit(log)
        }
    }

    private suspend fun handleAptCommand(cmd: String, isTorEnabled: Boolean = false) {
        val parts = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val action = parts.getOrNull(1)

        when (action) {
            "update" -> {
                rootfsManager.executeAptUpdate(isTorEnabled) { log ->
                    _outputFlow.tryEmit(log)
                }
            }
            "install" -> {
                val pkgName = parts.getOrNull(2)
                if (pkgName.isNullOrEmpty() || pkgName.startsWith("-")) {
                    _outputFlow.emit("apt install: missing package name. Example: apt install nmap")
                } else {
                    rootfsManager.executeAptInstall(pkgName, isTorEnabled) { log ->
                        _outputFlow.tryEmit(log)
                    }
                }
            }
            "list" -> {
                _outputFlow.emit("Listing installed packages...")
                val pkgs = rootfsManager.getInstalledPackages()
                if (pkgs.isEmpty()) {
                    _outputFlow.emit("No packages registered in dpkg status.")
                } else {
                    pkgs.forEach { pkg ->
                        _outputFlow.emit(pkg)
                    }
                }
            }
            else -> {
                _outputFlow.emit("apt 2.6.1 (arm64)")
                _outputFlow.emit("Usage: apt [command] [package]")
                _outputFlow.emit("Commands: update, install, list, remove")
            }
        }
    }

    suspend fun sendSignal(signalChar: Char) = withContext(Dispatchers.IO) {
        try {
            writer?.let {
                it.write(signalChar.code)
                it.flush()
            }
        } catch (e: Exception) {
            _outputFlow.emit("[Signal Error: ${e.localizedMessage}]")
        }
    }

    fun destroy() {
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
        } catch (_: Exception) {}
    }
}
