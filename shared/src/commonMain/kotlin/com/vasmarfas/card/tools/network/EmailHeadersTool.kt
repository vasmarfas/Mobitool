package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.formatted
import com.vasmarfas.card.tools.developer.localDateTime
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadedFile
import com.vasmarfas.card.ui.components.LoadedFileCard
import com.vasmarfas.card.ui.components.LocalOpenTool
import com.vasmarfas.card.ui.components.OpenFileButton
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SelectableText
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

val emailHeadersTool = Tool(
    id = "email-headers",
    category = ToolCategory.NETWORK,
    title = Res.string.email_headers,
    description = Res.string.email_headers_description,
    icon = Icons.Filled.Email,
    keywords = listOf(
        "email", "mail", "headers", "eml", "received", "authentication-results", "arc", "spf", "dkim", "dmarc", "phishing", "spoofing",
        "attachments", "письмо", "почта", "заголовки", "фишинг", "подделка", "вложения",
    ),
) { EmailHeadersScreen() }

private val shownHeaders = listOf("Subject", "From", "To", "Cc", "Reply-To", "Return-Path", "Date", "Message-ID")

@Composable
private fun EmailHeadersScreen() {
    var typed by rememberSaveable { mutableStateOf("") }
    var file by remember { mutableStateOf<LoadedFile?>(null) }
    var opened by remember { mutableStateOf<MailMessage?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val tooLarge = stringResource(Res.string.email_file_too_large, EmailHeaders.MAX_BYTES / (1024 * 1024))
    val current = file
    if (current == null) {
        ToolInputField(
            value = typed,
            onValueChange = { typed = it },
            label = Res.string.email_headers_input.str(),
            singleLine = false,
            minLines = 6,
            placeholder = "Received: from …",
            monospace = true,
        )
    } else {
        LoadedFileCard(current) { file = null; opened = null }
    }
    OpenFileButton(setOf("eml", "txt")) { picked ->
        fileError = null
        if (picked.size() > EmailHeaders.MAX_BYTES) {
            fileError = tooLarge
        } else {
            val bytes = picked.readBytes()
            opened = withContext(Dispatchers.Default) { EmailHeaders.parse(bytes) }
            file = LoadedFile(picked.name, bytes)
        }
    }
    fileError?.let { ErrorText(it) }
    val pasted = remember(typed) { typed.trim().takeIf { it.isNotEmpty() }?.let { EmailHeaders.parse(it.encodeToByteArray()) } }
    val message = (if (current != null) opened else pasted) ?: return
    if (message.headers.isEmpty()) {
        ErrorText(Res.string.email_no_headers.str())
        return
    }
    SummaryCard(message)
    RouteCard(message)
    AuthCard(message)
    message.text?.let { text ->
        ResultCard(title = Res.string.body.str()) { SelectableText(text) }
    }
    if (message.attachments.isNotEmpty()) AttachmentsCard(message.attachments)
}

@Composable
private fun SummaryCard(message: MailMessage) {
    ResultCard {
        shownHeaders.forEach { name -> message.header(name)?.let { KeyValueRow(name, it, mono = false) } }
        val from = message.header("From")?.let(EmailHeaders::addressDomain).orEmpty()
        message.foreignDomains().forEach { (name, domain) ->
            Text(
                stringResource(if (name == "Reply-To") Res.string.email_reply_to_foreign else Res.string.email_return_path_foreign, domain, from),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RouteCard(message: MailMessage) {
    if (message.hops.isEmpty()) return
    ResultCard(title = Res.string.email_route.str()) {
        SimpleTable(
            header = listOf("#", Res.string.email_server.str(), Res.string.time.str(), Res.string.email_delay.str()),
            rows = message.hops.mapIndexed { index, hop ->
                listOf(
                    (index + 1).toString(),
                    hopNode(hop),
                    hop.time?.let { localDateTime(it * 1000).formatted() }.orEmpty(),
                    hop.delay?.let { delayText(it) }.orEmpty(),
                )
            },
            weights = listOf(0.35f, 2.6f, 1.5f, 1.1f),
            mono = false,
        )
        message.totalDelay?.let { KeyValueRow(Res.string.email_total_delay.str(), delayText(it), mono = false, copyable = false) }
    }
}

@Composable
private fun AuthCard(message: MailMessage) {
    ResultCard(title = Res.string.email_authentication.str()) {
        if (message.auth.isEmpty()) {
            Hint(Res.string.email_no_auth.str())
        } else {
            SimpleTable(
                header = listOf(Res.string.email_check.str(), Res.string.result.str(), Res.string.domain.str(), Res.string.email_checked_by.str()),
                rows = message.auth.map { listOf(it.method, it.result, it.domain, it.checkedBy) },
                weights = listOf(0.9f, 0.9f, 1.6f, 1.6f),
                mono = false,
            )
        }
        val openTool = LocalOpenTool.current
        message.signatures.forEach { signature ->
            KeyValueRow("DKIM-Signature", "d=${signature.domain} s=${signature.selector}")
            TextButton(
                onClick = {
                    Prefs.store.put(DKIM_HANDOFF_KEY, "${signature.domain} ${signature.selector}")
                    openTool("dkim")
                },
            ) { Text(Res.string.dkim_check_key.str()) }
        }
    }
}

@Composable
private fun AttachmentsCard(attachments: List<MailAttachment>) {
    val scope = rememberCoroutineScope()
    ResultCard(title = Res.string.email_attachments.str()) {
        attachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
                    Hint("${attachment.type} · ${formatBytes(attachment.bytes.size.toLong(), binary = false)}")
                }
                IconButton(onClick = { scope.launch { saveBytes(attachment.bytes, attachment.name) } }) {
                    Icon(Icons.Filled.Save, contentDescription = Res.string.save_file.str())
                }
            }
        }
    }
}

private fun hopNode(hop: MailHop): String {
    val source = listOf(hop.from, if (hop.ip.isEmpty()) "" else "[${hop.ip}]").filter { it.isNotEmpty() }.joinToString(" ")
    return listOf(hop.by, if (source.isEmpty()) "" else "from $source", if (hop.with.isEmpty()) "" else "with ${hop.with}")
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

@Composable
private fun delayText(seconds: Long): String {
    val size = abs(seconds)
    return (if (seconds < 0) "-" else "") + if (size == 0L) "0 ${Res.string.unit_s.str()}" else formatDurationMs(size * 1000)
}
