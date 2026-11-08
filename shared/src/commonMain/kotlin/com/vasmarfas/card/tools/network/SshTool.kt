package com.vasmarfas.card.tools.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.SshAuth
import com.vasmarfas.card.core.SshShell
import com.vasmarfas.card.core.pickFiles
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.ssh.openSshShell
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val TERMINAL_ROWS = 24
private const val MIN_COLUMNS = 20
private const val MEASURED_CHARS = 100
private val TERMINAL_PADDING = 8.dp
private val TERMINAL_BACKGROUND = Color(0xFF1E1E1E)
private val TERMINAL_FOREGROUND = Color(0xFFD4D4D4)

val sshClientTool = Tool(
    id = "ssh-client",
    category = ToolCategory.NETWORK,
    title = Res.string.ssh_client,
    description = Res.string.ssh_client_description,
    icon = Icons.Filled.Terminal,
    keywords = listOf("ssh", "terminal", "shell", "console", "putty", "openssh", "remote", "терминал", "консоль", "сервер"),
    platforms = PlatformKind.native,
) { SshScreen() }

private enum class SshAuthMode { PASSWORD, KEY }

private class SshConnection(val shell: SshShell, val terminal: AnsiTerminal)

private class SshHostPrompt(val id: String, val seen: SshHostEntry, val saved: SshHostEntry?)

@Serializable
data class SshServer(val host: String, val port: Int, val user: String)

private object SshServers {
    private const val KEY = "ssh.servers"
    private val serializer = ListSerializer(SshServer.serializer())

    fun load(): List<SshServer> = runCatching { Prefs.store.get(KEY)?.let { Net.json.decodeFromString(serializer, it) } }.getOrNull() ?: emptyList()

    fun save(list: List<SshServer>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, list))
}

@Composable
private fun SshScreen() {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SshAuthMode.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf<String?>(null) }
    var keyPem by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf(SshServers.load()) }
    var connection by remember { mutableStateOf<SshConnection?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<SshHostPrompt?>(null) }
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily())
    val sample = remember(measurer, style) { measurer.measure("0".repeat(MEASURED_CHARS), style).size }
    val density = LocalDensity.current
    val number = port.toIntOrNull()?.takeIf { it in 1..65535 }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inner = constraints.maxWidth - with(density) { TERMINAL_PADDING.roundToPx() } * 2
        val columns = (inner * MEASURED_CHARS / sample.width).coerceAtLeast(MIN_COLUMNS)

        fun connect() {
            val target = hostFrom(host)
            val portNumber = number ?: return
            val id = SshKnownHosts.id(target, portNumber)
            val known = SshKnownHosts.load()
            val auth = if (mode == SshAuthMode.KEY) SshAuth.PrivateKey(keyPem.orEmpty(), passphrase) else SshAuth.Password(password)
            connecting = true
            error = null
            scope.launch {
                var rejected: SshHostEntry? = null
                val result = runCatching {
                    openSshShell(target, portNumber, user.trim(), auth, columns, TERMINAL_ROWS) { key ->
                        val seen = SshHostEntry(key.type, SshKnownHosts.fingerprint(key.blob))
                        val trusted = SshKnownHosts.status(known, id, seen.fingerprint) == SshHostStatus.TRUSTED
                        if (!trusted) rejected = seen
                        trusted
                    }
                }
                result.onSuccess { connection = SshConnection(it, AnsiTerminal(columns, TERMINAL_ROWS)) }
                val seen = rejected
                if (seen != null) {
                    prompt = SshHostPrompt(id, seen, known[id])
                } else {
                    result.exceptionOrNull()?.let { error = networkErrorText(it) }
                }
                connecting = false
            }
        }

        val open = connection
        if (open != null) {
            SshTerminal(open, columns, style, with(density) { (sample.height * TERMINAL_ROWS).toDp() }) { connection = null }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ToolInputField(host, { host = it }, Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolInputField(user, { user = it }, Res.string.ssh_user.str(), modifier = Modifier.weight(2f), monospace = true)
                    NumberField(port, { port = it }, Res.string.port.str(), modifier = Modifier.weight(1f), isError = number == null)
                }
                SegmentedChoice(
                    SshAuthMode.entries,
                    mode,
                    { mode = it },
                    { if (it == SshAuthMode.KEY) Res.string.ssh_auth_key.str() else Res.string.ssh_auth_password.str() },
                )
                if (mode == SshAuthMode.KEY) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val file = runCatching { pickFiles(emptySet()) }.getOrDefault(emptyList()).firstOrNull() ?: return@launch
                                runCatching { file.readBytes().decodeToString() }
                                    .onSuccess {
                                        keyPem = it
                                        keyName = file.name
                                    }
                                    .onFailure { error = getString(Res.string.ssh_error_key) }
                            }
                        },
                    ) { Text(Res.string.ssh_key_choose.str()) }
                    keyName?.let { Hint(stringResource(Res.string.ssh_key_chosen, it)) }
                    SecretField(passphrase, { passphrase = it }, Res.string.ssh_passphrase.str())
                } else {
                    SecretField(password, { password = it }, Res.string.ssh_auth_password.str())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        Res.string.ssh_connect.str(),
                        onClick = { connect() },
                        enabled = host.isNotBlank() && user.isNotBlank() && number != null && !connecting && (mode == SshAuthMode.PASSWORD || keyPem != null),
                    )
                    TextButton(
                        onClick = {
                            val server = SshServer(hostFrom(host), number!!, user.trim())
                            servers = servers.filter { it != server } + server
                            SshServers.save(servers)
                        },
                        enabled = host.isNotBlank() && user.isNotBlank() && number != null,
                    ) { Text(Res.string.ssh_remember.str()) }
                }
                Hint(Res.string.ssh_hint.str())
                if (connecting) LoadingRow(Res.string.ssh_connecting.str())
                error?.let { ErrorText(it) }
                if (servers.isNotEmpty()) {
                    ResultCard(Res.string.ssh_saved.str()) {
                        servers.forEach { server ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${server.user}@${server.host}:${server.port}", Modifier.weight(1f), style = style)
                                TextButton(
                                    onClick = {
                                        host = server.host
                                        port = server.port.toString()
                                        user = server.user
                                    },
                                ) { Text(Res.string.ssh_use.str()) }
                                IconButton(
                                    onClick = {
                                        servers = servers - server
                                        SshServers.save(servers)
                                    },
                                ) { Icon(Icons.Filled.Close, Res.string.remove.str()) }
                            }
                        }
                    }
                }
            }
        }

        prompt?.let { asked ->
            val saved = asked.saved
            if (saved == null) {
                AlertDialog(
                    onDismissRequest = { prompt = null },
                    title = { Text(Res.string.ssh_unknown_host.str()) },
                    text = { Text(stringResource(Res.string.ssh_unknown_host_text, asked.id, asked.seen.type, asked.seen.fingerprint)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                SshKnownHosts.save(SshKnownHosts.load() + (asked.id to asked.seen))
                                prompt = null
                                connect()
                            },
                        ) { Text(Res.string.ssh_trust.str()) }
                    },
                    dismissButton = { TextButton(onClick = { prompt = null }) { Text(Res.string.cancel.str()) } },
                )
            } else {
                AlertDialog(
                    onDismissRequest = { prompt = null },
                    title = { Text(Res.string.ssh_changed_host.str()) },
                    text = { Text(stringResource(Res.string.ssh_changed_text, asked.id, saved.fingerprint, asked.seen.fingerprint)) },
                    confirmButton = { TextButton(onClick = { prompt = null }) { Text(Res.string.ssh_keep_key.str()) } },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                SshKnownHosts.save(SshKnownHosts.load() - asked.id)
                                prompt = null
                            },
                        ) { Text(Res.string.ssh_forget_key.str()) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

// the hidden field always holds this one character, so a backspace on an empty line still has something to erase
private const val SENTINEL = " "

private val CTRL_LETTERS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)

private val FUNCTION_KEYS = mapOf(
    Key.F1 to "\u001bOP", Key.F2 to "\u001bOQ", Key.F3 to "\u001bOR", Key.F4 to "\u001bOS",
    Key.F5 to "\u001b[15~", Key.F6 to "\u001b[17~", Key.F7 to "\u001b[18~", Key.F8 to "\u001b[19~",
    Key.F9 to "\u001b[20~", Key.F10 to "\u001b[21~", Key.F11 to "\u001b[23~", Key.F12 to "\u001b[24~",
)

private fun emptyField() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

@Composable
private fun SshTerminal(connection: SshConnection, columns: Int, style: TextStyle, height: Dp, onLeave: () -> Unit) {
    val terminal = connection.terminal
    val shell = connection.shell
    var revision by remember { mutableIntStateOf(0) }
    var ended by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(emptyField()) }
    var typing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lineCount = remember(revision) { terminal.lineCount }

    fun send(keys: String) {
        scope.launch { runCatching { shell.write(keys.encodeToByteArray()) } }
    }

    fun openKeyboard() {
        focus.requestFocus()
        keyboard?.show()
    }

    fun press(keys: String) {
        send(keys)
        focus.requestFocus()
    }

    fun arrow(key: TerminalKey) = press(TerminalKeys.arrow(key, terminal.applicationCursor))

    // whatever the keyboard typed or erased in the hidden field since the last change goes out at once,
    // a keyboard still composing a word keeps its text until it commits
    fun edit(next: TextFieldValue) {
        val before = field.text
        val common = before.commonPrefixWith(next.text).length
        val typed = next.text.substring(common).replace('\n', '\r')
        val control = if (ctrl && typed.length == 1) TerminalKeys.control(typed[0]) else null
        if (control != null) ctrl = false
        val keys = TerminalKeys.BACKSPACE.repeat(before.length - common) + (control?.let { Char(it.toInt()).toString() } ?: typed)
        if (keys.isNotEmpty()) send(keys)
        field = if (next.composition == null) emptyField() else next
    }

    DisposableEffect(shell) { onDispose { shell.close() } }
    LaunchedEffect(shell) {
        val buffer = ByteArray(8192)
        runCatching {
            while (true) {
                val count = shell.read(buffer)
                if (count < 0) break
                terminal.feed(buffer, count)
                terminal.takeReplies().takeIf { it.isNotEmpty() }?.let { shell.write(it.encodeToByteArray()) }
                revision++
            }
        }
        ended = true
    }
    LaunchedEffect(columns) {
        terminal.resize(columns)
        runCatching { shell.resize(columns, TERMINAL_ROWS) }
    }
    LaunchedEffect(revision) { list.scrollToItem(lineCount - 1) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // a press inside SelectionContainer focuses the selection, so after a plain click, one without a drag
        // or a long press, focus goes back to the hidden field. A selection keeps it, and Ctrl+C copies it
        Box(
            Modifier.pointerInput(ended) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var click = true
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                        val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (moved || change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) click = false
                        if (!change.pressed) break
                    }
                    if (click && !ended) openKeyboard()
                }
            },
        ) {
            SelectionContainer {
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height + TERMINAL_PADDING * 2)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TERMINAL_BACKGROUND)
                        .border(1.dp, if (typing) MaterialTheme.colorScheme.primary else TERMINAL_BACKGROUND, RoundedCornerShape(8.dp))
                        .padding(TERMINAL_PADDING),
                ) {
                    items(lineCount) { index ->
                        val line = remember(revision, index) { terminalLine(terminal.runs(index)) }
                        Text(line, style = style, color = TERMINAL_FOREGROUND, softWrap = false, maxLines = 1)
                    }
                }
            }
            if (!ended) {
                BasicTextField(
                    value = field,
                    onValueChange = { edit(it) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focus)
                        .onFocusChanged { typing = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            val keys = if (event.type == KeyEventType.KeyDown) hardwareKey(event, terminal.applicationCursor) else null
                            keys?.let { send(it) }
                            keys != null
                        },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    cursorBrush = SolidColor(Color.Transparent),
                )
            }
        }
        if (ended) {
            Hint(Res.string.ssh_closed.str())
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = ctrl,
                    onClick = {
                        ctrl = !ctrl
                        focus.requestFocus()
                    },
                    label = { Text("Ctrl") },
                )
                KeyButton({ press(TerminalKeys.ESCAPE) }) { Text("Esc") }
                KeyButton({ press("\t") }) { Text("Tab") }
                KeyButton({ arrow(TerminalKey.LEFT) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Res.string.ssh_key_left.str()) }
                KeyButton({ arrow(TerminalKey.UP) }) { Icon(Icons.Filled.ArrowUpward, Res.string.ssh_key_up.str()) }
                KeyButton({ arrow(TerminalKey.DOWN) }) { Icon(Icons.Filled.ArrowDownward, Res.string.ssh_key_down.str()) }
                KeyButton({ arrow(TerminalKey.RIGHT) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, Res.string.ssh_key_right.str()) }
                KeyButton({ press(TerminalKeys.BACKSPACE) }) { Icon(Icons.AutoMirrored.Filled.Backspace, Res.string.ssh_key_backspace.str()) }
            }
            Hint(Res.string.ssh_input_hint.str())
        }
        ActionButton((if (ended) Res.string.ssh_new_connection else Res.string.ssh_disconnect).str(), onClick = onLeave)
    }
}

@Composable
private fun KeyButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp), content = content)
}

// keys a text field would not pass on as text. Ctrl+V and Ctrl+Shift combinations stay with the field, so paste works
private fun hardwareKey(event: KeyEvent, application: Boolean): String? {
    if (event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key != Key.V) {
        val letter = CTRL_LETTERS.indexOf(event.key)
        if (letter >= 0) return Char(letter + 1).toString()
    }
    if (event.isMetaPressed) return null
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> "\r"
        Key.Tab -> "\t"
        Key.Escape -> TerminalKeys.ESCAPE
        Key.Backspace -> TerminalKeys.BACKSPACE
        Key.Delete -> "\u001b[3~"
        Key.Insert -> "\u001b[2~"
        Key.MoveHome -> if (application) "\u001bOH" else "\u001b[H"
        Key.MoveEnd -> if (application) "\u001bOF" else "\u001b[F"
        Key.PageUp -> "\u001b[5~"
        Key.PageDown -> "\u001b[6~"
        Key.DirectionUp -> TerminalKeys.arrow(TerminalKey.UP, application)
        Key.DirectionDown -> TerminalKeys.arrow(TerminalKey.DOWN, application)
        Key.DirectionLeft -> TerminalKeys.arrow(TerminalKey.LEFT, application)
        Key.DirectionRight -> TerminalKeys.arrow(TerminalKey.RIGHT, application)
        else -> FUNCTION_KEYS[event.key]
    }
}

private fun terminalColor(color: Int, fallback: Color): Color =
    if (color == TerminalStyle.DEFAULT_COLOR) fallback else Color(0xFF000000 or TerminalPalette.rgb(color).toLong())

private fun terminalLine(runs: List<TerminalRun>): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val foreground = terminalColor(run.style.foreground, TERMINAL_FOREGROUND)
        val background = terminalColor(run.style.background, TERMINAL_BACKGROUND)
        val span = SpanStyle(
            color = if (run.style.inverse) background else foreground,
            background = if (run.style.inverse) foreground else background,
            fontWeight = if (run.style.bold) FontWeight.Bold else null,
            textDecoration = if (run.style.underline) TextDecoration.Underline else null,
        )
        withStyle(span) { append(run.text) }
    }
}
