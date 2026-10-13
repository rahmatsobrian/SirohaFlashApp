package com.siroha.flashtool.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.core.TerminalSession
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.OperationOverlay
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.theme.HapticIconButton
import kotlinx.coroutines.launch

/**
 * Termux-style terminal for people who know the PC commands: type
 * `fastboot flash boot boot.img`, `adb reboot recovery` or `adb shell` and it
 * runs against the connected phone through the app's built-in USB clients.
 * Files are attached with the paperclip and referred to by name.
 *
 * The session (scrollback, attachments, history) is owned by the navigation
 * graph, so it survives leaving and re-entering this screen.
 */
@Composable
fun TerminalScreen(
    session: TerminalSession,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lines by session.lines.collectAsState()
    val attachments by session.attachments.collectAsState()
    val prompt by session.prompt.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            session.attach(SafFiles.displayName(context, uri, "file"), uri)
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || running) return
        input = ""
        historyIndex = -1
        scope.launch {
            running = true
            try {
                session.execute(text)
            } finally {
                running = false
            }
        }
    }

    fun historyUp() {
        val h = session.history
        if (h.isEmpty()) return
        historyIndex = if (historyIndex == -1) h.lastIndex else (historyIndex - 1).coerceAtLeast(0)
        input = h[historyIndex]
    }

    fun historyDown() {
        val h = session.history
        if (historyIndex == -1) return
        if (historyIndex >= h.lastIndex) {
            historyIndex = -1
            input = ""
        } else {
            historyIndex++
            input = h[historyIndex]
        }
    }

    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

    Scaffold(
        topBar = { SirohaTopBar("Terminal", icon = Icons.Filled.Terminal, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                // Scrollback
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                ) {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            items(lines.size) { index ->
                                val line = lines[index]
                                Text(
                                    line.text.ifEmpty { " " },
                                    style = mono,
                                    color = when (line.kind) {
                                        TerminalSession.Kind.INPUT -> MaterialTheme.colorScheme.primary
                                        TerminalSession.Kind.OUTPUT -> MaterialTheme.colorScheme.onSurface
                                        TerminalSession.Kind.ERROR -> MaterialTheme.colorScheme.error
                                        TerminalSession.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // Attached files: "the current folder"
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEach { file ->
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 180.dp)
                                    )
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Detach ${file.name}",
                                        modifier = Modifier.size(16.dp).clickable { session.detach(file.name) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Extra keys, like Termux's row above the keyboard
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticIconButton(onClick = { if (!running) attachPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach files")
                    }
                    HapticIconButton(onClick = { historyUp() }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous command")
                    }
                    HapticIconButton(onClick = { historyDown() }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next command")
                    }
                    HapticIconButton(onClick = {
                        clipboard.setText(AnnotatedString(session.transcript()))
                        scope.launch { snackbarHostState.showSnackbar("Copied the whole terminal") }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy everything")
                    }
                    HapticIconButton(onClick = { session.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear screen")
                    }
                }

                // Prompt + input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        prompt,
                        style = mono.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp)
                    )
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        enabled = !running,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // Password keyboard = no autocorrect / suggestions, the same trick terminal apps use.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                    )
                    HapticIconButton(onClick = { submit() }) {
                        Icon(Icons.Filled.Send, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            OperationOverlay(
                busy = running,
                logRepository = logRepository,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
