package com.example.terminal

import androidx.compose.ui.graphics.Color
import com.example.model.TerminalLine
import com.example.model.TextSpan
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenGlow
import com.example.ui.theme.TerminalPurple
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

object AnsiParser {

    private val ANSI_COLOR_MAP = mapOf(
        // Standard foreground (30-37)
        30 to Color(0xFF30363D),
        31 to TerminalRed,
        32 to TerminalGreen,
        33 to TerminalAmber,
        34 to TerminalCyan,
        35 to TerminalPurple,
        36 to Color(0xFF39C5BB),
        37 to TextPrimary,

        // High intensity foreground (90-97)
        90 to TextMuted,
        91 to Color(0xFFFF7B72),
        92 to TerminalGreenGlow,
        93 to Color(0xFFF2CC60),
        94 to Color(0xFF79C0FF),
        95 to Color(0xFFD2A8FF),
        96 to Color(0xFF56D4DD),
        97 to Color(0xFFFFFFFF),

        // Backgrounds (40-47)
        40 to Color(0xFF161B22),
        41 to Color(0xFF4C1010),
        42 to Color(0xFF0F3617),
        43 to Color(0xFF3C2C07),
        44 to Color(0xFF10284C),
        45 to Color(0xFF38144C),
        46 to Color(0xFF0C3335),
        47 to Color(0xFF30363D)
    )

    // Regex matches ANSI escape sequences: ESC [ <params> m OR cursor movement codes
    private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[([0-9;]*)([a-zA-Z])")

    fun parseLine(input: String): TerminalLine {
        if (!input.contains('\u001B')) {
            return TerminalLine.plain(input, TextPrimary)
        }

        val spans = mutableListOf<TextSpan>()
        var currentColor: Color = TextPrimary
        var currentBg: Color? = null
        var isBold = false
        var isUnderline = false
        var isDim = false

        var lastIndex = 0
        val matches = ANSI_ESCAPE_REGEX.findAll(input)

        for (match in matches) {
            val startIndex = match.range.first
            if (startIndex > lastIndex) {
                val textSegment = input.substring(lastIndex, startIndex)
                if (textSegment.isNotEmpty()) {
                    spans.add(
                        TextSpan(
                            text = textSegment,
                            color = currentColor,
                            backgroundColor = currentBg,
                            isBold = isBold,
                            isUnderline = isUnderline,
                            isDim = isDim
                        )
                    )
                }
            }

            val commandChar = match.groupValues[2]
            val paramsStr = match.groupValues[1]

            if (commandChar == "m") {
                // Color and style graphic rendition
                if (paramsStr.isEmpty() || paramsStr == "0") {
                    currentColor = TextPrimary
                    currentBg = null
                    isBold = false
                    isUnderline = false
                    isDim = false
                } else {
                    val codes = paramsStr.split(";").mapNotNull { it.toIntOrNull() }
                    for (code in codes) {
                        when (code) {
                            0 -> {
                                currentColor = TextPrimary
                                currentBg = null
                                isBold = false
                                isUnderline = false
                                isDim = false
                            }
                            1 -> isBold = true
                            2 -> isDim = true
                            4 -> isUnderline = true
                            22 -> { isBold = false; isDim = false }
                            24 -> isUnderline = false
                            39 -> currentColor = TextPrimary
                            49 -> currentBg = null
                            in 30..37, in 90..97 -> {
                                ANSI_COLOR_MAP[code]?.let { currentColor = it }
                            }
                            in 40..47, in 100..107 -> {
                                ANSI_COLOR_MAP[code]?.let { currentBg = it }
                            }
                        }
                    }
                }
            }
            // For other cursor movement codes (e.g., 'K', 'H', 'J', 'A', 'B'), we absorb without rendering artifacts

            lastIndex = match.range.last + 1
        }

        if (lastIndex < input.length) {
            val remainingText = input.substring(lastIndex)
            if (remainingText.isNotEmpty()) {
                spans.add(
                    TextSpan(
                        text = remainingText,
                        color = currentColor,
                        backgroundColor = currentBg,
                        isBold = isBold,
                        isUnderline = isUnderline,
                        isDim = isDim
                    )
                )
            }
        }

        return TerminalLine(spans)
    }
}
