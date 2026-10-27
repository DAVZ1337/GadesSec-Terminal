package com.example.model

import androidx.compose.ui.graphics.Color

data class TextSpan(
    val text: String,
    val color: Color? = null,
    val backgroundColor: Color? = null,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false,
    val isDim: Boolean = false
)

data class TerminalLine(
    val spans: List<TextSpan> = emptyList(),
    val isBanner: Boolean = false
) {
    val rawText: String
        get() = spans.joinToString("") { it.text }

    companion object {
        fun plain(text: String, color: Color? = null): TerminalLine {
            return TerminalLine(listOf(TextSpan(text = text, color = color)))
        }

        fun banner(text: String, color: Color? = null, isBold: Boolean = true): TerminalLine {
            return TerminalLine(listOf(TextSpan(text = text, color = color, isBold = isBold)), isBanner = true)
        }
    }
}
