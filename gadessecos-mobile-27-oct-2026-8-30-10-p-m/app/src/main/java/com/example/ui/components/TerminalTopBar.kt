package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun TerminalTopBar(
    isTorEnabled: Boolean,
    isKillswitchEngaged: Boolean,
    onToggleTor: () -> Unit,
    onOpenLimitations: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CyberSurface)
            .border(width = 1.dp, color = CyberBorderSubtle)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Minimalist Prompt Brand
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = ">_",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = TerminalGreen
            )
            Text(
                text = "gadessec",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = TextPrimary
            )
        }

        // Minimalist Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Tor Status (Minimalist pill)
            val torColor = when {
                isTorEnabled && isKillswitchEngaged -> TerminalGreen
                isTorEnabled -> TerminalCyan
                else -> TextMuted
            }
            val torLabel = when {
                isTorEnabled && isKillswitchEngaged -> "tor:on"
                isTorEnabled -> "tor:on"
                else -> "tor:off"
            }

            Box(
                modifier = Modifier
                    .testTag("tor_status_pill")
                    .clip(RoundedCornerShape(4.dp))
                    .background(CyberSurfaceVariant)
                    .border(1.dp, torColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable { onToggleTor() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(torColor)
                    )
                    Text(
                        text = torLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = torColor
                    )
                }
            }

            // Android Sandbox Info
            IconButton(
                onClick = onOpenLimitations,
                modifier = Modifier
                    .testTag("info_limitations_button")
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Sandbox Info",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
