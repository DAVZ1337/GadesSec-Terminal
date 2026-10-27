package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.terminal.RootfsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RootfsManagerTest {

    private lateinit var context: Context
    private lateinit var rootfsManager: RootfsManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rootfsManager = RootfsManager(context)
    }

    @Test
    fun getDeviceArchitecture_returnsValidDebianArchitecture() {
        val arch = rootfsManager.getDeviceArchitecture()
        assertNotNull(arch)
        assertTrue(arch in listOf("arm64", "amd64", "armhf"))
    }

    @Test
    fun isPRootReady_rejectsFakeScriptSubstitutes() {
        val prootBin = rootfsManager.prootBinary
        prootBin.parentFile?.mkdirs()
        // Write fake shell script substitute
        prootBin.writeText("#!/bin/sh\nexec \"$@\"\n")
        prootBin.setExecutable(true, false)

        // Must reject text script because it's not a real ELF binary
        assertFalse(rootfsManager.isPRootReady())
    }

    @Test
    fun isPRootReady_acceptsGenuineElfBinary() {
        val prootBin = rootfsManager.prootBinary
        prootBin.parentFile?.mkdirs()

        // Create mock binary with authentic ELF header (0x7F, 'E', 'L', 'F') and padding > 1000 bytes
        val elfHeader = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val padding = ByteArray(1200)
        prootBin.writeBytes(elfHeader + padding)
        prootBin.setExecutable(true, false)

        assertTrue(rootfsManager.isPRootReady())
    }

    @Test
    fun isInstalled_checksActualDebianBinaries() {
        val rootfsDir = rootfsManager.rootfsDir
        val bashFile = File(rootfsDir, "bin/bash")
        bashFile.delete()

        assertFalse(rootfsManager.isInstalled())

        bashFile.parentFile?.mkdirs()
        bashFile.writeBytes(ByteArray(2048))
        assertTrue(rootfsManager.isInstalled())
    }
}
