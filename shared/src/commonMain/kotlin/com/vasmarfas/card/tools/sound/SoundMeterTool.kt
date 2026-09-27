package com.vasmarfas.card.tools.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.ensurePermission
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.microphoneLevelFlow
import com.vasmarfas.card.core.microphoneSupported
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.measure.LineChart
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChartKind
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

val soundMeterTool = Tool(
    id = "sound-meter",
    category = ToolCategory.SOUND,
    title = Res.string.sound_meter,
    description = Res.string.sound_meter_description,
    icon = Icons.Filled.Mic,
    keywords = listOf("decibel", "noise", "db", "microphone", "loudness", "шум", "децибел", "громкость"),
    expandable = true,
) { SoundMeterScreen() }

@Composable
private fun SoundMeterScreen() {
    var refused by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<Float>() }
    val scope = rememberCoroutineScope()
    if (!microphoneSupported()) {
        Text(Res.string.not_available_on_this_platform.str())
        return
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        error = null
        microphoneLevelFlow().catch { error = it.message ?: it.toString(); running = false }.collect { db ->
            level = db
            history.add(db.toFloat())
            if (history.size > 200) history.removeAt(0)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            text = if (running) Res.string.stop.str() else Res.string.start.str(),
            onClick = {
                if (running) running = false
                else scope.launch { if (ensurePermission(AppPermission.MICROPHONE)) running = true else refused = true }
            },
        )
        TextButton(onClick = { history.clear() }) { Text(Res.string.reset.str()) }
    }
    if (refused) {
        PermissionPrompt(AppPermission.MICROPHONE, Res.string.mic_access_needed.str()) {
            refused = false
            running = true
        }
    }
    error?.let { ErrorText(micErrorLabel(it).str()) }
    level?.let { db ->
        Text("${db.fmt(0)} ${Res.string.unit_db.str()}", style = MaterialTheme.typography.displayLarge)
        LineChart(
            series = listOf(history.toList()),
            colors = listOf(MaterialTheme.colorScheme.primary),
            symmetric = false,
            kind = ChartKind.AREA,
        )
        ResultCard {
            if (history.isNotEmpty()) KeyValueRow(Res.string.min_avg_max.str(), "${history.min().toDouble().fmt(0)} / ${history.average().fmt(0)} / ${history.max().toDouble().fmt(0)} ${Res.string.unit_db.str()}", copyable = false)
            KeyValueRow(Res.string.reference.str(), noiseReference(db).str(), mono = false, copyable = false)
        }
    }
}

private fun noiseReference(db: Double): StringResource = when {
    db < 30 -> Res.string.whisper_quiet_room
    db < 45 -> Res.string.quiet_office_library
    db < 60 -> Res.string.normal_conversation
    db < 70 -> Res.string.busy_street_vacuum_cleaner
    db < 85 -> Res.string.heavy_traffic_loud_music
    db < 100 -> Res.string.sound_meter_motorcycle_power_tools
    else -> Res.string.sound_meter_concert_chainsaw
}
