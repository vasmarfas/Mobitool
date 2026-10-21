package com.vasmarfas.card.tools.network

import androidx.compose.runtime.Composable
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import org.jetbrains.compose.resources.StringResource

enum class SpeedSourceKind(val label: StringResource, val note: StringResource) {
    CLOUDFLARE(
        Res.string.cloudflare_style,
        Res.string.speed_nearest_cloudflare_server,
    ),
    OPENSPEEDTEST(
        Res.string.openspeedtest,
        Res.string.speed_self_hosted_server,
    ),
    YANDEX(
        Res.string.yandex,
        Res.string.speed_measured_in_the_app,
    ),
    FILE(
        Res.string.direct_file_url,
        Res.string.speed_download_only_the_file,
    ),
    BROWSER(
        Res.string.open_in_browser,
        Res.string.speed_measurement_runs,
    ),
}

@Serializable
data class SpeedSource(val name: String, val kind: SpeedSourceKind, val baseUrl: String = "") {
    @Composable
    fun title(): String = builtInTitles[name]?.str() ?: name
}

data class LatencyStats(val medianMs: Double, val jitterMs: Double)

private val builtInTitles: Map<String, StringResource> = mapOf(
    "Yandex" to Res.string.yandex_internetometer,
    "OpenSpeedTest" to Res.string.openspeedtest_self_hosted,
    "Custom file" to Res.string.direct_file_url,
)

val cloudflareSpeedSource = SpeedSource("Cloudflare", SpeedSourceKind.CLOUDFLARE, "https://speed.cloudflare.com")

val builtInSpeedSources: List<SpeedSource> = listOf(
    cloudflareSpeedSource,
    SpeedSource("Yandex", SpeedSourceKind.YANDEX, "https://yandex.ru/internet/"),
    SpeedSource("OpenSpeedTest", SpeedSourceKind.OPENSPEEDTEST),
    SpeedSource("Custom file", SpeedSourceKind.FILE),
)

object SpeedSourceStore {
    private const val KEY = "speedtest.sources"
    private val serializer = ListSerializer(SpeedSource.serializer())

    fun load(): List<SpeedSource> {
        val raw = Prefs.store.get(KEY) ?: return emptyList()
        val array = runCatching { Net.json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { Net.json.decodeFromJsonElement(SpeedSource.serializer(), element) }.getOrNull()
        }
    }

    fun save(list: List<SpeedSource>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, list))
}

object SpeedEndpoints {
    fun latency(source: SpeedSource): String? = when (source.kind) {
        SpeedSourceKind.CLOUDFLARE -> "${base(source)}/__down?bytes=0"
        SpeedSourceKind.OPENSPEEDTEST -> "${base(source)}/downloading"
        SpeedSourceKind.FILE -> base(source)
        SpeedSourceKind.YANDEX, SpeedSourceKind.BROWSER -> null
    }

    fun download(source: SpeedSource, bytes: Long, nonce: Long): String? = when (source.kind) {
        SpeedSourceKind.CLOUDFLARE -> "${base(source)}/__down?bytes=$bytes"
        SpeedSourceKind.OPENSPEEDTEST -> "${base(source)}/downloading?r=$nonce"
        SpeedSourceKind.FILE -> base(source)
        SpeedSourceKind.YANDEX, SpeedSourceKind.BROWSER -> null
    }

    fun upload(source: SpeedSource): String? = when (source.kind) {
        SpeedSourceKind.CLOUDFLARE -> "${base(source)}/__up"
        SpeedSourceKind.OPENSPEEDTEST -> "${base(source)}/upload"
        SpeedSourceKind.FILE, SpeedSourceKind.YANDEX, SpeedSourceKind.BROWSER -> null
    }

    fun headLatency(source: SpeedSource): Boolean =
        source.kind == SpeedSourceKind.OPENSPEEDTEST || source.kind == SpeedSourceKind.FILE

    private fun base(source: SpeedSource): String {
        val trimmed = source.baseUrl.trim().trimEnd('/')
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }
}

@Serializable
data class YandexProbe(val url: String = "", val timeout: Int? = null)

@Serializable
data class YandexUploadProbe(
    val url: String = "",
    val postUrl: String = "",
    val statsUrl: String = "",
    val size: Int = 0,
    val timeout: Int? = null,
)

@Serializable
data class YandexProbeGroup<T>(val probes: List<T> = emptyList())

@Serializable
data class YandexProbes(
    val mid: String = "",
    val lid: List<String> = emptyList(),
    val latency: YandexProbeGroup<YandexProbe> = YandexProbeGroup(),
    val download: YandexProbeGroup<YandexProbe> = YandexProbeGroup(),
    val upload: YandexProbeGroup<YandexUploadProbe> = YandexProbeGroup(),
)

object YandexSpeedTest {
    const val PROBES_URL = "https://yandex.ru/internet/api/v0/get-probes?flag_ws-conn-timeout=2000&flag_speedtest-worker=0"

    fun parse(body: String): YandexProbes = Net.json.decodeFromString(YandexProbes.serializer(), body)

    fun latencyUrl(probes: YandexProbes): String? = probes.latency.probes.firstOrNull()?.url?.ifBlank { null }

    fun downloadUrls(probes: YandexProbes): List<String> =
        probes.download.probes.filter { it.timeout == null }.map { it.url }.filter { it.isNotBlank() }

    fun uploadProbes(probes: YandexProbes): List<YandexUploadProbe> =
        probes.upload.probes.filter { it.timeout == null && it.postUrl.isNotBlank() }

    fun uploadProbe(probes: YandexProbes): YandexUploadProbe? =
        uploadProbes(probes).maxByOrNull { it.size } ?: probes.upload.probes.filter { it.postUrl.isNotBlank() }.maxByOrNull { it.size }
}

fun speedMbps(bytes: Long, millis: Long): Double = if (bytes <= 0 || millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0

fun bufferbloatGrade(addedMs: Double): String = when {
    addedMs < 5 -> "A+"
    addedMs < 30 -> "A"
    addedMs < 60 -> "B"
    addedMs < 200 -> "C"
    addedMs < 400 -> "D"
    else -> "F"
}

fun latencyStats(samples: List<Double>): LatencyStats {
    if (samples.isEmpty()) return LatencyStats(0.0, 0.0)
    val sorted = samples.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    val deltas = samples.zipWithNext { a, b -> abs(b - a) }
    return LatencyStats(median, if (deltas.isEmpty()) 0.0 else deltas.average())
}
