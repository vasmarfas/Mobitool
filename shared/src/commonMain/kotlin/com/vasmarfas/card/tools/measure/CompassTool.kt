package com.vasmarfas.card.tools.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.cos
import kotlin.math.sin

val compassTool = Tool(
    id = "compass",
    category = ToolCategory.MEASURE,
    title = Res.string.compass,
    description = Res.string.compass_description,
    icon = Icons.Filled.Explore,
    keywords = listOf("heading", "azimuth", "north", "magnetic", "азимут", "север", "направление"),
    platforms = PlatformKind.mobileAndWeb,
    expandable = true,
) { CompassScreen() }

private val compassPoints = listOf(
    Res.string.compass_n, Res.string.compass_ne, Res.string.compass_e, Res.string.compass_se,
    Res.string.compass_s, Res.string.compass_sw, Res.string.compass_w, Res.string.compass_nw,
)

@Composable
private fun headingName(deg: Float): String = compassPoints[(((deg + 22.5f) % 360) / 45).toInt().coerceIn(0, 7)].str()

@Composable
private fun CompassScreen() {
    val session = rememberSensor(SensorType.ORIENTATION)
    SensorGate(session) { reading ->
        val heading = ((reading.x % 360) + 360) % 360
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CompassDial(heading, Modifier.size(expandedSquare(normal = 280.dp, reserved = 320.dp)))
        }
        Text(
            "${heading.toDouble().fmt(0)}° ${headingName(heading)}",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        ResultCard {
            KeyValueRow(Res.string.pitch.str(), "${reading.y.toDouble().fmt(1)}°", copyable = false)
            KeyValueRow(Res.string.compass_roll.str(), "${reading.z.toDouble().fmt(1)}°", copyable = false)
        }
        Text(
            Res.string.compass_keep_the_device_level.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompassDial(heading: Float, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val north = LocalStatusColors.current.bad
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(container, radius, center)
        rotate(-heading, center) {
            for (i in 0 until 360 step 5) {
                val angle = Math_toRadians(i.toDouble() - 90)
                val outer = radius - 6
                val inner = if (i % 30 == 0) radius - 26 else if (i % 10 == 0) radius - 16 else radius - 10
                drawLine(
                    if (i == 0) north else onSurface,
                    Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                    strokeWidth = if (i % 30 == 0) 3f else 1.5f,
                )
            }
        }
        drawCircle(primary, 6f, center)
        drawLine(primary, Offset(center.x, center.y - radius + 30), Offset(center.x, center.y - radius + 2), strokeWidth = 5f)
        drawCircle(onSurface, radius - 2, center, style = Stroke(2f))
    }
}

@Suppress("FunctionName")
private fun Math_toRadians(deg: Double): Double = deg / 180.0 * kotlin.math.PI
