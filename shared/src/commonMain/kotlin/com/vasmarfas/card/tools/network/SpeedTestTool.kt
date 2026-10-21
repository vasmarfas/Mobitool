package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val speedTestTool = Tool(
    id = "speed-test",
    category = ToolCategory.NETWORK,
    title = Res.string.speed_test,
    description = Res.string.speed_test_description,
    icon = Icons.Filled.Speed,
    keywords = listOf("bandwidth", "download", "upload", "latency", "mbps", "openspeedtest", "yandex", "скорость", "интернет", "пинг", "яндекс", "интернетометр"),
) { SpeedTestScreen() }

private enum class SpeedPhase { IDLE, LATENCY, DOWNLOAD, UPLOAD, DONE }

private data class SpeedState(
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val progress: Float = 0f,
    val transferred: Long = 0,
    val windowStart: Long = 0,
    val source: String = "",
    val endpoint: String = "",
    val latencyMs: Double? = null,
    val jitterMs: Double? = null,
    val loadedLatencyMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val error: String? = null,
    val browserFallback: Boolean = false,
    val running: Boolean = false,
)

private const val STREAMS = 4
private const val WARMUP_MS = 500L
private const val WINDOW_MS = 8_000L
private const val READ_BUFFER = 64 * 1024
private const val UPLOAD_CHUNK = 2_000_000
private const val MAX_UPLOAD_CHUNK = 8_000_000
private const val DOWNLOAD_REQUEST_BYTES = 50_000_000L
private const val TRANSFER_TIMEOUT_MS = 60_000L
private const val LOADED_PING_GAP_MS = 300L

private data class Transfer(val bytes: Long, val startedAt: Long, val endedAt: Long) {
    val mbps: Double get() = speedMbps(bytes, endedAt - startedAt)
}

private fun List<Transfer>.merge(): Transfer = filter { it.bytes > 0 }
    .fold(Transfer(0, Long.MAX_VALUE, 0)) { acc, part ->
        Transfer(acc.bytes + part.bytes, minOf(acc.startedAt, part.startedAt), maxOf(acc.endedAt, part.endedAt))
    }

private data class SpeedPlan(
    val latencyUrl: String,
    val headLatency: Boolean,
    val downloadUrls: List<String>,
    val uploadUrl: String?,
    val uploadChunk: Int,
)

private suspend fun speedPlan(source: SpeedSource): SpeedPlan? = when (source.kind) {
    SpeedSourceKind.YANDEX -> {
        val probes = YandexSpeedTest.parse(fetchText(YandexSpeedTest.PROBES_URL))
        val upload = YandexSpeedTest.uploadProbe(probes)
        YandexSpeedTest.latencyUrl(probes)?.let { latency ->
            SpeedPlan(
                latencyUrl = latency,
                headLatency = false,
                downloadUrls = YandexSpeedTest.downloadUrls(probes),
                uploadUrl = upload?.postUrl,
                uploadChunk = upload?.size?.takeIf { it > 0 }?.coerceIn(UPLOAD_CHUNK, MAX_UPLOAD_CHUNK) ?: UPLOAD_CHUNK,
            )
        }
    }

    else -> SpeedEndpoints.latency(source)?.let { latency ->
        SpeedPlan(
            latencyUrl = latency,
            headLatency = SpeedEndpoints.headLatency(source),
            downloadUrls = (0 until STREAMS).mapNotNull { SpeedEndpoints.download(source, DOWNLOAD_REQUEST_BYTES, currentEpochMillis() + it) },
            uploadUrl = SpeedEndpoints.upload(source),
            uploadChunk = UPLOAD_CHUNK,
        )
    }
}

private suspend fun fetchText(url: String): String {
    val response = Net.client.get(url)
    if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}")
    return response.bodyAsText()
}

private suspend fun measureLatency(url: String, head: Boolean, samples: Int = 6): LatencyStats {
    val times = mutableListOf<Double>()
    repeat(samples) {
        val started = currentEpochMillis()
        if (head) Net.client.head(url) { timeout { requestTimeoutMillis = 10_000 } }
        else Net.client.get(url) { timeout { requestTimeoutMillis = 10_000 } }
        times += (currentEpochMillis() - started).toDouble()
    }
    return latencyStats(times.drop(1))
}

private suspend fun latencyUnderLoad(url: String, head: Boolean, samples: MutableList<Double>) {
    while (true) {
        val started = currentEpochMillis()
        runCatching {
            if (head) Net.client.head(url) { timeout { requestTimeoutMillis = 5_000 } } else Net.client.get(url) { timeout { requestTimeoutMillis = 5_000 } }
        }.onSuccess { samples += (currentEpochMillis() - started).toDouble() }
        delay(LOADED_PING_GAP_MS.milliseconds)
    }
}

private val NotAStream = Res.string.speed_address_returns_a_web

private suspend fun measureDownload(urls: List<String>, counter: MutableStateFlow<Long>, lang: Lang, window: Long = WINDOW_MS): Transfer = coroutineScope {
    val warmupEnd = currentEpochMillis() + WARMUP_MS
    val deadline = warmupEnd + window
    List(STREAMS) { index ->
        async { downloadStream(urls[index % urls.size], warmupEnd, deadline, counter, lang) }
    }.awaitAll().merge()
}

internal suspend fun quickDownloadMbps(lang: Lang): Double =
    measureDownload(listOf(SpeedEndpoints.download(cloudflareSpeedSource, DOWNLOAD_REQUEST_BYTES, 0)!!), MutableStateFlow(0L), lang, 3_000).mbps

private suspend fun downloadStream(url: String, warmupEnd: Long, deadline: Long, counter: MutableStateFlow<Long>, lang: Lang): Transfer {
    val buffer = ByteArray(READ_BUFFER)
    var bytes = 0L
    var lastRead = warmupEnd
    while (currentEpochMillis() < deadline) {
        var body = 0L
        Net.client.prepareGet(url) { timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MS } }.execute { response ->
            val code = response.status.value
            if (code in 300..399) throw IllegalStateException("HTTP $code · " + getString(NotAStream))
            if (!response.status.isSuccess()) throw IllegalStateException("HTTP $code ${response.status.description}")
            if (response.contentType()?.withoutParameters() == ContentType.Text.Html) throw IllegalStateException(getString(NotAStream))
            val channel = response.bodyAsChannel()
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                body += read
                val now = currentEpochMillis()
                if (now >= warmupEnd) {
                    bytes += read
                    lastRead = now
                    counter.update { it + read }
                }
                if (now >= deadline) break
            }
        }
        if (body == 0L) break
    }
    return Transfer(bytes, warmupEnd, lastRead)
}

private suspend fun measureUpload(url: String, chunk: Int, counter: MutableStateFlow<Long>): Transfer = coroutineScope {
    val payload = ByteArray(chunk) { (it % 251).toByte() }
    val warmupEnd = currentEpochMillis() + WARMUP_MS
    val deadline = warmupEnd + WINDOW_MS
    List(STREAMS) { async { uploadStream(url, payload, warmupEnd, deadline, counter) } }.awaitAll().merge()
}

private suspend fun uploadStream(url: String, payload: ByteArray, warmupEnd: Long, deadline: Long, counter: MutableStateFlow<Long>): Transfer {
    var bytes = 0L
    var from = 0L
    var to = 0L
    do {
        val sentAt = currentEpochMillis()
        val status = Net.client.post(url) {
            timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MS }
            setBody(payload)
        }.status
        if (!status.isSuccess()) throw IllegalStateException("HTTP ${status.value} ${status.description}")
        if (sentAt >= warmupEnd) {
            if (bytes == 0L) from = sentAt
            bytes += payload.size
            to = currentEpochMillis()
            counter.update { it + payload.size }
        }
    } while (currentEpochMillis() < deadline || bytes == 0L)
    return Transfer(bytes, from, to)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedTestScreen() {
    val noEndpointsForThisSourceText = Res.string.no_endpoints_for_this_source.str()
    val corsNoteText = Res.string.net_in_the_browser_requests.str()
    var customSources by remember { mutableStateOf(SpeedSourceStore.load()) }
    var showCustom by rememberSaveable { mutableStateOf(customSources.isNotEmpty()) }
    var sourceName by rememberSaveable { mutableStateOf(cloudflareSpeedSource.name) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf(SpeedState()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    // Yandex answers without CORS headers, a browser page cannot read its probes at all
    val sources = builtInSpeedSources.filter { currentPlatform != PlatformKind.WEB || it.kind != SpeedSourceKind.YANDEX } + customSources
    val selected = sources.firstOrNull { it.name == sourceName } ?: cloudflareSpeedSource
    val source = if (selected.baseUrl.isBlank()) selected.copy(baseUrl = baseUrl.trim()) else selected
    val titleText = source.title()
    val corsRisk = currentPlatform == PlatformKind.WEB && source != cloudflareSpeedSource

    fun start() {
        job?.cancel()
        val counter = MutableStateFlow(0L)
        state = SpeedState(running = true, phase = SpeedPhase.LATENCY, source = titleText)
        job = scope.launch {
            val watcher = launch {
                counter.collect { bytes ->
                    val started = state.windowStart
                    val elapsed = (currentEpochMillis() - started).toFloat() / (WARMUP_MS + WINDOW_MS)
                    state = state.copy(transferred = bytes, progress = if (started == 0L) 0f else elapsed.coerceIn(0f, 1f))
                }
            }
            runCatching {
                val plan = speedPlan(source) ?: error(noEndpointsForThisSourceText)
                state = state.copy(endpoint = plan.latencyUrl)
                val latency = measureLatency(plan.latencyUrl, plan.headLatency)
                state = state.copy(latencyMs = latency.medianMs, jitterMs = latency.jitterMs)
                val loaded = mutableListOf<Double>()
                val sampler = launch { delay(WARMUP_MS.milliseconds); latencyUnderLoad(plan.latencyUrl, plan.headLatency, loaded) }
                if (plan.downloadUrls.isNotEmpty()) {
                    counter.value = 0
                    state = state.copy(phase = SpeedPhase.DOWNLOAD, progress = 0f, transferred = 0, windowStart = currentEpochMillis(), endpoint = plan.downloadUrls.first())
                    state = state.copy(downloadMbps = measureDownload(plan.downloadUrls, counter, lang).mbps)
                }
                plan.uploadUrl?.let { uploadUrl ->
                    counter.value = 0
                    state = state.copy(phase = SpeedPhase.UPLOAD, progress = 0f, transferred = 0, windowStart = currentEpochMillis())
                    state = state.copy(uploadMbps = measureUpload(uploadUrl, plan.uploadChunk, counter).mbps)
                }
                sampler.cancel()
                if (loaded.size >= 3) state = state.copy(loadedLatencyMs = latencyStats(loaded).medianMs)
                state = state.copy(running = false, phase = SpeedPhase.DONE, progress = 1f)
            }.onFailure { failure ->
                if (!isActive) return@onFailure
                val message = failure.message ?: failure.toString()
                state = state.copy(
                    running = false,
                    phase = SpeedPhase.IDLE,
                    error = if (corsRisk) "$message\n${corsNoteText}" else message,
                    browserFallback = source.kind == SpeedSourceKind.YANDEX,
                )
            }
            watcher.cancel()
        }
    }

    DropdownChoice(
        options = sources,
        selected = selected,
        onSelect = { sourceName = it.name },
        label = Res.string.source.str(),
        text = { it.title() },
    )
    if (selected.baseUrl.isBlank()) {
        ToolInputField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = if (selected.kind == SpeedSourceKind.OPENSPEEDTEST) Res.string.server_url.str() else Res.string.file_url.str(),
            placeholder = if (selected.kind == SpeedSourceKind.OPENSPEEDTEST) "http://192.168.1.10:3000" else "https://example.com/100mb.bin",
            keyboardType = KeyboardType.Uri,
            monospace = true,
        )
    }
    Text(source.kind.note.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (source.kind == SpeedSourceKind.BROWSER) {
        ActionButton(
            text = Res.string.open_the_measurement_page.str(),
            onClick = { openUrl(source.baseUrl) },
            enabled = source.baseUrl.isNotBlank(),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = if (state.running) Res.string.net_running.str() else Res.string.start.str(),
                onClick = ::start,
                enabled = !state.running && source.baseUrl.isNotBlank(),
            )
            if (state.running) {
                TextButton(onClick = { job?.cancel(); state = state.copy(running = false, phase = SpeedPhase.IDLE) }) { Text(Res.string.stop_short.str()) }
            }
        }
    }
    if (state.running) {
        val transferring = state.phase == SpeedPhase.DOWNLOAD || state.phase == SpeedPhase.UPLOAD
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (state.phase) {
                    SpeedPhase.LATENCY -> Res.string.measuring_latency.str()
                    SpeedPhase.DOWNLOAD -> Res.string.downloading.str() + " " + formatBytes(state.transferred, binary = false)
                    SpeedPhase.UPLOAD -> Res.string.uploading.str() + " " + formatBytes(state.transferred, binary = false)
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (transferring) LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    state.error?.let { ErrorText(it) }
    if (state.browserFallback && source.baseUrl.isNotBlank()) {
        ActionButton(
            text = Res.string.open_the_measurement_page.str(),
            onClick = { openUrl(source.baseUrl) },
            icon = Icons.AutoMirrored.Filled.OpenInNew,
        )
    }
    if (state.latencyMs != null || state.downloadMbps != null) {
        state.downloadMbps?.let { AnswerCard("${it.fmt(1)} ${Res.string.unit_mbit_s.str()}", "${Res.string.download.str()} · ${state.source}", copyValue = it.fmt(1)) }
        ResultCard(title = state.source) {
            state.latencyMs?.let { KeyValueRow(Res.string.latency.str(), "${it.fmt(0)} ${Res.string.unit_ms.str()} · ${Res.string.jitter.str()} ${state.jitterMs?.fmt(1) ?: "—"} ${Res.string.unit_ms.str()}", copyable = false) }
            val idle = state.latencyMs
            val loaded = state.loadedLatencyMs
            if (idle != null && loaded != null) {
                val added = (loaded - idle).coerceAtLeast(0.0)
                KeyValueRow(
                    Res.string.speed_loaded_latency.str(),
                    stringResource(Res.string.speed_loaded_latency_value, loaded.fmt(0), added.fmt(0), bufferbloatGrade(added)),
                    copyable = false,
                )
            }
            state.downloadMbps?.let { KeyValueRow(Res.string.download.str(), "${it.fmt(1)} ${Res.string.unit_mbit_s.str()} (${(it / 8).fmt(2)} ${Res.string.unit_mb_per_second.str()})", copyable = false) }
            state.uploadMbps?.let { KeyValueRow(Res.string.upload.str(), "${it.fmt(1)} ${Res.string.unit_mbit_s.str()} (${(it / 8).fmt(2)} ${Res.string.unit_mb_per_second.str()})", copyable = false) }
            KeyValueRow(Res.string.endpoint.str(), state.endpoint, copyable = false)
        }
    }
    if (state.loadedLatencyMs != null) Hint(Res.string.speed_bufferbloat_hint.str())
    Text(
        Res.string.speed_results_depend.str(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow(Res.string.custom_sources.str(), showCustom, { showCustom = it })
    if (showCustom) {
        SpeedSourceSection(
            sources = customSources,
            onChange = {
                customSources = it
                SpeedSourceStore.save(it)
            },
        )
    }
}

@Composable
private fun SpeedSourceSection(sources: List<SpeedSource>, onChange: (List<SpeedSource>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SpeedSourceKind.CLOUDFLARE) }
    var editing by rememberSaveable { mutableStateOf(-1) }

    fun reset() {
        name = ""
        url = ""
        kind = SpeedSourceKind.CLOUDFLARE
        editing = -1
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolInputField(value = name, onValueChange = { name = it }, label = Res.string.item_name.str())
        ChoiceChips(
            options = SpeedSourceKind.entries.filter { it != SpeedSourceKind.YANDEX },
            selected = kind,
            onSelect = { kind = it },
            label = { it.label.str() },
        )
        ToolInputField(
            value = url,
            onValueChange = { url = it },
            label = Res.string.speed_base_url.str(),
            placeholder = "https://speed.example.com",
            keyboardType = KeyboardType.Uri,
            monospace = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = if (editing >= 0) Res.string.save.str() else Res.string.add_source.str(),
                onClick = {
                    val entry = SpeedSource(name.trim().ifBlank { hostFrom(url) }, kind, url.trim())
                    onChange(if (editing in sources.indices) sources.toMutableList().also { it[editing] = entry } else sources + entry)
                    reset()
                },
                enabled = url.isNotBlank(),
                icon = Icons.Filled.Add,
            )
            if (editing >= 0) {
                TextButton(onClick = ::reset) { Text(Res.string.cancel.str()) }
            }
        }
        sources.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${item.baseUrl} · ${item.kind.label.str()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        name = item.name
                        url = item.baseUrl
                        kind = item.kind
                        editing = index
                    },
                ) { Icon(Icons.Filled.Edit, contentDescription = Res.string.edit.str()) }
                IconButton(
                    onClick = {
                        onChange(sources.filterIndexed { i, _ -> i != index })
                        reset()
                    },
                ) { Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str()) }
            }
        }
    }
}
