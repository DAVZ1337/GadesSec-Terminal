package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitationsDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier
            .testTag("limitations_dialog")
            .clip(RoundedCornerShape(12.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Security Notice",
                    tint = TerminalAmber,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Android Sandbox & Limits",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
            }

            // Section 1: PRoot vs Virtual Machine
            LimitItem(
                title = "1. PRoot is NOT a Virtual Machine",
                color = TerminalCyan,
                body = "PRoot uses ptrace/seccomp to intercept and emulate system calls in user-space. It is not KVM, QEMU, or a hardware hypervisor. It shares the host Android Linux kernel and cannot bypass kernel security domains."
            )

            // Section 2: Kernel Modules & Raw Sockets
            LimitItem(
                title = "2. No Kernel Modules & Raw Sockets",
                color = TerminalAmber,
                body = "Without root access on Android, the app cannot load kernel modules (iptables, wireguard.ko) or create raw network sockets (AF_RAW/AF_PACKET). Stealth SYN port scans (nmap -sS) fail without root; unprivileged TCP connect scans (nmap -sT) work normally."
            )

            // Section 3: Scoped Tor & Banking Apps
            LimitItem(
                title = "3. Scoped Tor Isolation",
                color = TerminalGreen,
                body = "Tor runs on local port 127.0.0.1:9050 and is configured solely for GadesSecOS. It does NOT route traffic from your banking, browser, or other apps. Your daily applications maintain direct, unmodified connections."
            )

            // Section 4: DNS Leaks & Proxychains
            LimitItem(
                title = "4. DNS Leaks & Proxychains Reality",
                color = TerminalRed,
                body = "Proxychains hooks glibc getaddrinfo() via LD_PRELOAD. Statically linked binaries (e.g. Go/Rust) bypass LD_PRELOAD and can leak DNS queries. GadesSecOS enforces socks5h:// remote DNS and monitors fail-closed state to minimize exposure."
            )

            // Dismiss Button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dismiss_limitations_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberSurfaceVariant,
                    contentColor = TerminalGreen
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
            ) {
                Text(
                    text = "I Understand & Accept Limits",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun LimitItem(
    title: String,
    color: Color,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CyberSurfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color
        )
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 16.sp
        )
    }
}
