package com.example

import com.example.terminal.AnsiParser
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    @Test
    fun parsePlainLine_returnsSingleSpanWithExactText() {
        val input = "Hello GadesSecOS"
        val line = AnsiParser.parseLine(input)
        assertEquals(input, line.rawText)
        assertEquals(1, line.spans.size)
        assertEquals("Hello GadesSecOS", line.spans[0].text)
    }

    @Test
    fun parseAnsiColors_detectsGreenAndRed() {
        val input = "\u001B[32mSUCCESS\u001B[0m: \u001B[31mERROR\u001B[0m"
        val line = AnsiParser.parseLine(input)
        assertEquals("SUCCESS: ERROR", line.rawText)
        assertTrue(line.spans.size >= 3)
        assertEquals(TerminalGreen, line.spans[0].color)
        assertEquals(TerminalRed, line.spans[2].color)
    }

    @Test
    fun parseAnsiBold_setsBoldFlag() {
        val input = "\u001B[1;34mCYBER CYAN BOLD\u001B[0m"
        val line = AnsiParser.parseLine(input)
        assertEquals("CYBER CYAN BOLD", line.rawText)
        assertTrue(line.spans[0].isBold)
        assertEquals(TerminalCyan, line.spans[0].color)
    }

    @Test
    fun parseCursorSequences_absorbsWithoutArtifacts() {
        val input = "\u001B[2K\u001B[1;1Hgadessec@mobile:~# \u001B[32mpwd\u001B[0m"
        val line = AnsiParser.parseLine(input)
        assertEquals("gadessec@mobile:~# pwd", line.rawText)
    }
}
