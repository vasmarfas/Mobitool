package com.vasmarfas.card.tools.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.MediaException
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.pickFiles
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.cancellation.CancellationException

val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tif", "tiff", "ico")
val videoExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "flv", "wmv", "ts", "mts", "m2ts", "mpg", "mpeg", "ogv")
val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "aiff", "aif", "amr", "caf", "mka")

@Composable
fun PickButton(
    text: String,
    extensions: Set<String>,
    kind: PickKind = PickKind.FILE,
    multiple: Boolean = false,
    icon: ImageVector = Icons.Filled.FileOpen,
    empty: Boolean = false,
    onPicked: (List<PlatformFile>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pick: () -> Unit = {
        scope.launch {
            val files = runCatching { pickFiles(extensions, kind, multiple) }.getOrDefault(emptyList())
            if (files.isNotEmpty()) onPicked(files)
        }
    }
    if (!empty) {
        ActionButton(text = text, icon = icon, onClick = pick)
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = pick,
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            val formats = extensions.map { it.uppercase() }.filter { it != "JPEG" && it != "TIFF" && it != "HEIF" && it != "AIF" }
            Text(
                formats.take(6).joinToString(", ") + if (formats.size > 6) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = total / 60_000 % 60
    val seconds = total / 1000 % 60
    val tenths = total / 100 % 10
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}.$tenths"
    }
}

internal suspend fun errorText(e: Throwable): String = when (e) {
    is MediaException -> getString(e.reason)
    is Error -> getString(Res.string.out_of_memory)
    else -> e.message ?: e.toString()
}

@Stable
class TaskState {
    var progress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    val running: Boolean get() = progress != null

    fun launch(scope: CoroutineScope, block: suspend (onProgress: (Float) -> Unit) -> Unit) {
        job?.cancel()
        error = null
        progress = 0f
        job = scope.launch {
            try {
                block { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = errorText(e)
            } finally {
                progress = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        progress = null
    }
}

@Composable
fun TaskProgress(state: TaskState, label: String) {
    val progress = state.progress
    if (progress != null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${(progress * 100).toDouble().fmt(0)} %", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = state::cancel) { Text(Res.string.cancel.str()) }
            }
            if (progress > 0f) {
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
    state.error?.let { ErrorText(it) }
}

@Composable
fun SaveButton(text: String = Res.string.save_file.str(), save: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    ActionButton(
        text = if (saved) Res.string.saved.str() else text,
        icon = if (saved) Icons.Filled.Check else Icons.Filled.Save,
        onClick = {
            scope.launch {
                failure = null
                runCatching { save() }
                    .onSuccess { saved = it }
                    .onFailure { failure = it.message ?: it.toString() }
            }
        },
    )
    failure?.let { ErrorText(it) }
}

@Composable
fun Preview(image: ImageBitmap?, modifier: Modifier = Modifier) {
    if (image == null) return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimSlider(durationMs: Long, range: ClosedFloatingPointRange<Float>, onChange: (ClosedFloatingPointRange<Float>) -> Unit) {
    if (durationMs <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Res.string.trim.str(), style = MaterialTheme.typography.titleSmall)
        RangeSlider(value = range, onValueChange = onChange, valueRange = 0f..1f)
        Row(Modifier.fillMaxWidth()) {
            val start = (range.start * durationMs).toLong()
            val end = (range.endInclusive * durationMs).toLong()
            Text(formatClock(start), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatClock(end - start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(formatClock(end), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SizeChange(before: Long, after: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${formatBytes(before, binary = false)} → ${formatBytes(after, binary = false)}", style = MaterialTheme.typography.bodyLarge)
        if (before > 0) {
            Spacer(Modifier.width(12.dp))
            val change = (after - before) * 100.0 / before
            Text(
                (if (change > 0) "+" else "") + change.fmt(0) + " %",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun FileLine(name: String, detail: String, trailing: (@Composable () -> Unit)? = null, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun EditButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
