package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Http
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.JsonTools
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.rememberCopy
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val httpRequestTool = Tool(
    id = "http-request",
    category = ToolCategory.NETWORK,
    title = Res.string.http_request,
    description = Res.string.http_request_description,
    icon = Icons.Filled.Http,
    keywords = listOf("rest", "api", "curl", "postman", "headers", "json", "запрос", "апи", "заголовки", "куки"),
) { HttpRequestScreen() }

private enum class ResponseTab(val label: StringResource) {
    BODY(Res.string.body_mode),
    HEADERS(Res.string.headers),
    COOKIES(Res.string.cookies),
}

private const val MAX_VISIBLE_CHARS = 100_000

private val webHeadersNote = Res.string.request_in_the_browser

private val webRedirectNote = Res.string.request_browser_follows_redirects

@Composable
private fun HttpRequestScreen() {
    val corsNoteText = Res.string.net_in_the_browser_requests.str()
    var spec by remember { mutableStateOf(HttpRequestSpec()) }
    var timeoutText by rememberSaveable { mutableStateOf((HttpRequests.DEFAULT_TIMEOUT_MS / 1000).toString()) }
    var curlText by rememberSaveable { mutableStateOf("") }
    var curlFailed by remember { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(HttpRequestStore.history()) }
    var saved by remember { mutableStateOf(HttpRequestStore.saved()) }
    var tab by rememberSaveable { mutableStateOf(ResponseTab.BODY) }
    var formatted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<HttpExchange?>(null) }
    val scope = rememberCoroutineScope()
    val copy = rememberCopy()

    fun load(next: HttpRequestSpec) {
        spec = HttpRequests.synced(next)
        timeoutText = (next.timeoutMs / 1000).toString()
    }

    fun send() {
        val request = HttpRequests.synced(spec)
        spec = request
        loading = true
        error = null
        result = null
        formatted = false
        scope.launch {
            runCatching { HttpRequests.execute(request) }
                .onSuccess {
                    result = it
                    tab = ResponseTab.BODY
                    history = HttpRequestStore.push(history, HttpHistoryEntry(request, it.status, it.timeMs))
                    HttpRequestStore.saveHistory(history)
                }
                .onFailure {
                    val message = networkErrorText(it)
                    error = if (currentPlatform == PlatformKind.WEB) "$message\n${corsNoteText}" else message
                }
            loading = false
        }
    }

    ToolInputField(
        value = spec.url,
        onValueChange = { spec = spec.copy(url = it, query = HttpQuery.sync(it, spec.query)) },
        label = "URL",
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    ChoiceChips(options = httpMethods, selected = spec.method, onSelect = { spec = spec.copy(method = it) }, label = { it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(
            text = Res.string.send.str(),
            onClick = ::send,
            enabled = !loading && spec.url.isNotBlank(),
            icon = Icons.AutoMirrored.Filled.Send,
        )
        TextButton(onClick = { copy(Curl.build(HttpRequests.synced(spec))) }) {
            Text(Res.string.copy_as_curl.str())
        }
    }
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    result?.let { exchange ->
        ResponseSection(
            exchange = exchange,
            tab = tab,
            onTab = { tab = it },
            formatted = formatted,
            onFormat = { formatted = true },
        )
    }
    ToolSection(Res.string.request_query_parameters.str()) {
        FieldsEditor(
            fields = spec.query,
            keyLabel = Res.string.name.str(),
            valueLabel = Res.string.value_.str(),
        ) { spec = spec.copy(query = it, url = HttpQuery.apply(spec.url, it)) }
    }
    ToolSection(Res.string.headers.str()) {
        FieldsEditor(
            fields = spec.headers,
            keyLabel = Res.string.name.str(),
            valueLabel = Res.string.value_.str(),
        ) { spec = spec.copy(headers = it) }
    }
    ToolSection(Res.string.request_body.str()) {
        SegmentedChoice(
            options = HttpBodyMode.entries,
            selected = spec.bodyMode,
            onSelect = { spec = spec.copy(bodyMode = it) },
            label = { it.label.str() },
        )
        if (spec.bodyMode != HttpBodyMode.NONE && (spec.method == "GET" || spec.method == "HEAD")) {
            Text(
                Res.string.request_get_and_head.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (spec.bodyMode) {
            HttpBodyMode.NONE -> Unit
            HttpBodyMode.RAW -> {
                ChoiceChips(
                    options = RawBodyType.entries,
                    selected = spec.rawType,
                    onSelect = { spec = spec.copy(rawType = it) },
                    label = { it.label.str() },
                )
                ToolInputField(
                    value = spec.rawBody,
                    onValueChange = { spec = spec.copy(rawBody = it) },
                    label = spec.rawType.label.str(),
                    singleLine = false,
                    minLines = 4,
                    monospace = true,
                )
                if (spec.rawType == RawBodyType.JSON) {
                    TextButton(
                        onClick = {
                            JsonTools.parse(spec.rawBody).getOrNull()?.let { spec = spec.copy(rawBody = JsonTools.format(it, 2)) }
                        },
                        enabled = spec.rawBody.isNotBlank(),
                    ) { Text(Res.string.format_json.str()) }
                }
            }
            HttpBodyMode.FORM -> FieldsEditor(
                fields = spec.form,
                keyLabel = Res.string.field_.str(),
                valueLabel = Res.string.value_.str(),
            ) { spec = spec.copy(form = it) }
        }
    }
    ToolSection(Res.string.authorization.str()) {
        ChoiceChips(
            options = HttpAuthKind.entries,
            selected = spec.auth.kind,
            onSelect = { spec = spec.copy(auth = spec.auth.copy(kind = it)) },
            label = { it.label.str() },
        )
        when (spec.auth.kind) {
            HttpAuthKind.NONE -> Unit
            HttpAuthKind.BEARER -> ToolInputField(
                value = spec.auth.token,
                onValueChange = { spec = spec.copy(auth = spec.auth.copy(token = it)) },
                label = Res.string.token.str(),
                monospace = true,
            )
            HttpAuthKind.BASIC -> {
                ToolInputField(
                    value = spec.auth.user,
                    onValueChange = { spec = spec.copy(auth = spec.auth.copy(user = it)) },
                    label = Res.string.user.str(),
                )
                ToolInputField(
                    value = spec.auth.password,
                    onValueChange = { spec = spec.copy(auth = spec.auth.copy(password = it)) },
                    label = Res.string.password.str(),
                )
                KeyValueRow("Authorization", HttpRequests.basicCredentials(spec.auth.user, spec.auth.password))
            }
            HttpAuthKind.HEADER -> {
                ToolInputField(
                    value = spec.auth.headerName,
                    onValueChange = { spec = spec.copy(auth = spec.auth.copy(headerName = it)) },
                    label = Res.string.header_name.str(),
                    monospace = true,
                )
                ToolInputField(
                    value = spec.auth.token,
                    onValueChange = { spec = spec.copy(auth = spec.auth.copy(token = it)) },
                    label = Res.string.value_.str(),
                    monospace = true,
                )
            }
        }
    }
    ToolSection(Res.string.options.str()) {
        SwitchRow(
            Res.string.follow_redirects.str(),
            spec.followRedirects,
            { spec = spec.copy(followRedirects = it) },
            description = if (currentPlatform == PlatformKind.WEB) webRedirectNote.str() else null,
        )
        NumberField(
            value = timeoutText,
            onValueChange = {
                timeoutText = it
                val seconds = it.toDoubleLenient() ?: (HttpRequests.DEFAULT_TIMEOUT_MS / 1000.0)
                spec = spec.copy(timeoutMs = (seconds * 1000).toLong().coerceIn(1_000L, 300_000L))
            },
            label = Res.string.timeout.str(),
            suffix = Res.string.unit_s.str(),
        )
    }
    ToolSection("curl") {
        ToolInputField(
            value = curlText,
            onValueChange = {
                curlText = it
                curlFailed = false
            },
            label = Res.string.paste_a_curl_command.str(),
            singleLine = false,
            minLines = 3,
            monospace = true,
        )
        ActionButton(
            text = Res.string.import__2.str(),
            onClick = {
                val parsed = Curl.parse(curlText)
                curlFailed = parsed == null
                if (parsed != null) load(parsed)
            },
            enabled = curlText.isNotBlank(),
        )
        if (curlFailed) {
            ErrorText(Res.string.no_url_found_in_the_command.str())
        }
    }
    ToolSection(Res.string.saved_requests.str()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolInputField(
                value = saveName,
                onValueChange = { saveName = it },
                label = Res.string.item_name.str(),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val entry = SavedHttpRequest(saveName.trim(), HttpRequests.synced(spec))
                    saved = saved.filterNot { it.name == entry.name } + entry
                    HttpRequestStore.saveAll(saved)
                    saveName = ""
                },
                enabled = saveName.isNotBlank(),
            ) { Text(Res.string.save.str()) }
        }
        saved.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${item.spec.method} ${item.spec.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { load(item.spec) }) { Text(Res.string.open.str()) }
                IconButton(
                    onClick = {
                        saved = saved - item
                        HttpRequestStore.saveAll(saved)
                    },
                ) { Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str()) }
            }
        }
    }
    if (history.isNotEmpty()) {
        ToolSection(Res.string.history.str()) {
            history.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${entry.spec.method} ${entry.spec.url}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${entry.status} · ${entry.timeMs} ${Res.string.unit_ms.str()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { load(entry.spec) }) { Text(Res.string.open.str()) }
                }
            }
            TextButton(
                onClick = {
                    history = emptyList()
                    HttpRequestStore.saveHistory(history)
                },
            ) { Text(Res.string.clear.str()) }
        }
    }
}

@Composable
private fun FieldsEditor(
    fields: List<HttpField>,
    keyLabel: String,
    valueLabel: String,
    onChange: (List<HttpField>) -> Unit,
) {
    fields.forEachIndexed { index, field ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = field.enabled, onCheckedChange = { onChange(fields.replace(index, field.copy(enabled = it))) })
            ToolInputField(
                value = field.key,
                onValueChange = { onChange(fields.replace(index, field.copy(key = it))) },
                label = keyLabel,
                modifier = Modifier.weight(1f),
                monospace = true,
            )
            ToolInputField(
                value = field.value,
                onValueChange = { onChange(fields.replace(index, field.copy(value = it))) },
                label = valueLabel,
                modifier = Modifier.weight(1.4f),
                monospace = true,
            )
            IconButton(onClick = { onChange(fields.filterIndexed { i, _ -> i != index }) }) {
                Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str())
            }
        }
    }
    TextButton(onClick = { onChange(fields + HttpField("")) }) { Text(Res.string.add_row.str()) }
}

@Composable
private fun ResponseSection(
    exchange: HttpExchange,
    tab: ResponseTab,
    onTab: (ResponseTab) -> Unit,
    formatted: Boolean,
    onFormat: () -> Unit,
) {
    val body = remember(exchange, formatted) {
        if (formatted) {
            JsonTools.parse(exchange.body).getOrNull()?.let { JsonTools.format(it, 2) } ?: exchange.body
        } else {
            exchange.body
        }
    }
    ResultCard(title = "${exchange.status} ${exchange.statusText} · ${exchange.timeMs} ${Res.string.unit_ms.str()} · ${formatBytes(exchange.sizeBytes)}") {
        exchange.contentType?.let { KeyValueRow("Content-Type", it, copyable = false) }
        SegmentedChoice(options = ResponseTab.entries, selected = tab, onSelect = onTab, label = { it.label.str() })
        when (tab) {
            ResponseTab.BODY -> {
                if (exchange.body.isEmpty()) {
                    Text(Res.string.the_response_has_no_body.str(), style = MaterialTheme.typography.bodyMedium)
                    return@ResultCard
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Res.string.response_body.str(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!formatted && HttpRequests.looksLikeJson(exchange.body)) {
                        TextButton(onClick = onFormat) { Text(Res.string.format_json.str()) }
                    }
                    CopyIconButton(body)
                }
                MonoText(if (body.length > MAX_VISIBLE_CHARS) body.take(MAX_VISIBLE_CHARS) else body)
                if (body.length > MAX_VISIBLE_CHARS) {
                    Text(
                        stringResource(Res.string.output_shown_first, MAX_VISIBLE_CHARS.fmtGrouped(), body.length.fmtGrouped()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (exchange.truncated) {
                    Text(
                        stringResource(Res.string.http_body_cut, formatBytes(HttpRequests.MAX_BODY_BYTES)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ResponseTab.HEADERS -> {
                if (currentPlatform == PlatformKind.WEB) {
                    Text(
                        webHeadersNote.str(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                exchange.headers.forEach { (name, value) -> KeyValueRow(name, value) }
            }

            ResponseTab.COOKIES -> {
                if (exchange.cookies.isEmpty()) {
                    Text(Res.string.the_response_set_no_cookies.str(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    exchange.cookies.forEach { cookie ->
                        KeyValueRow(cookie.name, cookie.value)
                        if (cookie.attributes.isNotEmpty()) {
                            Text(
                                cookie.attributes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun List<HttpField>.replace(index: Int, field: HttpField): List<HttpField> =
    mapIndexed { i, item -> if (i == index) field else item }
