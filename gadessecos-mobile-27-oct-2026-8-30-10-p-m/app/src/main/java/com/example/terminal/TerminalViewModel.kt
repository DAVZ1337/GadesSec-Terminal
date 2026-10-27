package com.example.terminal

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.TerminalLine
import com.example.model.TextSpan
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenGlow
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val currentInput: String = "",
    val prompt: String = "gadessec@debian:~$ ",
    val isCtrlActive: Boolean = false,
    val isAltActive: Boolean = false,
    val isTorEnabled: Boolean = false,
    val isKillswitchEngaged: Boolean = false,
    val isDebianReady: Boolean = false,
    val isDownloadingDebian: Boolean = false,
    val debianDownloadProgress: Float = 0f,
    val showLimitationsDialog: Boolean = false,
    val architecture: String = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "aarch64"
)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val rootfsManager = RootfsManager(application)
    private val shellProcess = ShellProcess(application, rootfsManager)
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        renderWelcomeBanner()
        val installed = rootfsManager.isInstalled()
        _uiState.update {
            it.copy(
                isDebianReady = installed,
                prompt = if (installed) "root@gadessec:~# " else "gadessec@debian:~$ "
            )
        }

        viewModelScope.launch {
            launch {
                shellProcess.outputFlow.collect { rawLine ->
                    appendRawLine(rawLine)
                }
            }
            shellProcess.start()
        }

        // Auto-bootstrap Debian environment on startup if not yet installed
        if (!installed) {
            triggerDebianDownload()
        }
    }

    private fun renderWelcomeBanner() {
        val kernel = System.getProperty("os.version") ?: "Linux"
        val bannerLines = listOf(
            TerminalLine.banner("   ___           _          ___       ", TerminalGreenGlow),
            TerminalLine.banner("  / __|__ _ __ _| |___ ___ / __| ___  ", TerminalGreen),
            TerminalLine.banner(" | (_ / _` / _` | -_)_-< |__ \\/ _ \\ ", TerminalGreen),
            TerminalLine.banner("  \\___\\__,_\\__,_\\___/__/ |___/\\___/ ", TerminalGreenGlow),
            TerminalLine.banner("    == GADESSEC TERMINAL ==         ", TerminalCyan),
            TerminalLine(listOf(TextSpan(" -------------------------------------", TextMuted))),
            TerminalLine(listOf(
                TextSpan(" Welcome to GadesSec Terminal! Real Linux shell on Android.", TextSecondary)
            )),
            TerminalLine(listOf(
                TextSpan(" • Package Manager: ", TextSecondary),
                TextSpan("pkg install <pkg>", TerminalGreen),
                TextSpan(" | ", TextMuted),
                TextSpan("apt install <pkg>", TerminalGreen)
            )),
            TerminalLine(listOf(
                TextSpan(" • Tool Suite: ", TextSecondary),
                TextSpan("python3, pip, git, nmap, nc, curl, wget, nano, vim, gcc, make", TerminalCyan)
            )),
            TerminalLine(listOf(
                TextSpan(" • System Info: ", TextSecondary),
                TextSpan("neofetch, uname -a, whoami, uptime, free -h, df -h, ifconfig", TerminalAmber)
            )),
            TerminalLine(listOf(
                TextSpan(" • Setup: ", TextSecondary),
                TextSpan("Auto-configured on startup (or type ", TextSecondary),
                TextSpan("debian-setup", TerminalGreen),
                TextSpan(" to reinstall)", TextSecondary)
            )),
            TerminalLine(listOf(
                TextSpan(" • Clipboard: ", TextSecondary),
                TextSpan("Long-press text to copy, tap [PASTE] key to paste", TextPrimary)
            )),
            TerminalLine(listOf(TextSpan(" -------------------------------------", TextMuted))),
            TerminalLine(emptyList())
        )

        _uiState.update { it.copy(lines = bannerLines) }
    }

    fun onInputChange(newText: String) {
        _uiState.update { it.copy(currentInput = newText) }
    }

    fun onPaste(pastedText: String) {
        _uiState.update { it.copy(currentInput = it.currentInput + pastedText) }
    }

    fun toggleCtrl() {
        _uiState.update { it.copy(isCtrlActive = !it.isCtrlActive) }
    }

    fun toggleAlt() {
        _uiState.update { it.copy(isAltActive = !it.isAltActive) }
    }

    fun toggleLimitationsDialog(show: Boolean) {
        _uiState.update { it.copy(showLimitationsDialog = show) }
    }

    fun toggleTor() {
        val newState = !_uiState.value.isTorEnabled
        if (newState) {
            val isReachable = RealNetworkEngine.isTorSocksReachable(9050)
            _uiState.update {
                it.copy(
                    isTorEnabled = true,
                    isKillswitchEngaged = true
                )
            }
            if (isReachable) {
                appendRawLine("[\u001B[32mTOR SOCKS5 ACTIVE\u001B[0m] Proxy verified on 127.0.0.1:9050. Killswitch armed.")
            } else {
                appendRawLine("[\u001B[31mKILLSWITCH ARMED\u001B[0m] Tor SOCKS5 daemon on 127.0.0.1:9050 is offline.")
                appendRawLine("[\u001B[33mNOTICE\u001B[0m] Terminal network egress is paused until Tor proxy starts.")
            }
        } else {
            _uiState.update {
                it.copy(
                    isTorEnabled = false,
                    isKillswitchEngaged = false
                )
            }
            appendRawLine("[\u001B[32mDIRECT NETWORK ACTIVE\u001B[0m] High-speed direct Internet enabled (Termux standard).")
        }
    }

    fun triggerDebianDownload() {
        if (_uiState.value.isDownloadingDebian) return
        _uiState.update { it.copy(isDownloadingDebian = true, debianDownloadProgress = 0.05f) }
        appendLine(TerminalLine.plain("Starting Debian rootfs download and PRoot installation...", TerminalGreen))

        viewModelScope.launch {
            val success = rootfsManager.downloadOfficialDebianRootfs { progress, statusMessage ->
                _uiState.update { it.copy(debianDownloadProgress = progress) }
                appendLine(TerminalLine.plain("[DEBIAN] $statusMessage", TerminalCyan))
            }
            _uiState.update {
                it.copy(
                    isDownloadingDebian = false,
                    isDebianReady = success,
                    prompt = if (success) "root@gadessec:~# " else "gadessec@debian:~$ "
                )
            }
            if (success) {
                appendLine(TerminalLine.plain("[SUCCESS] Debian rootfs installed. Launching PRoot Bash session...", TerminalGreenGlow))
                shellProcess.restartSession()
            } else {
                appendLine(TerminalLine.plain("[ERROR] Debian rootfs installation failed. Check network connection and retry.", TerminalRed))
            }
        }
    }

    fun executeCurrentCommand() {
        val cmd = _uiState.value.currentInput.trim()
        val prompt = _uiState.value.prompt

        val commandLine = TerminalLine(listOf(
            TextSpan(prompt, TerminalGreen, isBold = true),
            TextSpan(cmd, TextPrimary)
        ))
        appendLine(commandLine)

        _uiState.update {
            it.copy(
                currentInput = "",
                isCtrlActive = false,
                isAltActive = false
            )
        }

        if (cmd.isNotEmpty()) {
            commandHistory.add(cmd)
            historyIndex = commandHistory.size
        }

        if (cmd.isEmpty()) return

        when {
            cmd == "clear" -> {
                _uiState.update { it.copy(lines = emptyList()) }
            }
            cmd == "help" || cmd == "gadessec-help" -> {
                showHelp()
            }
            cmd == "gadessec" || cmd == "banner" -> {
                renderWelcomeBanner()
            }
            cmd == "sysinfo" -> {
                showSysInfo()
            }
            cmd == "tor" || cmd == "tor-status" -> {
                showTorStatus()
            }
            cmd == "tor-toggle" -> {
                toggleTor()
            }
            cmd in listOf("setup-debian", "debian-setup", "debian setup", "debian-install", "setup", "pkg setup", "debian") -> {
                triggerDebianDownload()
            }
            cmd.startsWith("pkg ") -> {
                val aptEquivalent = "apt " + cmd.removePrefix("pkg ").trim()
                viewModelScope.launch {
                    shellProcess.sendCommand(aptEquivalent, _uiState.value.isTorEnabled)
                }
            }
            else -> {
                viewModelScope.launch {
                    shellProcess.sendCommand(cmd, _uiState.value.isTorEnabled)
                }
            }
        }
    }

    fun handleSpecialKey(key: String) {
        when (key) {
            "ENTER" -> executeCurrentCommand()
            "TAB" -> {
                _uiState.update { it.copy(currentInput = it.currentInput + "  ") }
            }
            "ESC" -> {
                viewModelScope.launch {
                    shellProcess.sendSignal('\u001B')
                }
            }
            "CTRL+C" -> {
                viewModelScope.launch {
                    shellProcess.sendSignal('\u0003')
                    appendLine(TerminalLine(listOf(TextSpan("^C", TerminalRed))))
                }
            }
            "CTRL+L" -> {
                _uiState.update { it.copy(lines = emptyList()) }
            }
            "UP" -> {
                if (commandHistory.isNotEmpty() && historyIndex > 0) {
                    historyIndex--
                    _uiState.update { it.copy(currentInput = commandHistory[historyIndex]) }
                }
            }
            "DOWN" -> {
                if (commandHistory.isNotEmpty() && historyIndex < commandHistory.size - 1) {
                    historyIndex++
                    _uiState.update { it.copy(currentInput = commandHistory[historyIndex]) }
                } else {
                    historyIndex = commandHistory.size
                    _uiState.update { it.copy(currentInput = "") }
                }
            }
            else -> {
                _uiState.update { it.copy(currentInput = it.currentInput + key) }
            }
        }
    }

    fun appendLine(line: TerminalLine) {
        _uiState.update {
            val updated = it.lines + line
            if (updated.size > 500) {
                it.copy(lines = updated.takeLast(500))
            } else {
                it.copy(lines = updated)
            }
        }
    }

    private fun appendRawLine(raw: String) {
        val parsed = AnsiParser.parseLine(raw)
        appendLine(parsed)
    }

    private fun showHelp() {
        val helpLines = listOf(
            TerminalLine(listOf(TextSpan("=== GadesSec Terminal Commands ===", TerminalGreenGlow, isBold = true))),
            TerminalLine(listOf(TextSpan("  pkg install <pkg> / apt install <pkg> ", TerminalGreen), TextSpan("- Install package", TextPrimary))),
            TerminalLine(listOf(TextSpan("  pkg update / apt update               ", TerminalGreen), TextSpan("- Update package index", TextPrimary))),
            TerminalLine(listOf(TextSpan("  pkg list / apt list                   ", TerminalGreen), TextSpan("- List installed packages", TextPrimary))),
            TerminalLine(listOf(TextSpan("  python3 / python3 -c \"...\"             ", TerminalGreen), TextSpan("- Python 3 interpreter", TextPrimary))),
            TerminalLine(listOf(TextSpan("  git init / git status                 ", TerminalGreen), TextSpan("- Git version control", TextPrimary))),
            TerminalLine(listOf(TextSpan("  curl <url> / wget <url>               ", TerminalGreen), TextSpan("- Real HTTP/HTTPS client", TextPrimary))),
            TerminalLine(listOf(TextSpan("  scan <target>                         ", TerminalGreen), TextSpan("- TCP connect port scanner", TextPrimary))),
            TerminalLine(listOf(TextSpan("  ifconfig / ip a                       ", TerminalGreen), TextSpan("- Device network interfaces & IPs", TextPrimary))),
            TerminalLine(listOf(TextSpan("  tor-toggle                            ", TerminalGreen), TextSpan("- Toggle optional Tor SOCKS5 mode", TextPrimary))),
            TerminalLine(listOf(TextSpan("  setup-debian                          ", TerminalGreen), TextSpan("- Install full Debian rootfs with PRoot", TextPrimary))),
            TerminalLine(listOf(TextSpan("  clear                                 ", TerminalGreen), TextSpan("- Clear terminal screen", TextPrimary))),
            TerminalLine(listOf(TextSpan("  sysinfo                               ", TerminalGreen), TextSpan("- System & storage information", TextPrimary))),
            TerminalLine(emptyList())
        )
        helpLines.forEach { appendLine(it) }
    }

    private fun showSysInfo() {
        val freeBytes = getApplication<Application>().filesDir.freeSpace / (1024 * 1024)
        val kernel = System.getProperty("os.version") ?: "Linux"
        val infoLines = listOf(
            TerminalLine(listOf(TextSpan("=== GadesSec Terminal System Info ===", TerminalCyan, isBold = true))),
            TerminalLine(listOf(TextSpan("Kernel:          ", TextSecondary), TextSpan(kernel, TextPrimary))),
            TerminalLine(listOf(TextSpan("Architecture:    ", TextSecondary), TextSpan(_uiState.value.architecture, TerminalGreen))),
            TerminalLine(listOf(TextSpan("Storage Free:    ", TextSecondary), TextSpan("${freeBytes} MB", TerminalGreen))),
            TerminalLine(listOf(TextSpan("Network Mode:    ", TextSecondary), if (_uiState.value.isTorEnabled) TextSpan("Tor SOCKS5 Proxy", TerminalAmber) else TextSpan("Direct Internet (Fast)", TerminalGreen))),
            TerminalLine(emptyList()),
            TerminalLine(listOf(TextSpan("[i] Terminal Capabilities:", TerminalGreen, isBold = true))),
            TerminalLine(listOf(TextSpan("  • Full interactive POSIX shell & PRoot userland environment.", TextPrimary))),
            TerminalLine(listOf(TextSpan("  • Native TCP socket networking and package management.", TextPrimary))),
            TerminalLine(listOf(TextSpan("  • Long-press on terminal output to select and copy text.", TextPrimary))),
            TerminalLine(emptyList())
        )
        infoLines.forEach { appendLine(it) }
    }

    private fun showTorStatus() {
        val torState = _uiState.value.isTorEnabled
        val killswitchState = _uiState.value.isKillswitchEngaged
        val statusLines = listOf(
            TerminalLine(listOf(TextSpan("=== SOCKS5 Tor Status ===", TerminalGreenGlow, isBold = true))),
            TerminalLine(listOf(
                TextSpan("Status:        ", TextSecondary),
                if (torState) TextSpan("ACTIVE (127.0.0.1:9050)", TerminalGreen, isBold = true)
                else TextSpan("OFF (Direct Internet)", TerminalGreen)
            )),
            TerminalLine(listOf(
                TextSpan("Killswitch:    ", TextSecondary),
                if (killswitchState) TextSpan("ARMED", TerminalGreen, isBold = true)
                else TextSpan("INACTIVE", TextMuted)
            )),
            TerminalLine(listOf(TextSpan("Scope:         ", TextSecondary), TextSpan("Isolated to GadesSecOS (Application SOCKS5 proxy)", TerminalCyan))),
            TerminalLine(emptyList())
        )
        statusLines.forEach { appendLine(it) }
    }

    override fun onCleared() {
        super.onCleared()
        shellProcess.destroy()
    }
}
