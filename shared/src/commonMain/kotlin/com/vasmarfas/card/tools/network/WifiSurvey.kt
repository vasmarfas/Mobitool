package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.resources.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.resources.StringResource

@Serializable
data class SurveyPoint(
    val x: Float,
    val y: Float,
    val rssi: Int? = null,
    val bssid: String? = null,
    val signals: Map<String, Int> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val latency: Double? = null,
    val loss: Int? = null,
    val mbps: Double? = null,
)

@Serializable
data class Survey(val id: Long, val name: String, val aspect: Float, val plan: Boolean, val points: List<SurveyPoint> = emptyList())

enum class SurveyMetric(val title: StringResource) {
    SIGNAL(Res.string.wifi_metric_signal),
    NETWORK(Res.string.wifi_metric_network),
    ACCESS_POINT(Res.string.wifi_metric_access_point),
    LATENCY(Res.string.wifi_metric_latency),
    SPEED(Res.string.wifi_metric_speed),
}

class SurveyGrid(val columns: Int, val rows: Int, val values: DoubleArray, val fade: FloatArray) {
    operator fun get(column: Int, row: Int): Double = values[row * columns + column]
}

object SurveyMap {
    // in parts of the plan's longer side
    const val REACH = 0.22

    private const val UNHEARD = -95.0

    fun value(point: SurveyPoint, metric: SurveyMetric, ssid: String?): Double? = when (metric) {
        SurveyMetric.SIGNAL -> point.rssi?.toDouble()
        SurveyMetric.NETWORK -> point.signals.filterKeys { point.names[it] == ssid }.values.maxOrNull()?.toDouble()
        SurveyMetric.LATENCY -> point.latency
        SurveyMetric.SPEED -> point.mbps
        SurveyMetric.ACCESS_POINT -> point.signals.maxByOrNull { it.value }?.value?.toDouble()
    }

    private class Sample(val x: Double, val y: Double, val value: Double)

    private fun nearest(samples: List<Sample>, x: Double, y: Double): Double =
        samples.minOfOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }?.let(::sqrt) ?: Double.MAX_VALUE

    private fun interpolate(samples: List<Sample>, x: Double, y: Double): Double {
        var weight = 0.0
        var sum = 0.0
        var nearest = Double.MAX_VALUE
        for (sample in samples) {
            val d2 = (sample.x - x) * (sample.x - x) + (sample.y - y) * (sample.y - y)
            if (d2 < 1e-9) return sample.value
            nearest = min(nearest, d2)
            weight += 1 / d2
            sum += sample.value / d2
        }
        return if (samples.isEmpty() || nearest > REACH * REACH) Double.NaN else sum / weight
    }

    private fun samples(survey: Survey, value: (SurveyPoint) -> Double?): List<Sample> {
        val side = max(survey.aspect, 1f).toDouble()
        return survey.points.mapNotNull { point -> value(point)?.let { Sample(point.x * survey.aspect / side, point.y / side, it) } }
    }

    private fun build(survey: Survey, columns: Int, cell: (Double, Double) -> Double): SurveyGrid {
        val rows = max(1, (columns / survey.aspect).toInt())
        val side = max(survey.aspect, 1f).toDouble()
        val measured = samples(survey) { 0.0 }
        val fade = FloatArray(columns * rows)
        val values = DoubleArray(columns * rows) { index ->
            val x = (index % columns + 0.5) / columns * survey.aspect / side
            val y = (index / columns + 0.5) / rows / side
            fade[index] = ((REACH - nearest(measured, x, y)) / (REACH * 0.4)).coerceIn(0.0, 1.0).toFloat()
            cell(x, y)
        }
        return SurveyGrid(columns, rows, values, fade)
    }

    fun grid(survey: Survey, metric: SurveyMetric, ssid: String?, columns: Int): SurveyGrid {
        val samples = samples(survey) { value(it, metric, ssid) }
        return build(survey, columns) { x, y -> interpolate(samples, x, y) }
    }

    fun accessPoints(survey: Survey): List<String> =
        survey.points.flatMap { it.signals.keys }.distinct().sortedByDescending { bssid -> survey.points.maxOf { it.signals[bssid] ?: -200 } }

    fun accessPointGrid(survey: Survey, bssids: List<String>, columns: Int): SurveyGrid {
        val reach = samples(survey) { 0.0 }
        val perAp = bssids.map { bssid -> samples(survey) { it.signals[bssid]?.toDouble() ?: UNHEARD } }
        return build(survey, columns) { x, y ->
            if (interpolate(reach, x, y).isNaN()) return@build Double.NaN
            perAp.indices.maxByOrNull { interpolate(perAp[it], x, y) }?.toDouble() ?: Double.NaN
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
object SurveyStore {
    private const val KEY = "wifi.surveys"
    private val serializer = ListSerializer(Survey.serializer())

    fun load(): List<Survey> = runCatching { Net.json.decodeFromString(serializer, Prefs.store.get(KEY) ?: "[]") }.getOrDefault(emptyList())

    fun save(surveys: List<Survey>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, surveys))

    fun plan(id: Long): ByteArray? = Prefs.store.get("$KEY.$id")?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }

    fun savePlan(id: Long, jpeg: ByteArray) = Prefs.store.put("$KEY.$id", Base64.Default.encode(jpeg))

    fun removePlan(id: Long) = Prefs.store.remove("$KEY.$id")
}
