package com.example

import com.example.terminal.RealNetworkEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealNetworkEngineTest {

    @Test
    fun getRealIfconfigOutput_returnsValidLinuxInterfaceData() {
        val output = RealNetworkEngine.getRealIfconfigOutput()
        assertNotNull(output)
        assertTrue(output.isNotEmpty())
        assertTrue(output.any { it.contains("flags=") && it.contains("mtu") })
    }

    @Test
    fun runRealPortScan_scansLocalhostWithoutCrashing() = runTest {
        val logs = mutableListOf<String>()
        RealNetworkEngine.runRealPortScan("127.0.0.1", listOf(54321), isTorEnabled = false) { log ->
            logs.add(log)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("Starting GadesSec TCP Connect Scanner") })
        assertTrue(logs.any { it.contains("Scan completed") })
    }

    @Test
    fun runRealPortScan_killswitchEngagesWhenTorOffline() = runTest {
        val logs = mutableListOf<String>()
        RealNetworkEngine.runRealPortScan("127.0.0.1", listOf(54321), isTorEnabled = true) { log ->
            logs.add(log)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("KILLSWITCH ENGAGED") })
    }
}
