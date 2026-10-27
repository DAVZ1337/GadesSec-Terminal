package com.example.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

object DebianRootfsArchiveExtractor {

    /**
     * Extracts a .tar.xz or .tar.gz archive stream directly into the target directory.
     * Enforces strict path traversal prevention, symlink boundary validation,
     * and eliminates text-file symlink fallbacks.
     */
    suspend fun extractTarArchive(
        inputStream: InputStream,
        isXz: Boolean,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var count = 0
        try {
            targetDir.mkdirs()
            val targetBasePath: Path = targetDir.toPath().toAbsolutePath().normalize()

            val decompressor: InputStream = if (isXz) {
                XZCompressorInputStream(BufferedInputStream(inputStream))
            } else {
                GZIPInputStream(BufferedInputStream(inputStream))
            }

            val tarIn = TarArchiveInputStream(decompressor)
            var entry: TarArchiveEntry? = tarIn.nextTarEntry

            while (entry != null) {
                count++
                val rawName = entry.name.removePrefix("/")
                // Strip top-level directory if archive packages rootfs under a folder like debian-trixie-aarch64/
                val normalizedName = if (rawName.startsWith("debian-") && rawName.contains("/")) {
                    rawName.substringAfter("/")
                } else {
                    rawName
                }

                if (normalizedName.isEmpty()) {
                    entry = tarIn.nextTarEntry
                    continue
                }

                val entryPath = targetBasePath.resolve(normalizedName).normalize()

                // 1. Syntactic path traversal protection:
                if (!entryPath.startsWith(targetBasePath)) {
                    onProgress(count, "Security Warning: Path traversal detected in entry '$rawName'. Skipped.")
                    entry = tarIn.nextTarEntry
                    continue
                }

                // 2. Prevent write-through via existing symlinked parent directories
                if (hasEscapingParentSymlink(entryPath, targetBasePath)) {
                    onProgress(count, "Security Warning: Entry '$rawName' traverses an escaping parent symlink. Skipped.")
                    entry = tarIn.nextTarEntry
                    continue
                }

                val entryFile = entryPath.toFile()

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else if (entry.isSymbolicLink) {
                    // 3. Symlink boundary validation: Ensure link destination does not escape targetBasePath
                    val targetLinkPath = Paths.get(entry.linkName)
                    val resolvedDestination = if (targetLinkPath.isAbsolute) {
                        targetBasePath.resolve(targetLinkPath.toString().removePrefix("/")).normalize()
                    } else {
                        val parentPath = entryPath.parent ?: targetBasePath
                        parentPath.resolve(targetLinkPath).normalize()
                    }

                    if (!resolvedDestination.startsWith(targetBasePath)) {
                        onProgress(count, "Security Warning: Symlink '$rawName' target ('${entry.linkName}') escapes root. Skipped.")
                        entry = tarIn.nextTarEntry
                        continue
                    }

                    entryFile.parentFile?.mkdirs()
                    if (Files.exists(entryPath) || Files.isSymbolicLink(entryPath)) {
                        Files.deleteIfExists(entryPath)
                    }

                    try {
                        Files.createSymbolicLink(entryPath, targetLinkPath)
                    } catch (e: Exception) {
                        // Strict security: Do NOT write plain text fallback! Log error honestly.
                        onProgress(count, "Warning: Unable to create symlink '$rawName' -> '${entry.linkName}': ${e.message}")
                    }
                } else {
                    entryFile.parentFile?.mkdirs()
                    if (Files.isSymbolicLink(entryPath)) {
                        Files.deleteIfExists(entryPath)
                    }

                    FileOutputStream(entryFile).use { out ->
                        tarIn.copyTo(out)
                    }

                    // Set executable flag if owner/group/other execute bit is set or in binary paths
                    val mode = entry.mode
                    val isExecutable = (mode and 0b001_001_001) != 0 ||
                            entryFile.parentFile?.name == "bin" ||
                            entryFile.parentFile?.name == "sbin"
                    if (isExecutable) {
                        entryFile.setExecutable(true, false)
                    }
                }

                if (count % 250 == 0) {
                    onProgress(count, "Extracted $count files...")
                }

                entry = tarIn.nextTarEntry
            }

            tarIn.close()
            onProgress(count, "Extraction complete ($count files).")
            true
        } catch (e: Exception) {
            onProgress(count, "Extraction error: ${e.localizedMessage ?: "Corrupted archive"}")
            false
        }
    }

    private fun hasEscapingParentSymlink(entryPath: Path, targetBasePath: Path): Boolean {
        var current: Path? = entryPath.parent
        while (current != null && current != targetBasePath && current.startsWith(targetBasePath)) {
            if (Files.isSymbolicLink(current)) {
                try {
                    val linkTarget = Files.readSymbolicLink(current)
                    val parent = current.parent ?: targetBasePath
                    val resolved = if (linkTarget.isAbsolute) {
                        targetBasePath.resolve(linkTarget.toString().removePrefix("/")).normalize()
                    } else {
                        parent.resolve(linkTarget).normalize()
                    }
                    if (!resolved.startsWith(targetBasePath)) {
                        return true
                    }
                } catch (_: Exception) {
                    return true
                }
            }
            current = current.parent
        }
        return false
    }
}
