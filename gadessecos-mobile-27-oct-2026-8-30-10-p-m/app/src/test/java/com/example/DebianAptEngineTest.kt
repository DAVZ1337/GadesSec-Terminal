package com.example

import com.example.terminal.DebianAptEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebianAptEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootfsDir: File
    private lateinit var dpkgStatusFile: File
    private lateinit var aptEngine: DebianAptEngine

    @Before
    fun setup() {
        rootfsDir = tempFolder.newFolder("rootfs")
        dpkgStatusFile = File(rootfsDir, "var/lib/dpkg/status").apply {
            parentFile?.mkdirs()
            writeText(
                "Package: bash\nStatus: install ok installed\nArchitecture: arm64\nVersion: 5.2.15-2\n\n" +
                "Package: apt\nStatus: install ok installed\nArchitecture: arm64\nVersion: 2.6.1\n\n"
            )
        }
        aptEngine = DebianAptEngine(rootfsDir, dpkgStatusFile)
        aptEngine.packageIndexFile.parentFile?.mkdirs()
        aptEngine.packageIndexFile.writeText("")
    }

    @Test
    fun listInstalled_readsActualDpkgStatusFile() {
        val installed = aptEngine.listInstalled()
        assertTrue(installed.any { it.contains("bash") })
        assertTrue(installed.any { it.contains("apt") })
    }

    @Test
    fun findPackageInIndex_parsesPackagesRecordWithDependencies() {
        // Write mock Debian Packages index
        aptEngine.packageIndexFile.parentFile?.mkdirs()
        aptEngine.packageIndexFile.writeText(
            "Package: curl\n" +
            "Version: 7.88.1-10+deb12u5\n" +
            "Architecture: arm64\n" +
            "Depends: libc6 (>= 2.34), libcurl4 (= 7.88.1-10+deb12u5), zlib1g (>= 1:1.1.4)\n" +
            "Filename: pool/main/c/curl/curl_7.88.1-10+deb12u5_arm64.deb\n" +
            "Size: 314488\n" +
            "SHA256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
            "Section: web\n\n"
        )

        val record = aptEngine.findPackageInIndex("curl")
        assertNotNull(record)
        assertEquals("curl", record?.name)
        assertEquals("7.88.1-10+deb12u5", record?.version)
        assertEquals("pool/main/c/curl/curl_7.88.1-10+deb12u5_arm64.deb", record?.filename)
        assertEquals(314488L, record?.size)
        assertTrue(record?.depends?.any { it.contains("libc6") } == true)
        assertTrue(record?.depends?.any { it.contains("libcurl4") } == true)
    }

    @Test
    fun findPackageInIndex_returnsNullForMissingPackage() {
        aptEngine.packageIndexFile.parentFile?.mkdirs()
        aptEngine.packageIndexFile.writeText("Package: nano\nVersion: 7.2-1\nFilename: pool/main/n/nano/nano_7.2-1_arm64.deb\n\n")

        val record = aptEngine.findPackageInIndex("non_existent_pkg")
        assertNull(record)
    }

    @Test
    fun installPackage_failsTruthfullyWhenNotIndexedWithoutInventedFallback() = runTest {
        val logs = mutableListOf<String>()
        val success = aptEngine.install("unknown_invalid_pkg", isTorEnabled = false) { log ->
            logs.add(log)
        }
        assertFalse(success)
        assertTrue(logs.any { it.contains("has no installation candidate in Debian index") })
    }

    @Test
    fun installPackage_detectsAlreadyInstalledPackage() = runTest {
        val logs = mutableListOf<String>()
        val success = aptEngine.install("bash", isTorEnabled = false) { log ->
            logs.add(log)
        }
        assertTrue(success)
        assertTrue(logs.any { it.contains("already the newest version") })
    }

    @Test
    fun update_killswitchBlocksWhenTorUnavailable() = runTest {
        val logs = mutableListOf<String>()
        val result = aptEngine.update(isTorEnabled = true) { log ->
            logs.add(log)
        }
        assertFalse(result)
        assertTrue(logs.any { it.contains("Blocked by Killswitch") })
    }

    @Test
    fun packageRecord_handlesSectionsAndMultiDependencies() {
        aptEngine.packageIndexFile.parentFile?.mkdirs()
        aptEngine.packageIndexFile.writeText(
            "Package: vim\n" +
            "Version: 2:9.0.1378-2\n" +
            "Architecture: arm64\n" +
            "Section: editors\n" +
            "Depends: vim-common (= 2:9.0.1378-2), vim-runtime (= 2:9.0.1378-2), libc6 (>= 2.34)\n" +
            "Filename: pool/main/v/vim/vim_9.0.1378-2_arm64.deb\n" +
            "Size: 1495832\n" +
            "SHA256: d2419ecde2a41243dbf88c8f000b17163c4573fb9d35c8b74681615b3c5a6d71\n\n"
        )

        val record = aptEngine.findPackageInIndex("vim")
        assertNotNull(record)
        assertEquals("editors", record?.section)
        assertEquals(3, record?.depends?.size)
        assertTrue(record?.depends?.contains("vim-common (= 2:9.0.1378-2)") == true)
    }
}
