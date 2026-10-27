package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.TerminalLine
import com.example.terminal.TerminalViewModel
import com.example.ui.components.KeyboardToolbar
import com.example.ui.components.LimitationsDialog
import com.example.ui.components.TerminalTopBar
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CursorGreen
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val clipboardManager = LocalClipboardManager.current

    // Auto-scroll to bottom on new output lines
    LaunchedEffect(uiState.lines.size) {
        if (uiState.lines.isNotEmpty()) {
            listState.animateScrollToItem(uiState.lines.size - 1)
        }
    }

    // Auto-focus terminal on launch
    LaunchedEffect(Unit) {
        delay(200)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Handle system back navigation gracefully
    androidx.activity.compose.BackHandler(enabled = uiState.showLimitationsDialog || uiState.currentInput.isNotEmpty()) {
        if (uiState.showLimitationsDialog) {
            viewModel.toggleLimitationsDialog(false)
        } else if (uiState.currentInput.isNotEmpty()) {
            viewModel.onInputChange("")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CyberBackground,
        topBar = {
            TerminalTopBar(
                isTorEnabled = uiState.isTorEnabled,
                isKillswitchEngaged = uiState.isKillswitchEngaged,
                onToggleTor = { viewModel.toggleTor() },
                onOpenLimitations = { viewModel.toggleLimitationsDialog(true) }
            )
        },
        bottomBar = {
            KeyboardToolbar(
                isCtrlActive = uiState.isCtrlActive,
                isAltActive = uiState.isAltActive,
                onToggleCtrl = { viewModel.toggleCtrl() },
                onToggleAlt = { viewModel.toggleAlt() },
                onSpecialKey = { key ->
                    if (key == "PASTE") {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrEmpty()) {
                            viewModel.onPaste(clip)
                        }
                    } else {
                        viewModel.handleSpecialKey(key)
                    }
                },
                modifier = Modifier.imePadding()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(CyberBackground)
        ) {
            // Termux-style SelectionContainer: enables long-press text selection, copy, and share on any output line
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("terminal_output_list")
                ) {
                    items(uiState.lines) { line ->
                        TerminalLineView(line = line)
                    }

                    // Interactive Prompt & Input Row
                    item {
                        TerminalInputRow(
                            prompt = uiState.prompt,
                            input = uiState.currentInput,
                            onInputChange = { viewModel.onInputChange(it) },
                            onExecute = { viewModel.executeCurrentCommand() },
                            focusRequester = focusRequester
                        )
                    }
                }
            }
        }
    }

    // Android Limitations Transparency Modal
    if (uiState.showLimitationsDialog) {
        LimitationsDialog(
            onDismiss = { viewModel.toggleLimitationsDialog(false) }
        )
    }
}

@Composable
private fun TerminalLineView(line: TerminalLine) {
    val annotatedString = buildAnnotatedString {
        line.spans.forEach { span ->
            val style = SpanStyle(
                color = span.color ?: TextPrimary,
                background = span.backgroundColor ?: androidx.compose.ui.graphics.Color.Transparent,
                fontWeight = if (span.isBold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (span.isUnderline) TextDecoration.Underline else TextDecoration.None
            )
            withStyle(style) {
                append(span.text)
            }
        }
    }

    if (line.isBanner) {
        val bannerScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(bannerScrollState)
        ) {
            Text(
                text = annotatedString,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                softWrap = false
            )
        }
    } else {
        Text(
            text = annotatedString,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 16.5.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TerminalInputRow(
    prompt: String,
    input: String,
    onInputChange: (String) -> Unit,
    onExecute: () -> Unit,
    focusRequester: FocusRequester
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = prompt,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
            color = TerminalGreen
        )

        Spacer(modifier = Modifier.width(4.dp))

        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .testTag("terminal_input_field"),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                color = TextPrimary
            ),
            cursorBrush = SolidColor(CursorGreen),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrect = false
            ),
            keyboardActions = KeyboardActions(
                onSend = { onExecute() },
                onDone = { onExecute() }
            ),
            decorationBox = { innerTextField ->
                innerTextField()
            }
        )
    }
}
