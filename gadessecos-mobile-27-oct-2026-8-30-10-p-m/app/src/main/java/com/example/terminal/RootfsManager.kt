package com.example.terminal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RootfsManager(private val context: Context) {

    val rootfsDir: File by lazy {
        File(context.filesDir, "rootfs").apply { if (!exists()) mkdirs() }
    }

    val binDir: File by lazy { File(context.filesDir, "bin").apply { if (!exists()) mkdirs() } }
    val prootBinary: File by lazy { File(binDir, "proot") }

    val usrBinDir: File by lazy { File(rootfsDir, "usr/bin").apply { if (!exists()) mkdirs() } }
    val etcDir: File by lazy { File(rootfsDir, "etc").apply { if (!exists()) mkdirs() } }
    val varLibDpKgDir: File by lazy { File(rootfsDir, "var/lib/dpkg").apply { if (!exists()) mkdirs() } }
    val dpkgStatusFile: File by lazy { File(varLibDpKgDir, "status") }

    val aptEngine: DebianAptEngine by lazy { DebianAptEngine(rootfsDir, dpkgStatusFile) }

    // Official Debian minimal rootfs archive mirrors (verified Termux proot-distro releases)
    private val ROOTFS_ARCHIVES = mapOf(
        "arm64" to listOf(
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
        ),
        "amd64" to listOf(
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-x86_64-pd-v4.29.0.tar.xz"
        ),
        "armhf" to listOf(
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-arm-pd-v4.29.0.tar.xz"
        )
    )

    // Real PRoot ELF static binary release URLs
    private val PROOT_BINARIES = mapOf(
        "arm64" to listOf(
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
        ),
        "amd64" to listOf(
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static"
        )
    )

    init {
        setupDebianEnvironment()
    }

    /**
     * Determines the exact host architecture
     */
    fun getDeviceArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "arm64-v8a"
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.contains("x86_64") || abi.contains("amd64") -> "amd64"
            abi.contains("arm") -> "armhf"
            else -> "arm64"
        }
    }

    /**
     * Checks if real Debian bash or sh binary exists inside rootfs
     */
    fun isInstalled(): Boolean {
        val bash = File(rootfsDir, "bin/bash")
        val sh = File(rootfsDir, "bin/sh")
        val usrBash = File(usrBinDir, "bash")
        return (bash.exists() && bash.length() > 0) ||
                (sh.exists() && sh.length() > 0) ||
                (usrBash.exists() && usrBash.length() > 0)
    }

    /**
     * Checks if real PRoot ELF binary exists, is executable, and has valid ELF magic (0x7F 'E' 'L' 'F').
     * Strictly rejects shell script or text file substitutes.
     */
    fun isPRootReady(): Boolean {
        if (!prootBinary.exists() || !prootBinary.canExecute() || prootBinary.length() < 1000) {
            return false
        }
        return try {
            FileInputStream(prootBinary).use { stream ->
                val magic = ByteArray(4)
                val read = stream.read(magic)
                read == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                        magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getInstalledPackages(): List<String> {
        return aptEngine.listInstalled()
    }

    fun setupDebianEnvironment() {
        try {
            val resolvConf = File(etcDir, "resolv.conf")
            if (!resolvConf.exists()) {
                resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            val hosts = File(etcDir, "hosts")
            if (!hosts.exists()) {
                hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }

            val aptDir = File(etcDir, "apt").apply { if (!exists()) mkdirs() }
            val sourcesList = File(aptDir, "sources.list")
            if (!sourcesList.exists()) {
                sourcesList.writeText(
                    "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                    "deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware\n"
                )
            }

            val rootHome = File(rootfsDir, "root").apply { if (!exists()) mkdirs() }
            val bashrc = File(rootHome, ".bashrc")
            if (!bashrc.exists()) {
                bashrc.writeText(
                    "export PS1='\\[\\033[01;32m\\]root@gadessec\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]# '\n" +
                    "export TERM=xterm-256color\n" +
                    "export LANG=C.UTF-8\n" +
                    "alias ls='ls --color=auto'\n" +
                    "alias ll='ls -la'\n"
                )
            }

            val tmpDir = File(rootfsDir, "tmp").apply { if (!exists()) mkdirs() }
            tmpDir.setWritable(true, false)
            tmpDir.setReadable(true, false)
            tmpDir.setExecutable(true, false)

            val binDir = File(rootfsDir, "bin").apply { if (!exists()) mkdirs() }
            val bashInUsr = File(usrBinDir, "bash")
            val bashInBin = File(binDir, "bash")
            if (bashInUsr.exists() && !bashInBin.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(bashInBin.toPath(), java.nio.file.Paths.get("../usr/bin/bash"))
                } catch (_: Exception) {
                    try { bashInUsr.copyTo(bashInBin, overwrite = true); bashInBin.setExecutable(true, false) } catch (_: Exception) {}
                }
            }

            val shInUsr = File(usrBinDir, "sh")
            val shInBin = File(binDir, "sh")
            if (shInUsr.exists() && !shInBin.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(shInBin.toPath(), java.nio.file.Paths.get("../usr/bin/sh"))
                } catch (_: Exception) {
                    try { shInUsr.copyTo(shInBin, overwrite = true); shInBin.setExecutable(true, false) } catch (_: Exception) {}
                }
            }

            val passwd = File(etcDir, "passwd")
            if (!passwd.exists()) {
                passwd.writeText(
                    "root:x:0:0:root:/root:/bin/bash\n" +
                    "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n" +
                    "bin:x:2:2:bin:/bin:/usr/sbin/nologin\n" +
                    "sys:x:3:3:sys:/dev:/usr/sbin/nologin\n" +
                    "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n" +
                    "gadessec:x:1000:1000:GadesSec User:/home/gadessec:/bin/bash\n"
                )
            }

            val group = File(etcDir, "group")
            if (!group.exists()) {
                group.writeText(
                    "root:x:0:\n" +
                    "daemon:x:1:\n" +
                    "bin:x:2:\n" +
                    "sys:x:3:\n" +
                    "adm:x:4:\n" +
                    "tty:x:5:\n" +
                    "disk:x:6:\n" +
                    "sudo:x:27:gadessec\n" +
                    "audio:x:29:\n" +
                    "video:x:44:\n" +
                    "plugdev:x:46:\n" +
                    "netdev:x:101:\n" +
                    "aid_inet:x:3003:\n" +
                    "aid_net_raw:x:3004:\n" +
                    "aid_everybody:x:9997:\n" +
                    "nogroup:x:65534:\n" +
                    "gadessec:x:1000:\n"
                )
            }

            if (!dpkgStatusFile.exists()) {
                dpkgStatusFile.parentFile?.mkdirs()
                dpkgStatusFile.writeText(
                    "Package: base-files\nStatus: install ok installed\nArchitecture: ${getDeviceArchitecture()}\nVersion: 12.4+deb12u1\n\n"
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun executeAptUpdate(isTorEnabled: Boolean, onLog: (String) -> Unit): Boolean {
        return aptEngine.update(isTorEnabled, onLog)
    }

    suspend fun executeAptInstall(packageName: String, isTorEnabled: Boolean = false, onLog: (String) -> Unit): Boolean {
        return aptEngine.install(packageName, isTorEnabled, onLog)
    }

    /**
     * Downloads real compiled PRoot ELF binary from official release mirror.
     * Strictly verifies ELF magic header before setting permissions.
     */
    suspend fun downloadPRootBinary(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val arch = getDeviceArchitecture()
        val urls = PROOT_BINARIES[arch] ?: PROOT_BINARIES["arm64"]!!

        binDir.mkdirs()
        for (urlStr in urls) {
            try {
                onProgress("Fetching real PRoot ELF binary from $urlStr...")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode in 200..299) {
                    val tempBin = File(binDir, "proot.tmp")
                    val input = BufferedInputStream(conn.inputStream)
                    val output = FileOutputStream(tempBin)
                    input.copyTo(output)
                    output.flush()
                    output.close()
                    input.close()

                    // Check ELF magic
                    val isElf = FileInputStream(tempBin).use { stream ->
                        val magic = ByteArray(4)
                        val r = stream.read(magic)
                        r == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
                    }

                    if (isElf) {
                        tempBin.renameTo(prootBinary)
                        prootBinary.setExecutable(true, false)
                        onProgress("Real PRoot ELF binary installed and verified.")
                        return@withContext true
                    } else {
                        tempBin.delete()
                    }
                }
            } catch (e: Exception) {
                onProgress("PRoot download mirror attempt: ${e.localizedMessage}")
            }
        }
        onProgress("Could not fetch valid PRoot ELF binary from mirrors.")
        false
    }

    // Trusted SHA-256 cryptographic hashes for official minimal rootfs archives
    private val TRUSTED_ROOTFS_SHA256 = mapOf(
        "arm64" to "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
        "amd64" to "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
        "armhf" to "99bcba87d8d1c66c0de06259ac0a270eb0a20f8b4af39beb0705d28846d78b90"
    )

    /**
     * Downloads and extracts verified minimal Debian rootfs archive.
     * Strictly verifies free disk space, archive integrity, and PRoot ELF verification.
     */
    suspend fun downloadOfficialDebianRootfs(
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val arch = getDeviceArchitecture()
        onProgress(0.05f, "Target Architecture: $arch (Debian Bookworm)")

        // 1. Check free disk space (minimum 250 MB required for rootfs + extraction)
        val requiredBytes = 250 * 1024 * 1024L
        val freeBytes = context.filesDir.freeSpace
        if (freeBytes < requiredBytes) {
            val freeMb = freeBytes / (1024 * 1024)
            onProgress(0f, "Error: Insufficient storage ($freeMb MB free). Minimum 250 MB required.")
            return@withContext false
        }

        val archiveUrls = ROOTFS_ARCHIVES[arch] ?: ROOTFS_ARCHIVES["arm64"]!!
        var downloadedTar: File? = null

        for (urlStr in archiveUrls) {
            try {
                onProgress(0.12f, "Connecting to Debian rootfs archive mirror...")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 25000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode in 200..299) {
                    val contentLength = conn.contentLengthLong
                    val destFile = File(context.cacheDir, "debian-rootfs-$arch.tar.xz")
                    if (destFile.exists()) destFile.delete()

                    val input = BufferedInputStream(conn.inputStream)
                    val output = FileOutputStream(destFile)
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead: Long = 0

                    val digest = MessageDigest.getInstance("SHA-256")

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val pct = (0.15f + (totalRead.toFloat() / contentLength) * 0.55f).coerceAtMost(0.70f)
                            onProgress(pct, "Downloading rootfs: ${(totalRead / (1024 * 1024))} MB / ${(contentLength / (1024 * 1024))} MB")
                        } else {
                            onProgress(0.35f, "Downloading rootfs: ${(totalRead / 1024)} KB")
                        }
                    }

                    output.flush()
                    output.close()
                    input.close()

                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val trustedHash = TRUSTED_ROOTFS_SHA256[arch]
                    if (trustedHash != null && !hash.equals(trustedHash, ignoreCase = true)) {
                        destFile.delete()
                        onProgress(0f, "Error: Rootfs SHA-256 mismatch. Integrity check failed.")
                        return@withContext false
                    }

                    onProgress(0.72f, "SHA-256 Verified: ${hash.take(16)}... [PASS]")
                    downloadedTar = destFile
                    break
                }
            } catch (e: Exception) {
                onProgress(0.10f, "Mirror connection failed: ${e.localizedMessage}. Trying next...")
            }
        }

        if (downloadedTar == null || !downloadedTar.exists() || downloadedTar.length() < 1000000L) {
            onProgress(0f, "Error: Rootfs tar.xz archive download failed or was truncated.")
            return@withContext false
        }

        onProgress(0.75f, "Extracting real Debian rootfs into filesystem...")
        val extractSuccess = try {
            FileInputStream(downloadedTar).use { fileIn ->
                DebianRootfsArchiveExtractor.extractTarArchive(fileIn, isXz = true, rootfsDir) { _, msg ->
                    onProgress(0.85f, msg)
                }
            }
        } catch (e: Exception) {
            onProgress(0f, "Extraction exception: ${e.localizedMessage}")
            false
        }

        if (!extractSuccess) {
            onProgress(0f, "Error: Failed to unpack rootfs archive.")
            return@withContext false
        }

        onProgress(0.92f, "Fetching and verifying real PRoot ELF binary for $arch...")
        val prootSuccess = downloadPRootBinary { msg -> onProgress(0.95f, msg) }
        if (!prootSuccess || !isPRootReady()) {
            onProgress(0f, "Error: PRoot ELF binary installation failed or validation rejected.")
            return@withContext false
        }

        setupDebianEnvironment()
        onProgress(1.0f, "Debian $arch rootfs and PRoot successfully installed.")
        true
    }
}
