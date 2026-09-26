package com.vasmarfas.card.tools.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.AppleDisplay
import com.vasmarfas.card.core.DisplayPanel
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.appleCandidates
import com.vasmarfas.card.core.appleDisplays
import com.vasmarfas.card.core.appleScreen
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.displayPanels
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.screenDpi
import com.vasmarfas.card.core.screenPixels
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LocalChrome
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.expandedHeight
import com.vasmarfas.card.ui.components.trackTouch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.jetbrains.compose.resources.StringResource

val rulerTool = Tool(
    id = "ruler",
    category = ToolCategory.MEASURE,
    title = Res.string.ruler,
    description = Res.string.ruler_description,
    icon = Icons.Filled.Straighten,
    keywords = listOf("measure", "length", "cm", "inch", "calibrate", "измерить", "длина", "сантиметр", "калибровка"),
    expandable = true,
) { RulerScreen() }

private const val PX_PER_MM_KEY = "ruler.pxPerMm"
private const val SCREEN_KEY = "ruler.screen"

private enum class RulerAxis(val title: StringResource) {
    ACROSS(Res.string.across_the_screen),
    ALONG(Res.string.along_the_screen),
}

private enum class CalibrationMode(val title: StringResource) {
    OBJECT(Res.string.by_an_object),
    DIAGONAL(Res.string.by_the_diagonal),
}

private data class Reference(val title: StringResource, val mm: Double, val round: Boolean = false)

private val references = listOf(
    Reference(Res.string.bank_card_long_side, 85.60),
    Reference(Res.string.bank_card_short_side, 53.98),
    Reference(Res.string.s_10_coin, 22.0, round = true),
    Reference(Res.string.s_5_coin, 25.0, round = true),
    Reference(Res.string.s_1_coin, 23.25, round = true),
    Reference(Res.string.a4_sheet_short_side, 210.0),
)

// Android reports a density bucket, not the panel dpi, so an uncalibrated ruler is off by a few percent
// on most phones. The advertised diagonal is enough to recover the real value
fun dpiFromDiagonal(widthPx: Int, heightPx: Int, diagonalInches: Double): Double? {
    if (widthPx <= 0 || heightPx <= 0 || diagonalInches <= 0.0) return null
    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    return sqrt(w * w + h * h) / diagonalInches
}

@Composable
private fun defaultPxPerMm(): Float {
    val density = LocalDensity.current.density
    val dpi = screenDpi()?.takeIf { it > 40f } ?: (density * 160f)
    return dpi / 25.4f
}

private class ScreenModel(val key: String, val label: String, val pxPerMm: Float)

private class ScreenModels(val apple: Boolean, val all: List<ScreenModel>, val matching: List<ScreenModel>, val loading: Boolean = false) {
    val auto: Float? get() = matching.map { it.pxPerMm }.distinct().singleOrNull()
}

@Composable
private fun rememberScreenModels(pixels: Pair<Int, Int>?): ScreenModels {
    val density = LocalDensity.current.density
    val apple = remember { appleScreen() }
    var panels by remember { mutableStateOf<List<DisplayPanel>?>(null) }
    LaunchedEffect(Unit) { panels = displayPanels() }
    return remember(apple, panels, pixels, density) {
        if (apple != null) {
            fun AppleDisplay.model(): ScreenModel {
                val twin = appleDisplays.count { it.name == name } > 1
                val label = (if (twin) "$name, ${diagonalInches.fmt(1)}\"" else name) + " · $ppi ppi"
                return ScreenModel("${identifiers.first()}:$widthPx", label, (pxPerInch(density, apple.nativeScale ?: nativeScale) / 25.4).toFloat())
            }
            ScreenModels(true, appleDisplays.map { it.model() }, appleCandidates(apple, pixels, density).map { it.model() })
        } else {
            val (w, h) = pixels ?: (0 to 0)
            val known = panels.orEmpty()
            val fits = known.filter { minOf(it.widthPx, it.heightPx) == minOf(w, h) && maxOf(it.widthPx, it.heightPx) == maxOf(w, h) }
            fun DisplayPanel.model() = ScreenModel(
                "$name:${widthMm}x$heightMm",
                "$name · ${diagonalInches.fmt(1)}\" · $widthPx × $heightPx",
                (maxOf(w, h).takeIf { it > 0 } ?: maxOf(widthPx, heightPx)).toFloat() / maxOf(widthMm, heightMm),
            )
            ScreenModels(false, known.map { it.model() }, fits.map { it.model() }, panels == null && currentPlatform == PlatformKind.DESKTOP)
        }
    }
}

@Composable
private fun RulerScreen() {
    val detected = remember { screenPixels() }
    val models = rememberScreenModels(detected)
    val fallback = defaultPxPerMm()
    var saved by rememberSaveable { mutableStateOf(Prefs.store.get(PX_PER_MM_KEY)?.toFloatOrNull()) }
    var chosen by rememberSaveable { mutableStateOf(Prefs.store.get(SCREEN_KEY)) }
    val pxPerMm = saved ?: models.auto ?: fallback
    val model = models.all.firstOrNull { it.key == chosen } ?: models.matching.firstOrNull()?.takeIf { saved == null && models.auto != null }
    var calibrating by rememberSaveable { mutableStateOf(saved == null && models.auto == null && !models.loading) }
    LaunchedEffect(models.auto) {
        if (saved == null && models.auto != null) calibrating = false
    }
    var decided by rememberSaveable { mutableStateOf(!models.loading) }
    LaunchedEffect(models.loading) {
        if (models.loading || decided) return@LaunchedEffect
        decided = true
        if (saved == null && models.auto == null) calibrating = true
    }
    var mode by rememberSaveable { mutableStateOf(CalibrationMode.OBJECT) }
    var referenceIndex by rememberSaveable { mutableStateOf(0) }
    var axis by rememberSaveable { mutableStateOf(RulerAxis.ACROSS) }
    var measured by remember { mutableStateOf<Float?>(null) }
    var widthPx by rememberSaveable { mutableStateOf(detected?.first?.toString() ?: "") }
    var heightPx by rememberSaveable { mutableStateOf(detected?.second?.toString() ?: "") }
    var diagonal by rememberSaveable { mutableStateOf("") }
    val chrome = LocalChrome.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = onSurface, fontSize = 10.sp)
    val majorStyle = MaterialTheme.typography.titleMedium.copy(color = onSurface, fontSize = 18.sp)
    val vertical = axis == RulerAxis.ALONG

    fun save(value: Float, screen: String? = null) {
        val scale = value.coerceIn(1f, 40f)
        saved = scale
        chosen = screen
        Prefs.store.put(PX_PER_MM_KEY, scale.toString())
        if (screen == null) Prefs.store.remove(SCREEN_KEY) else Prefs.store.put(SCREEN_KEY, screen)
    }

    fun reset() {
        saved = null
        chosen = null
        Prefs.store.remove(PX_PER_MM_KEY)
        Prefs.store.remove(SCREEN_KEY)
    }

    if (!chrome.immersive) {
        if (models.all.isNotEmpty()) {
            DropdownChoice(
                options = listOf<ScreenModel?>(null) + models.all,
                selected = model,
                onSelect = { picked ->
                    if (picked == null) {
                        reset()
                    } else {
                        save(picked.pxPerMm, picked.key)
                        calibrating = false
                    }
                },
                label = if (models.apple) Res.string.model.str() else Res.string.monitor.str(),
                text = { it?.label ?: Res.string.not_chosen.str() },
                modifier = Modifier.fillMaxWidth(),
            )
            val hint = when {
                chosen != null || saved != null -> null
                models.matching.isEmpty() -> Res.string.screen_model_unknown.str()
                models.auto == null -> Res.string.several_models_fit_this_screen.str()
                else -> null
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SegmentedChoice(
            options = RulerAxis.entries,
            selected = axis,
            onSelect = { axis = it },
            label = { it.title.str() },
        )
        SwitchRow(
            Res.string.calibration.str(),
            calibrating,
            { calibrating = it },
            description = Res.string.ruler_match_the_outline.str(),
        )
    }

    if (calibrating && !chrome.immersive) {
        SegmentedChoice(
            options = CalibrationMode.entries,
            selected = mode,
            onSelect = { mode = it },
            label = { it.title.str() },
        )
        if (mode == CalibrationMode.OBJECT) {
            val reference = references[referenceIndex.coerceIn(references.indices)]
            ChoiceChips(
                options = references.indices.toList(),
                selected = referenceIndex,
                onSelect = { referenceIndex = it },
                label = { references[it].title.str() },
            )
            Canvas(Modifier.fillMaxWidth().height(if (reference.mm > 120) 260.dp else 150.dp)) {
                drawRect(container)
                val px = (reference.mm * pxPerMm).toFloat()
                if (reference.round) {
                    drawCircle(primary, px / 2, Offset(px / 2 + 12f, size.height / 2), style = Stroke(3f))
                } else {
                    val h = (54.0 * pxPerMm).toFloat().coerceAtMost(size.height - 24f)
                    drawRect(primary, Offset(12f, (size.height - h) / 2), Size(px, h), style = Stroke(3f))
                }
            }
            Slider(value = pxPerMm, onValueChange = { save(it) }, valueRange = 2f..24f)
        } else {
            Text(
                Res.string.ruler_system_often_reports.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(value = widthPx, onValueChange = { widthPx = it }, label = Res.string.width_px.str(), modifier = Modifier.weight(1f))
                NumberField(value = heightPx, onValueChange = { heightPx = it }, label = Res.string.height_px.str(), modifier = Modifier.weight(1f))
            }
            val computed = dpiFromDiagonal(
                widthPx.toIntOrNull() ?: 0,
                heightPx.toIntOrNull() ?: 0,
                diagonal.replace(',', '.').toDoubleOrNull() ?: 0.0,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(
                    value = diagonal,
                    onValueChange = { diagonal = it },
                    label = Res.string.diagonal_inches.str(),
                    modifier = Modifier.weight(1f),
                )
                if (computed != null) {
                    Text("${computed.fmt(1)} dpi", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { save((computed / 25.4).toFloat()) }) {
                        Text(Res.string.apply.str())
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { save(pxPerMm - 0.05f) }) { Text("−0.05") }
            TextButton(onClick = { save(pxPerMm + 0.05f) }) { Text("+0.05") }
            TextButton(onClick = { reset() }) { Text(Res.string.reset.str()) }
            TextButton(onClick = { calibrating = false }) { Text(Res.string.done.str()) }
        }
    }

    val height = when {
        chrome.immersive -> expandedHeight(normal = 0.dp, reserved = 0.dp)
        vertical -> 420.dp
        else -> 200.dp
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val along = if (chrome.immersive) height >= maxWidth else vertical
        LaunchedEffect(along) { measured = null }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(pxPerMm, along) {
                    val point: (Offset) -> Unit = { measured = if (along) it.y else it.x }
                    trackTouch(onStart = point, onMove = point)
                },
        ) {
            drawRect(container)
            drawRuler(along, pxPerMm, onSurface, measurer, labelStyle, majorStyle)
            measured?.let { drawMeasurement(along, it, primary) }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(if (along) Alignment.TopCenter else Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(12.dp),
        ) {
            val mm = measured?.div(pxPerMm)
            Text(
                text = mm?.let { "${(it / 10).toDouble().fmt(2)} ${Res.string.unit_cm.str()} · ${(it / 25.4).fmt(2)} in" }
                    ?: Res.string.drag_across_the_ruler.str(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    if (chrome.immersive) return

    ResultCard {
        KeyValueRow(
            Res.string.measured.str(),
            measured?.let { "${(it / pxPerMm).toDouble().fmt(1)} ${Res.string.unit_mm.str()}" } ?: "—",
            copyable = false,
        )
        KeyValueRow(Res.string.scale.str(), "${pxPerMm.toDouble().fmt(2)} px/mm · ${(pxPerMm * 25.4).fmt(0)} dpi", copyable = false)
        if (model != null) {
            KeyValueRow(if (models.apple) Res.string.model.str() else Res.string.monitor.str(), model.label, mono = false, copyable = false)
        } else {
            KeyValueRow(
                Res.string.detected_by_the_system.str(),
                screenDpi()?.let { "${it.toDouble().fmt(0)} dpi" } ?: Res.string.unknown_calibrate_manually.str(),
                mono = false,
                copyable = false,
            )
        }
        detected?.let {
            KeyValueRow(Res.string.screen.str(), "${it.first} × ${it.second} px", copyable = false)
        }
    }
}

private fun DrawScope.rulerPoint(vertical: Boolean, along: Float, depth: Float, fromFar: Boolean): Offset {
    val breadth = if (vertical) size.width else size.height
    val across = if (fromFar) breadth - depth else depth
    return if (vertical) Offset(across, along) else Offset(along, across)
}

private fun DrawScope.drawRuler(
    vertical: Boolean,
    pxPerMm: Float,
    color: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    majorStyle: TextStyle,
) {
    val length = if (vertical) size.height else size.width
    val pxPerCm = pxPerMm * 10
    val widest = measurer.measure("${(length / pxPerCm).toInt()}", labelStyle).size
    val everyCm = (if (vertical) widest.height.toFloat() else widest.width + 6f) + 4f <= pxPerCm
    var cm = 0
    while (cm * pxPerCm <= length) {
        val at = cm * pxPerCm
        val major = cm % 5 == 0
        drawLine(color, rulerPoint(vertical, at, 0f, false), rulerPoint(vertical, at, if (major) 56f else 44f, false), 2.5f)
        if (cm > 0 && (major || everyCm)) {
            val text = measurer.measure("$cm", if (major) majorStyle else labelStyle)
            val offset = if (vertical) {
                Offset(if (major) 62f else 50f, at - text.size.height / 2f)
            } else {
                Offset(at + 6f, if (major) 58f else 46f)
            }
            drawText(text, topLeft = offset)
        }
        for (m in 1..9) {
            val mark = at + m * pxPerMm
            if (mark > length) break
            val depth = if (m == 5) 28f else 15f
            drawLine(color, rulerPoint(vertical, mark, 0f, false), rulerPoint(vertical, mark, depth, false), 1f)
        }
        cm++
    }
    val pxPerInch = pxPerMm * 25.4f
    var inch = 0
    while (inch * pxPerInch <= length) {
        val at = inch * pxPerInch
        drawLine(color, rulerPoint(vertical, at, 0f, true), rulerPoint(vertical, at, 44f, true), 2.5f)
        if (inch > 0) {
            val text = measurer.measure("$inch\"", labelStyle)
            val breadth = if (vertical) size.width else size.height
            val offset = if (vertical) {
                Offset(breadth - 50f - text.size.width, at - text.size.height / 2f)
            } else {
                Offset(at + 6f, breadth - 50f - text.size.height)
            }
            drawText(text, topLeft = offset)
        }
        for (m in 1..15) {
            val mark = at + m * pxPerInch / 16
            if (mark > length) break
            val depth = when {
                m == 8 -> 28f
                m % 4 == 0 -> 22f
                m % 2 == 0 -> 16f
                else -> 10f
            }
            drawLine(color, rulerPoint(vertical, mark, 0f, true), rulerPoint(vertical, mark, depth, true), 1f)
        }
        inch++
    }
}

private fun DrawScope.drawMeasurement(vertical: Boolean, at: Float, color: Color) {
    val breadth = if (vertical) size.width else size.height
    val band = (breadth - 160f).coerceAtLeast(0f)
    val topLeft = if (vertical) Offset(80f, 0f) else Offset(0f, 80f)
    val area = if (vertical) Size(band, at) else Size(at, band)
    drawRect(color.copy(alpha = 0.22f), topLeft, area)
    drawLine(color, rulerPoint(vertical, at, 0f, false), rulerPoint(vertical, at, breadth, false), 3f)
}

val protractorTool = Tool(
    id = "protractor",
    category = ToolCategory.MEASURE,
    title = Res.string.protractor,
    description = Res.string.protractor_description,
    icon = Icons.Filled.Timeline,
    keywords = listOf("angle", "degrees", "угол", "градусы"),
    expandable = true,
) { ProtractorScreen() }

@Composable
private fun ProtractorScreen() {
    var a1 by remember { mutableStateOf(0f) }
    var a2 by remember { mutableStateOf(60f) }
    var active by remember { mutableStateOf(1) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(expandedHeight(normal = 320.dp, reserved = 260.dp))
            .pointerInput(Unit) {
                val centre = { Offset(size.width / 2f, size.height * 0.75f) }
                trackTouch(
                    onStart = { pos ->
                        val angle = angleOf(pos - centre())
                        active = if (abs(angleDiff(angle, a1)) < abs(angleDiff(angle, a2))) 1 else 2
                        if (active == 1) a1 = angle else a2 = angle
                    },
                    onMove = { pos ->
                        val angle = angleOf(pos - centre())
                        if (active == 1) a1 = angle else a2 = angle
                    },
                )
            },
    ) {
        val c = Offset(size.width / 2f, size.height * 0.75f)
        val r = minOf(size.width / 2f, size.height * 0.7f) - 8f
        drawRect(container)
        for (deg in 0..180 step 5) {
            val rad = (180 - deg) * PI / 180
            val len = if (deg % 30 == 0) 24f else if (deg % 10 == 0) 16f else 8f
            drawLine(
                onSurface,
                Offset(c.x + (r - len) * cos(rad).toFloat(), c.y - (r - len) * sin(rad).toFloat()),
                Offset(c.x + r * cos(rad).toFloat(), c.y - r * sin(rad).toFloat()),
                if (deg % 30 == 0) 2f else 1f,
            )
        }
        drawArc(onSurface, 180f, 180f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2f))
        drawLine(onSurface, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f)
        fun arm(angle: Float, color: Color) {
            val rad = angle * PI / 180
            drawLine(color, c, Offset(c.x + r * cos(rad).toFloat(), c.y - r * sin(rad).toFloat()), 5f)
        }
        arm(a1, primary)
        arm(a2, tertiary)
        drawCircle(onSurface, 6f, c)
    }
    val between = abs(angleDiff(a1, a2))
    Text(
        "${between.toDouble().fmt(1)}°",
        style = MaterialTheme.typography.displayMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    ResultCard {
        KeyValueRow(Res.string.arm_1.str(), "${normalize(a1).toDouble().fmt(1)}°", copyable = false)
        KeyValueRow(Res.string.arm_2.str(), "${normalize(a2).toDouble().fmt(1)}°", copyable = false)
        KeyValueRow(Res.string.supplementary.str(), "${(180 - between).toDouble().fmt(1)}°", copyable = false)
    }
}

private fun angleOf(v: Offset): Float = (atan2(-v.y, v.x) * 180 / PI).toFloat()

private fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

private fun normalize(a: Float): Float = ((a % 360) + 360) % 360
