package com.siroha.flashtool.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.core.TerminalSession
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.OperationOverlay
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.UsbModeChip
import com.siroha.flashtool.ui.components.rememberUsbModeInfo
import com.siroha.flashtool.ui.theme.HapticIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A file name as it should appear on a command line: quoted when it has spaces or quotes. */
private fun asShellWord(name: String): String =
    if (name.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + name.replace("\"", "\\\"") + "\"" else name

/**
 * Termux-style terminal for people who know the PC commands: type
 * `fastboot flash boot boot.img`, `adb reboot recovery` or `adb shell` and it
 * runs against the connected phone through the app's built-in USB clients.
 *
 * The paperclip attaches ONE file and types its name into the command line,
 * exactly where the cursor is, so commands read like they do on a PC.
 *
 * The session (scrollback, attached file, history) is owned by the
 * navigation graph, so it survives leaving and re-entering this screen.
 */
@Composable
fun TerminalScreen(
    session: TerminalSession,
    logRepository: LogRepository,
    adbState: () -> String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lines by session.lines.collectAsState()
    val prompt by session.prompt.collectAsState()
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var running by remember { mutableStateOf(false) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    val usbMode by rememberUsbModeInfo(adbState)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var refocusAfterPicker by remember { mutableStateOf(false) }

    // Only this screen needs the window to resize for the keyboard (imePadding below does the
    // rest). Without it the system pans the window AND imePadding lifts the content: a huge gap.
    // The mode is re-applied on every resume and when the input gets focus, because coming back
    // from the file picker could leave the window in pan mode (the gap only showed up after attaching).
    val applyResizeMode = remember(context) {
        {
            context.findActivity()?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            Unit
        }
    }
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val previous = window?.attributes?.softInputMode
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) applyResizeMode() }
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(observer)
        applyResizeMode()
        onDispose {
            (activity as? LifecycleOwner)?.lifecycle?.removeObserver(observer)
            if (window != null && previous != null) window.setSoftInputMode(previous)
        }
    }

    // Keep the connection state honest while the screen is open: drop links to unplugged phones,
    // leave the device shell if the phone vanished, open ADB once per plug-in.
    LaunchedEffect(Unit) {
        while (isActive) {
            session.refreshConnections()
            delay(1000)
        }
    }

    // Follow the output unless the person scrolled up to read.
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) follow = scroll.value >= scroll.maxValue - 80
    }
    LaunchedEffect(scroll.maxValue, lines.size) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }

    fun setInput(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    /** Types [snippet] at the cursor, with a space before it when needed and one after. */
    fun insertAtCursor(snippet: String) {
        val v = input
        val before = v.text.substring(0, v.selection.min)
        val after = v.text.substring(v.selection.max)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val trail = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
        val inserted = lead + snippet + trail
        input = TextFieldValue(before + inserted + after, TextRange((before + inserted).length))
    }

    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        applyResizeMode()
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            applyResizeMode()
            val name = SafFiles.displayName(context, uri, "file")
            // The full path when it can be worked out (/storage/emulated/0/Download/x.img), like a path pasted in Termux.
            val path = SafFiles.resolveRealPath(context, uri) ?: name
            session.attach(name, uri, path)
            insertAtCursor(asShellWord(path))
        }
    }

    // The gap after attaching came from the keyboard being restored while the window was still
    // coming back from the file picker: the system panned the window for the (then) hidden input,
    // and imePadding lifted the content again on top of that. So the keyboard is closed BEFORE the
    // picker opens and only brought back once the screen is resumed, settled and in resize mode.
    fun openAttachPicker() {
        if (running) return
        refocusAfterPicker = inputFocused
        applyResizeMode()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        attachPicker.launch(arrayOf("*/*"))
    }

    LaunchedEffect(refocusAfterPicker) {
        if (!refocusAfterPicker) return@LaunchedEffect
        val owner = context.findActivity() as? LifecycleOwner
        while (isActive && owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(50)
        delay(350)
        applyResizeMode()
        runCatching { inputFocus.requestFocus() }
        keyboard?.show()
        refocusAfterPicker = false
    }

    fun submit() {
        val text = input.text.trim()
        if (text.isEmpty() || running) return
        setInput("")
        historyIndex = -1
        follow = true
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
        setInput(h[historyIndex])
    }

    fun historyDown() {
        val h = session.history
        if (historyIndex == -1) return
        if (historyIndex >= h.lastIndex) {
            historyIndex = -1
            setInput("")
        } else {
            historyIndex++
            setInput(h[historyIndex])
        }
    }

    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    val colorInput = MaterialTheme.colorScheme.primary
    val colorOutput = MaterialTheme.colorScheme.onSurface
    val colorError = MaterialTheme.colorScheme.error
    val colorInfo = MaterialTheme.colorScheme.onSurfaceVariant

    // ONE text block (not a row per line): selecting and copying then keeps the line breaks.
    val screenText = remember(lines, colorInput, colorOutput, colorError, colorInfo) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val color = when (line.kind) {
                    TerminalSession.Kind.INPUT -> colorInput
                    TerminalSession.Kind.OUTPUT -> colorOutput
                    TerminalSession.Kind.ERROR -> colorError
                    TerminalSession.Kind.INFO -> colorInfo
                }
                withStyle(SpanStyle(color = color)) { append(line.text) }
            }
        }
    }

    Scaffold(
        topBar = { SirohaTopBar("Terminal", icon = Icons.Filled.Terminal, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                // Live device mode - no need to leave the screen to see it change
                UsbModeChip(usbMode, modifier = Modifier.padding(start = 12.dp, top = 8.dp))

                // Scrollback
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                        SelectionContainer {
                            Text(screenText, style = mono, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // Extra keys, like Termux's row above the keyboard
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticIconButton(onClick = { openAttachPicker() }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp)
                            .focusRequester(inputFocus)
                            .onFocusChanged {
                                inputFocused = it.isFocused
                                if (it.isFocused) applyResizeMode()
                            }
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
