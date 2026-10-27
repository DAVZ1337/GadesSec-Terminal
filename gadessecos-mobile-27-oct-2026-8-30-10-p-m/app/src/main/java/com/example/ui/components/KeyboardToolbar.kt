package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenGlow
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TextPrimary

@Composable
fun KeyboardToolbar(
    isCtrlActive: Boolean,
    isAltActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSpecialKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Termux-inspired ergonomic virtual terminal accessory bar
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CyberSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Execute / Enter
        AccessoryKey(
            label = "⏎",
            color = TerminalGreenGlow,
            isActive = true,
            activeColor = TerminalGreen,
            onClick = { onSpecialKey("ENTER") },
            testTag = "key_enter"
        )

        // Clipboard Paste button (Termux style)
        AccessoryKey(
            label = "PASTE",
            color = TerminalAmber,
            onClick = { onSpecialKey("PASTE") },
            testTag = "key_paste"
        )

        // Sticky CTRL Key
        AccessoryKey(
            label = "CTRL",
            isActive = isCtrlActive,
            activeColor = TerminalCyan,
            onClick = onToggleCtrl,
            testTag = "key_ctrl"
        )

        // Sticky ALT Key
        AccessoryKey(
            label = "ALT",
            isActive = isAltActive,
            activeColor = TerminalCyan,
            onClick = onToggleAlt,
            testTag = "key_alt"
        )

        // ESC Key
        AccessoryKey(
            label = "ESC",
            onClick = { onSpecialKey("ESC") },
            testTag = "key_esc"
        )

        // TAB Key
        AccessoryKey(
            label = "TAB",
            onClick = { onSpecialKey("TAB") },
            testTag = "key_tab"
        )

        // Direct Ctrl+C (Interrupt process)
        AccessoryKey(
            label = "^C",
            color = TerminalRed,
            onClick = { onSpecialKey("CTRL+C") },
            testTag = "key_ctrl_c"
        )

        // History Navigation Arrows
        AccessoryKey(
            label = "▲",
            onClick = { onSpecialKey("UP") },
            testTag = "key_arrow_up"
        )

        AccessoryKey(
            label = "▼",
            onClick = { onSpecialKey("DOWN") },
            testTag = "key_arrow_down"
        )

        // Common Unix CLI symbols
        listOf("|", "/", "-", "~", "_", "$", "@").forEach { symbol ->
            AccessoryKey(
                label = symbol,
                onClick = { onSpecialKey(symbol) },
                testTag = "key_symbol_$symbol"
            )
        }

        // Direct Ctrl+L (Clear screen)
        AccessoryKey(
            label = "^L",
            onClick = { onSpecialKey("CTRL+L") },
            testTag = "key_ctrl_l"
        )
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeColor: Color = TerminalGreen,
    color: Color = TextPrimary,
    testTag: String
) {
    val bg = if (isActive) activeColor.copy(alpha = 0.22f) else CyberSurfaceVariant
    val borderCol = if (isActive) activeColor else CyberBorderSubtle

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .testTag(testTag)
            .height(36.dp)
            .widthIn(min = 40.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = if (isActive) activeColor else color
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderCol),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}
