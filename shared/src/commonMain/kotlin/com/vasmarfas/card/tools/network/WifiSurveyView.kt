package com.vasmarfas.card.tools.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.EncodedFormat
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.decodeRawImage
import com.vasmarfas.card.core.encode
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.httpPing
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.scanWifi
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.readBytes
import kotlin.math.floor
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private const val PLAN_SIDE = 1600
private const val GRID_COLUMNS = 64
private const val MAX_ACCESS_POINTS = 12
private const val PINGS = 5
private const val NO_BSSID = "02:00:00:00:00:00"

private val ramp = listOf(Color(0xFFD32F2F), Color(0xFFF57C00), Color(0xFFFBC02D), Color(0xFF7CB342), Color(0xFF2E7D32))

private fun rampColor(fraction: Double): Color {
    val x = fraction.coerceIn(0.0, 1.0) * (ramp.size - 1)
    val i = floor(x).toInt().coerceAtMost(ramp.size - 2)
    return lerp(ramp[i], ramp[i + 1], (x - i).toFloat())
}

private fun fraction(metric: SurveyMetric, value: Double, maxSpeed: Double): Double = when (metric) {
    SurveyMetric.SIGNAL, SurveyMetric.NETWORK, SurveyMetric.ACCESS_POINT -> (value + 90) / 42
    SurveyMetric.LATENCY -> 1 - (ln(value.coerceAtLeast(5.0)) - ln(5.0)) / (ln(100.0) - ln(5.0))
    SurveyMetric.SPEED -> if (maxSpeed > 0) value / maxSpeed else 0.0
}

private suspend fun latencyTo(gateway: String?): Pair<Double?, Int> {
    val router = gateway?.takeIf { it.isNotEmpty() && NetCapabilities.icmpPing }
    val url = "https://1.1.1.1/cdn-cgi/trace"
    if (router == null) runCatching { httpPing(url, 5000) }
    val times = List(PINGS) { attempt ->
        if (router != null) icmpPing(router, attempt, 1000).timeMs else runCatching { httpPing(url, 3000).toDouble() }.getOrNull()
    }.filterNotNull()
    return times.takeIf { it.isNotEmpty() }?.average() to (PINGS - times.size) * 100 / PINGS
}

private suspend fun measureSpot(x: Float, y: Float, scans: Boolean, withSpeed: Boolean, lang: Lang): SurveyPoint = coroutineScope {
    val link = wifiDetails()
    val scan = async { if (scans) scanWifi().points else emptyList() }
    val latency = async { latencyTo(link["Gateway"]) }
    val heard = scan.await()
    val (ms, loss) = latency.await()
    val mbps = if (withSpeed) runCatching { quickDownloadMbps(lang) }.getOrNull() else null
    val connected = heard.firstOrNull { it.connected }
    SurveyPoint(
        x = x,
        y = y,
        rssi = link["RSSI"]?.toIntOrNull()?.takeIf { it > -127 } ?: connected?.rssi,
        bssid = link["BSSID"]?.uppercase()?.takeIf { it != NO_BSSID } ?: connected?.bssid,
        signals = heard.associate { it.bssid to it.rssi },
        names = heard.associate { it.bssid to it.ssid },
        latency = ms,
        loss = loss,
        mbps = mbps,
    )
}

@Composable
fun WifiSurveyView(scans: Boolean) {
    var surveys by remember { mutableStateOf(SurveyStore.load()) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    fun store(list: List<Survey>) {
        surveys = list
        SurveyStore.save(list)
    }
    val open = surveys.firstOrNull { it.id == openId }
    if (open == null) {
        SurveyList(surveys, scans, { openId = it }) { survey, jpeg ->
            jpeg?.let { SurveyStore.savePlan(survey.id, it) }
            store(surveys + survey)
            openId = survey.id
        }
    } else {
        SurveyEditor(
            survey = open,
            scans = scans,
            onChange = { changed -> store(surveys.map { if (it.id == changed.id) changed else it }) },
            onClose = { openId = null },
            onDelete = {
                SurveyStore.removePlan(open.id)
                store(surveys - open)
                openId = null
            },
        )
    }
}

@Composable
private fun SurveyList(surveys: List<Survey>, scans: Boolean, onOpen: (Long) -> Unit, onCreate: (Survey, ByteArray?) -> Unit) {
    val scope = rememberCoroutineScope()
    val fallback = stringResource(Res.string.wifi_survey_default_name, (surveys.size + 1).toString())
    var name by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    fun create(aspect: Float, jpeg: ByteArray?) = onCreate(Survey(currentEpochMillis(), name.trim().ifEmpty { fallback }, aspect, jpeg != null), jpeg)

    Hint(Res.string.wifi_survey_intro.str())
    if (!scans) Hint(Res.string.wifi_no_scan.str())
    surveys.forEach { survey ->
        ResultCard(modifier = Modifier.clickable { onOpen(survey.id) }) {
            Text(survey.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.wifi_survey_points, survey.points.size.toString()), style = MaterialTheme.typography.bodySmall)
        }
    }
    ResultCard(Res.string.wifi_survey_new.str()) {
        ToolInputField(name, { name = it }, Res.string.name.str(), placeholder = fallback)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickButton(Res.string.wifi_survey_plan.str(), setOf("jpg", "jpeg", "png", "webp", "heic", "heif"), PickKind.IMAGE, icon = Icons.Filled.Image) { files ->
                scope.launch {
                    val image = runCatching { decodeRawImage(files.first().readBytes(), PLAN_SIDE)?.bitmap?.limitedTo(PLAN_SIDE) }.getOrNull()
                    failed = image == null
                    if (image != null) create(image.width.toFloat() / image.height, withContext(Dispatchers.Default) { image.encode(EncodedFormat.JPEG, 80) })
                }
            }
            OutlinedButton(onClick = { create(4f / 3, null) }) {
                Icon(Icons.Filled.GridOn, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(Res.string.wifi_survey_grid.str())
            }
        }
        if (failed) ErrorText(Res.string.image_not_readable.str())
    }
}

private class SurveyLook(
    val plan: ImageBitmap?,
    val grid: SurveyGrid?,
    val color: (Double) -> Color,
    val value: (SurveyPoint) -> Double?,
    val label: (SurveyPoint) -> String,
    val background: Color,
    val lines: Color,
    val text: TextStyle,
)

private fun DrawScope.drawSurvey(survey: Survey, look: SurveyLook, measurer: TextMeasurer, selected: Int?, measuring: Offset?) {
    drawRect(look.background)
    val plan = look.plan
    if (plan != null) {
        drawImage(plan, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    } else {
        val step = size.width / 12
        var x = step
        while (x < size.width) { drawLine(look.lines, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = step
        while (y < size.height) { drawLine(look.lines, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
    look.grid?.let { grid ->
        val cell = Size(size.width / grid.columns + 0.5f, size.height / grid.rows + 0.5f)
        for (row in 0 until grid.rows) for (column in 0 until grid.columns) {
            val value = grid[column, row]
            if (value.isNaN()) continue
            drawRect(look.color(value).copy(alpha = 0.55f * grid.fade[row * grid.columns + column]), Offset(column * size.width / grid.columns, row * size.height / grid.rows), cell)
        }
    }
    val radius = 7.dp.toPx()
    survey.points.forEachIndexed { index, point ->
        val center = Offset(point.x * size.width, point.y * size.height)
        val fill = look.value(point)?.let(look.color) ?: Color.Gray
        drawCircle(Color.White, radius + 2.dp.toPx(), center)
        drawCircle(fill, radius, center)
        if (index == selected) drawCircle(Color.Black, radius + 5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        val label = look.label(point)
        if (label.isNotEmpty()) {
            val text = measurer.measure(label, look.text)
            val topLeft = Offset(center.x - text.size.width / 2f, center.y - radius - 4.dp.toPx() - text.size.height)
            drawRect(Color.White.copy(alpha = 0.8f), topLeft, Size(text.size.width.toFloat(), text.size.height.toFloat()))
            drawText(text, topLeft = topLeft)
        }
    }
    measuring?.let {
        val center = Offset(it.x * size.width, it.y * size.height)
        drawCircle(Color.Black, radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black, radius * 2, center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun SurveyEditor(survey: Survey, scans: Boolean, onChange: (Survey) -> Unit, onClose: () -> Unit, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = appLang
    val plan by produceState<ImageBitmap?>(null, survey.id) {
        value = if (survey.plan) withContext(Dispatchers.Default) { SurveyStore.plan(survey.id)?.let { decodeRawImage(it)?.bitmap } } else null
    }
    val metrics = if (scans) SurveyMetric.entries else listOf(SurveyMetric.LATENCY, SurveyMetric.SPEED)
    var metric by rememberSaveable(survey.id) { mutableStateOf(metrics.first()) }
    val networks = survey.points.flatMap { it.names.values }.filter { it.isNotEmpty() }.distinct().sorted()
    var picked by rememberSaveable(survey.id) { mutableStateOf<String?>(null) }
    val network = picked?.takeIf { it in networks } ?: survey.points.lastOrNull { it.bssid != null }?.let { it.names[it.bssid] } ?: networks.firstOrNull()
    var withSpeed by rememberSaveable { mutableStateOf(!scans) }
    var measuring by remember { mutableStateOf<Offset?>(null) }
    var selected by remember(survey.id) { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(survey)
    val speed by rememberUpdatedState(withSpeed)

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Res.string.back.str()) }
        Text(survey.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
    ChoiceChips(metrics, metric, { metric = it }, { it.title.str() })
    if (metric == SurveyMetric.NETWORK && network != null) {
        DropdownChoice(networks, network, { picked = it }, Res.string.wifi_metric_network.str(), { it })
    }
    SwitchRow(Res.string.wifi_survey_speed.str(), withSpeed, { withSpeed = it }, description = Res.string.wifi_survey_speed_hint.str())

    val accessPoints = remember(survey.points) { SurveyMap.accessPoints(survey).take(MAX_ACCESS_POINTS) }
    val grid by produceState<SurveyGrid?>(null, survey.points, metric, network, accessPoints) {
        value = withContext(Dispatchers.Default) {
            if (metric == SurveyMetric.ACCESS_POINT) SurveyMap.accessPointGrid(survey, accessPoints, GRID_COLUMNS) else SurveyMap.grid(survey, metric, network, GRID_COLUMNS)
        }
    }
    val maxSpeed = survey.points.mapNotNull { it.mbps }.maxOrNull() ?: 0.0
    val look = SurveyLook(
        plan = plan,
        grid = grid,
        color = { if (metric == SurveyMetric.ACCESS_POINT) wifiPalette[it.toInt() % wifiPalette.size] else rampColor(fraction(metric, it, maxSpeed)) },
        value = { point ->
            if (metric == SurveyMetric.ACCESS_POINT) {
                point.signals.maxByOrNull { it.value }?.key?.let { accessPoints.indexOf(it) }?.takeIf { it >= 0 }?.toDouble()
            } else {
                SurveyMap.value(point, metric, network)
            }
        },
        label = { point ->
            when (metric) {
                SurveyMetric.LATENCY -> point.latency?.fmt(0)
                SurveyMetric.SPEED -> point.mbps?.fmt(0)
                else -> SurveyMap.value(point, metric, network)?.toInt()?.toString()
            }.orEmpty()
        },
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        lines = MaterialTheme.colorScheme.outlineVariant,
        text = TextStyle(fontSize = 11.sp, color = Color.Black),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var shownWidth by remember { mutableStateOf(1) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        shownWidth = constraints.maxWidth
        Canvas(
            Modifier
                .size(maxWidth, maxWidth / survey.aspect)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        if (measuring != null) return@detectTapGestures
                        val points = current.points
                        val hit = points.indexOfFirst { (Offset(it.x * size.width, it.y * size.height) - tap).getDistance() < 24.dp.toPx() }
                        if (hit >= 0) {
                            selected = if (selected == hit) null else hit
                            return@detectTapGestures
                        }
                        val spot = Offset(tap.x / size.width, tap.y / size.height)
                        measuring = spot
                        selected = null
                        scope.launch {
                            val point = measureSpot(spot.x, spot.y, scans, speed, lang)
                            onChange(current.copy(points = current.points + point))
                            measuring = null
                        }
                    }
                },
        ) {
            drawSurvey(survey, look, measurer, selected, measuring)
        }
    }
    Legend(metric, maxSpeed, accessPoints.withIndex().filter { (index, _) -> grid?.values?.any { it == index.toDouble() } == true }, survey)
    when {
        measuring != null -> LoadingRow(Res.string.wifi_survey_measuring.str())
        survey.points.isEmpty() -> Hint(Res.string.wifi_survey_how.str())
    }
    selected?.let { index ->
        survey.points.getOrNull(index)?.let { point ->
            PointCard(point) {
                onChange(survey.copy(points = survey.points.filterIndexed { i, _ -> i != index }))
                selected = null
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange(survey.copy(points = survey.points.dropLast(1))); selected = null }, enabled = survey.points.isNotEmpty() && measuring == null) {
            Text(Res.string.undo.str())
        }
        TextButton(onClick = { confirmDelete = true }) { Text(Res.string.wifi_survey_delete.str()) }
    }
    if (survey.points.isNotEmpty()) {
        SaveButton(Res.string.wifi_survey_save_image.str()) {
            val width = plan?.width ?: PLAN_SIDE
            val height = (width / survey.aspect).toInt()
            val bitmap = ImageBitmap(width, height)
            val scale = Density(density.density * width / shownWidth.coerceAtLeast(1), density.fontScale)
            CanvasDrawScope().draw(scale, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
                drawSurvey(survey, look, measurer, null, null)
            }
            val png = withContext(Dispatchers.Default) { bitmap.encode(EncodedFormat.PNG, 100) }
            saveBytes(png, survey.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png")
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.wifi_survey_delete_confirm, survey.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(Res.string.delete.str()) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(Res.string.cancel.str()) } },
        )
    }
}

@Composable
private fun Legend(metric: SurveyMetric, maxSpeed: Double, accessPoints: List<IndexedValue<String>>, survey: Survey) {
    if (metric == SurveyMetric.ACCESS_POINT) {
        val names = survey.points.fold(emptyMap<String, String>()) { acc, point -> acc + point.names }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            accessPoints.forEach { (index, bssid) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(14.dp).background(wifiPalette[index % wifiPalette.size], CircleShape))
                    Text(listOf(names[bssid].orEmpty(), bssid).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }
    val (low, high) = when (metric) {
        SurveyMetric.LATENCY -> "100 ${Res.string.unit_ms.str()}" to "5 ${Res.string.unit_ms.str()}"
        SurveyMetric.SPEED -> "0" to "${maxSpeed.fmt(0)} ${Res.string.unit_mbit_s.str()}"
        else -> "-90 ${Res.string.unit_dbm.str()}" to "-48 ${Res.string.unit_dbm.str()}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(ramp)))
        Row {
            Text(low, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(high, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PointCard(point: SurveyPoint, onDelete: () -> Unit) {
    ResultCard(Res.string.wifi_survey_point.str()) {
        point.rssi?.let { KeyValueRow(Res.string.wifi_metric_signal.str(), "$it ${Res.string.unit_dbm.str()}", copyable = false) }
        point.bssid?.let { bssid -> KeyValueRow(Res.string.wifi_survey_serving.str(), listOfNotNull(point.names[bssid], bssid).joinToString(" · ")) }
        point.latency?.let { KeyValueRow(Res.string.wifi_metric_latency.str(), "${it.fmt(1)} ${Res.string.unit_ms.str()}", copyable = false) }
        point.loss?.takeIf { it > 0 }?.let { KeyValueRow(Res.string.wifi_survey_loss.str(), stringResource(Res.string.percent_value, it.toString()), copyable = false) }
        point.mbps?.let { KeyValueRow(Res.string.wifi_metric_speed.str(), "${it.fmt(1)} ${Res.string.unit_mbit_s.str()}", copyable = false) }
        if (point.signals.isNotEmpty()) KeyValueRow(Res.string.wifi_survey_heard.str(), point.signals.size.toString(), copyable = false)
        TextButton(onClick = onDelete) { Text(Res.string.delete.str()) }
    }
}
