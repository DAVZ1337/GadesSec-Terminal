package com.example

import com.example.terminal.DebianRootfsArchiveExtractor
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class DebianRootfsArchiveExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractTarArchive_extractsFilesAndSetsExecutableBits() = runTest {
        val destDir = tempFolder.newFolder("extracted_rootfs")

        val byteOut = ByteArrayOutputStream()
        val gzipOut = GZIPOutputStream(byteOut)
        val tarOut = TarArchiveOutputStream(gzipOut)

        // 1. Directory bin/
        val dirEntry = TarArchiveEntry("bin/")
        tarOut.putArchiveEntry(dirEntry)
        tarOut.closeArchiveEntry()

        // 2. Binary bin/bash with executable permissions (0755)
        val binEntry = TarArchiveEntry("bin/bash")
        binEntry.mode = 0b111_101_101
        val content = "#!/bin/sh\necho 'Debian Bash'\n".toByteArray()
        binEntry.size = content.size.toLong()
        tarOut.putArchiveEntry(binEntry)
        tarOut.write(content)
        tarOut.closeArchiveEntry()

        // 3. Regular file etc/debian_version
        val etcDir = TarArchiveEntry("etc/")
        tarOut.putArchiveEntry(etcDir)
        tarOut.closeArchiveEntry()

        val verEntry = TarArchiveEntry("etc/debian_version")
        val verContent = "12.5\n".toByteArray()
        verEntry.size = verContent.size.toLong()
        tarOut.putArchiveEntry(verEntry)
        tarOut.write(verContent)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val archiveBytes = byteOut.toByteArray()
        val logs = mutableListOf<String>()

        val success = DebianRootfsArchiveExtractor.extractTarArchive(
            ByteArrayInputStream(archiveBytes),
            isXz = false,
            destDir
        ) { _, msg -> logs.add(msg) }

        assertTrue(success)

        val bashFile = File(destDir, "bin/bash")
        assertTrue(bashFile.exists())
        assertTrue(bashFile.canExecute())

        val verFile = File(destDir, "etc/debian_version")
        assertTrue(verFile.exists())
        assertEquals("12.5\n", verFile.readText())
    }

    @Test
    fun extractTarArchive_rejectsPathTraversalAttack() = runTest {
        val destDir = tempFolder.newFolder("safe_rootfs")
        val outsideFile = File(tempFolder.root, "traversal_victim.txt")

        val byteOut = ByteArrayOutputStream()
        val gzipOut = GZIPOutputStream(byteOut)
        val tarOut = TarArchiveOutputStream(gzipOut)

        val attackEntry = TarArchiveEntry("../traversal_victim.txt")
        val attackContent = "malicious payload".toByteArray()
        attackEntry.size = attackContent.size.toLong()
        tarOut.putArchiveEntry(attackEntry)
        tarOut.write(attackContent)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val logs = mutableListOf<String>()
        val success = DebianRootfsArchiveExtractor.extractTarArchive(
            ByteArrayInputStream(byteOut.toByteArray()),
            isXz = false,
            destDir
        ) { _, msg -> logs.add(msg) }

        assertTrue(success)
        assertFalse(outsideFile.exists())
        assertTrue(logs.any { it.contains("Path traversal detected") })
    }

    @Test
    fun extractTarArchive_createsRealSymbolicLinks() = runTest {
        val destDir = tempFolder.newFolder("symlink_rootfs")

        val byteOut = ByteArrayOutputStream()
        val gzipOut = GZIPOutputStream(byteOut)
        val tarOut = TarArchiveOutputStream(gzipOut)

        val targetEntry = TarArchiveEntry("bin/bash")
        val targetContent = "echo bash".toByteArray()
        targetEntry.size = targetContent.size.toLong()
        tarOut.putArchiveEntry(targetEntry)
        tarOut.write(targetContent)
        tarOut.closeArchiveEntry()

        val symlinkEntry = TarArchiveEntry("bin/sh", TarArchiveEntry.LF_SYMLINK)
        symlinkEntry.linkName = "bash"
        tarOut.putArchiveEntry(symlinkEntry)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val success = DebianRootfsArchiveExtractor.extractTarArchive(
            ByteArrayInputStream(byteOut.toByteArray()),
            isXz = false,
            destDir
        ) { _, _ -> }

        assertTrue(success)

        val shFile = File(destDir, "bin/sh")
        assertTrue(shFile.exists() || Files.isSymbolicLink(shFile.toPath()))
        assertTrue(Files.isSymbolicLink(shFile.toPath()))
    }

    @Test
    fun extractTarArchive_rejectsEscapingSymlinkTarget() = runTest {
        val destDir = tempFolder.newFolder("escaping_symlink_rootfs")

        val byteOut = ByteArrayOutputStream()
        val gzipOut = GZIPOutputStream(byteOut)
        val tarOut = TarArchiveOutputStream(gzipOut)

        // Symlink pointing outside extraction root
        val evilSymlink = TarArchiveEntry("bin/evil_link", TarArchiveEntry.LF_SYMLINK)
        evilSymlink.linkName = "../../../../../etc/passwd"
        tarOut.putArchiveEntry(evilSymlink)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val logs = mutableListOf<String>()
        val success = DebianRootfsArchiveExtractor.extractTarArchive(
            ByteArrayInputStream(byteOut.toByteArray()),
            isXz = false,
            destDir
        ) { _, msg -> logs.add(msg) }

        assertTrue(success)
        val evilFile = File(destDir, "bin/evil_link")
        assertFalse(evilFile.exists())
        assertFalse(Files.isSymbolicLink(evilFile.toPath()))
        assertTrue(logs.any { it.contains("escapes root") })
    }

    @Test
    fun extractTarArchive_stripsDebianLeadingPrefix() = runTest {
        val destDir = tempFolder.newFolder("prefixed_rootfs")

        val byteOut = ByteArrayOutputStream()
        val gzipOut = GZIPOutputStream(byteOut)
        val tarOut = TarArchiveOutputStream(gzipOut)

        val entry = TarArchiveEntry("debian-trixie-aarch64/usr/bin/bash")
        val content = "#!/bin/bash\necho hello".toByteArray()
        entry.size = content.size.toLong()
        entry.mode = 0b111_101_101
        tarOut.putArchiveEntry(entry)
        tarOut.write(content)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val success = DebianRootfsArchiveExtractor.extractTarArchive(
            ByteArrayInputStream(byteOut.toByteArray()),
            isXz = false,
            destDir
        ) { _, _ -> }

        assertTrue(success)
        val extractedBash = File(destDir, "usr/bin/bash")
        assertTrue(extractedBash.exists())
        assertTrue(extractedBash.canExecute())
    }
}
