package com.example

import com.example.terminal.DebianToolsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebianToolsEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun runPython_evaluatesArithmeticExpression() {
        val logs = mutableListOf<String>()
        DebianToolsEngine.runPython("python3 -c \"print(21 + 21)\"") { log ->
            logs.add(log)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("42") })
    }

    @Test
    fun runPython_versionCheck() {
        val logs = mutableListOf<String>()
        DebianToolsEngine.runPython("python3 --version") { log ->
            logs.add(log)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("Python 3") })
    }

    @Test
    fun runGit_initializesRepositoryAndChecksStatus() {
        val workingDir = tempFolder.newFolder("project")
        val logs = mutableListOf<String>()

        // 1. git init
        DebianToolsEngine.runGit("git init", workingDir) { log -> logs.add(log) }
        assertTrue(logs.any { it.contains("Initialized empty Git repository") })
        val gitDir = File(workingDir, ".git")
        assertTrue(gitDir.exists())

        // 2. create file and check git status
        File(workingDir, "sample.txt").writeText("Hello Git")
        val statusLogs = mutableListOf<String>()
        DebianToolsEngine.runGit("git status", workingDir) { log -> statusLogs.add(log) }
        assertTrue(statusLogs.any { it.contains("sample.txt") })
    }

    @Test
    fun runDnsLookup_resolvesLocalhost() = runTest {
        val logs = mutableListOf<String>()
        DebianToolsEngine.runDnsLookup("dig localhost") { log ->
            logs.add(log)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("localhost") || it.contains("127.0.0.1") })
    }

    @Test
    fun runPip_versionCheck() {
        val logs = mutableListOf<String>()
        DebianToolsEngine.runPip("pip --version") { log -> logs.add(log) }
        assertTrue(logs.any { it.contains("pip 23") })
    }

    @Test
    fun runNeofetch_outputsDebianLogo() {
        val logs = mutableListOf<String>()
        DebianToolsEngine.runNeofetch { log -> logs.add(log) }
        assertTrue(logs.any { it.contains("Debian GNU/Linux") })
        assertTrue(logs.any { it.contains("Samsung Galaxy A17") })
    }
}
