package com.vasmarfas.card.tools.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.expandedHeight
import kotlin.math.roundToInt

private enum class GradientKind { LINEAR, RADIAL }

val gradientGeneratorTool = Tool(
    id = "gradient-generator",
    category = ToolCategory.DESIGN,
    title = Res.string.gradient_generator,
    description = Res.string.gradient_generator_description,
    icon = Icons.Filled.Gradient,
    keywords = listOf("gradient", "css", "brush", "linear", "radial", "градиент", "переход", "фон"),
    expandable = true,
) { GradientGeneratorScreen() }

@Composable
private fun GradientGeneratorScreen() {
    val stops = remember { mutableStateListOf("#6750A4", "#00696D") }
    var kind by rememberSaveable { mutableStateOf(GradientKind.LINEAR) }
    var angle by rememberSaveable { mutableStateOf(90f) }
    val parsed = stops.map { ColorMath.parse(it) }

    stops.forEachIndexed { index, value ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ToolInputField(
                value = value,
                onValueChange = { stops[index] = it },
                label = "${Res.string.gradient_stop.str()} ${index + 1}",
                modifier = Modifier.weight(1f),
                isError = parsed[index] == null,
                monospace = true,
            )
            IconButton(onClick = { stops.removeAt(index) }, enabled = stops.size > 2) {
                Icon(Icons.Filled.Close, contentDescription = Res.string.remove.str())
            }
        }
    }
    if (stops.size < 4) {
        ActionButton(
            text = Res.string.add_stop.str(),
            onClick = { stops.add("#FFFFFF") },
            icon = Icons.Filled.Add,
        )
    }
    SegmentedChoice(
        options = GradientKind.entries,
        selected = kind,
        onSelect = { kind = it },
        label = { if (it == GradientKind.LINEAR) Res.string.linear.str() else Res.string.radial.str() },
    )
    if (kind == GradientKind.LINEAR) {
        Text("${Res.string.angle.str()}: ${angle.roundToInt()}°", style = MaterialTheme.typography.labelLarge)
        Slider(value = angle, onValueChange = { angle = it }, valueRange = 0f..360f)
    }
    if (parsed.any { it == null }) {
        ErrorText(Res.string.check_the_colors.str())
        return
    }
    val colors = parsed.filterNotNull()
    val angleInt = angle.roundToInt()
    val (startX, startY) = Gradients.startOffset(angleInt, 600)
    val (endX, endY) = Gradients.endOffset(angleInt, 600)
    val brush = if (kind == GradientKind.LINEAR) {
        Brush.linearGradient(colors.map { it.toColor() }, start = Offset(startX.toFloat(), startY.toFloat()), end = Offset(endX.toFloat(), endY.toFloat()))
    } else {
        Brush.radialGradient(colors.map { it.toColor() })
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(expandedHeight(normal = 160.dp, reserved = 420.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(brush),
    )
    val css = if (kind == GradientKind.LINEAR) Gradients.cssLinear(angleInt, colors) else Gradients.cssRadial(colors)
    val compose = if (kind == GradientKind.LINEAR) Gradients.composeLinear(angleInt, colors) else Gradients.composeRadial(colors)
    ResultCard("CSS") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoText("background: $css;", Modifier.weight(1f))
            CopyIconButton("background: $css;")
        }
    }
    ResultCard("Compose") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoText(compose, Modifier.weight(1f))
            CopyIconButton(compose)
        }
    }
}
