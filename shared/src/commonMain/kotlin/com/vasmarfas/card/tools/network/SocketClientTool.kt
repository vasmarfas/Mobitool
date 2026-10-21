package com.vasmarfas.card.tools.network

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val socketClientTool = Tool(
    id = "socket-client",
    category = ToolCategory.NETWORK,
    title = Res.string.socket_client,
    description = Res.string.socket_client_description,
    icon = Icons.Filled.Cable,
    keywords = listOf("netcat", "nc", "telnet", "tcp", "udp", "socket", "banner", "raw", "hex", "сокет", "порт", "баннер"),
    platforms = PlatformKind.native,
) { SocketClientScreen() }

private enum class SocketProtocol { TCP, UDP }

object SocketPayload {
    fun hex(text: String): ByteArray? {
        val digits = text.filterNot { it.isWhitespace() || it == ':' || it == '-' || it == ',' }.removePrefix("0x")
        if (digits.length % 2 != 0 || digits.any { it.digitToIntOrNull(16) == null }) return null
        return digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun text(text: String, crlf: Boolean): ByteArray =
        (if (crlf) text.replace("\r\n", "\n").replace("\n", "\r\n").let { if (it.endsWith("\r\n")) it else "$it\r\n" } else text).encodeToByteArray()

    fun dump(bytes: ByteArray): String = bytes.toList().chunked(16).withIndex().joinToString("\n") { (row, line) ->
        val hex = line.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.padEnd(47)
        val ascii = line.joinToString("") { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar().toString() else "." }
        "${(row * 16).toString(16).padStart(4, '0')}  $hex  $ascii"
    }
}

private class SocketResult(val bytes: ByteArray, val ms: Long)

@Composable
private fun SocketClientScreen() {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("80") }
    var protocol by rememberSaveable { mutableStateOf(SocketProtocol.TCP) }
    var payload by rememberSaveable { mutableStateOf("") }
    var asHex by rememberSaveable { mutableStateOf(false) }
    var crlf by rememberSaveable { mutableStateOf(true) }
    var timeout by rememberSaveable { mutableStateOf("3000") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SocketResult>?>(null) }
    val scope = rememberCoroutineScope()
    val badHex = asHex && payload.isNotBlank() && SocketPayload.hex(payload) == null

    ToolInputField(host, { host = it }, Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    SegmentedChoice(SocketProtocol.entries, protocol, { protocol = it }, { it.name })
    NumberField(port, { port = it }, Res.string.port.str())
    ToolInputField(
        payload,
        { payload = it },
        Res.string.socket_payload.str(),
        singleLine = false,
        minLines = 3,
        monospace = true,
        isError = badHex,
        placeholder = if (asHex) "48 45 4C 4C 4F" else "HEAD / HTTP/1.0",
    )
    SwitchRow(Res.string.socket_hex.str(), asHex, { asHex = it })
    if (!asHex) SwitchRow(Res.string.socket_crlf.str(), crlf, { crlf = it })
    NumberField(timeout, { timeout = it }, Res.string.socket_timeout.str(), suffix = Res.string.unit_ms.str())
    ActionButton(
        Res.string.socket_send.str(),
        onClick = {
            val target = hostFrom(host)
            val number = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@ActionButton
            val wait = timeout.toIntOrNull()?.coerceIn(200, 60_000) ?: 3_000
            val bytes = if (asHex) SocketPayload.hex(payload) ?: return@ActionButton else if (payload.isEmpty()) ByteArray(0) else SocketPayload.text(payload, crlf)
            running = true
            scope.launch {
                result = runCatching {
                    val started = currentEpochMillis()
                    val answer = when (protocol) {
                        SocketProtocol.TCP -> tcpExchange(target, number, bytes, wait)
                        SocketProtocol.UDP -> udpQuery(target, number, bytes, wait) ?: ByteArray(0)
                    }
                    SocketResult(answer, currentEpochMillis() - started)
                }
                running = false
            }
        },
        enabled = host.isNotBlank() && !running && !badHex,
    )
    Hint(Res.string.socket_hint.str())
    if (running) LoadingRow()
    val outcome = result ?: return
    outcome.exceptionOrNull()?.let { ErrorText(Res.string.socket_failed.str()) }
    val answer = outcome.getOrNull() ?: return
    ResultCard(stringResource(Res.string.socket_received, answer.bytes.size.toString(), answer.ms.toString())) {
        if (answer.bytes.isEmpty()) {
            Hint(Res.string.socket_empty.str())
        } else {
            KeyValueRow(Res.string.socket_as_text.str(), TextDecoding.decode(answer.bytes))
            SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                Text(SocketPayload.dump(answer.bytes), style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()), softWrap = false)
            }
        }
    }
}
