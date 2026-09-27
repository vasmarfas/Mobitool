package com.vasmarfas.card.tools.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.SensorType
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.expandedSquare
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

val levelTool = Tool(
    id = "bubble-level",
    category = ToolCategory.MEASURE,
    title = Res.string.bubble_level,
    description = Res.string.bubble_level_description,
    icon = Icons.Filled.Architecture,
    keywords = listOf("spirit level", "inclinometer", "angle", "tilt", "уклон", "наклон", "ватерпас"),
    platforms = PlatformKind.mobileAndWeb,
    expandable = true,
) { LevelScreen() }

@Composable
private fun LevelScreen() {
    val session = rememberSensor(SensorType.ACCELEROMETER)
    SensorGate(session) { reading ->
        val x = reading.x
        val y = reading.y
        val z = reading.z
        val g = sqrt(x * x + y * y + z * z).coerceAtLeast(0.01f)
        val pitch = Math_deg(atan2(y, sqrt(x * x + z * z)))
        val roll = Math_deg(atan2(-x, sqrt(y * y + z * z)))
        val flat = abs(pitch) < 1 && abs(roll) < 1
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LevelDial(x / g, y / g, flat, Modifier.size(expandedSquare(normal = 280.dp, reserved = 320.dp)))
        }
        Text(
            "${roll.toDouble().fmt(1)}°  ·  ${pitch.toDouble().fmt(1)}°",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = if (flat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        ResultCard {
            KeyValueRow(Res.string.roll_x.str(), "${roll.toDouble().fmt(1)}°", copyable = false)
            KeyValueRow(Res.string.pitch_y.str(), "${pitch.toDouble().fmt(1)}°", copyable = false)
            KeyValueRow(Res.string.total_tilt.str(), "${Math_deg(kotlin.math.acos((abs(z) / g).coerceIn(0f, 1f))).toDouble().fmt(1)}°", copyable = false)
            KeyValueRow("g", "${(g / 9.80665f).toDouble().fmt(3)} (${g.toDouble().fmt(2)} ${Res.string.unit_mps2.str()})", copyable = false)
        }
    }
}

@Composable
private fun LevelDial(nx: Float, ny: Float, flat: Boolean, modifier: Modifier) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val ring = MaterialTheme.colorScheme.outline
    val bubble = if (flat) LocalStatusColors.current.good else MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(container, radius, center)
        drawCircle(ring, radius - 2, center, style = Stroke(2f))
        drawCircle(ring, radius * 0.15f, center, style = Stroke(2f))
        drawLine(ring, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
        drawLine(ring, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)
        val bx = center.x - nx.coerceIn(-1f, 1f) * radius * 0.85f
        val by = center.y + ny.coerceIn(-1f, 1f) * radius * 0.85f
        drawCircle(bubble, radius * 0.13f, Offset(bx, by))
    }
}

@Suppress("FunctionName")
private fun Math_deg(rad: Float): Float = (rad * 180.0 / kotlin.math.PI).toFloat()
