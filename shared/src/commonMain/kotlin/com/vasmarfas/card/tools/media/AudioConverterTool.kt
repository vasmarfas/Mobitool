package com.vasmarfas.card.tools.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.ClipKind
import com.vasmarfas.card.core.MediaClip
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.MediaProject
import com.vasmarfas.card.core.MediaResult
import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.discard
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.save
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch

val audioConverterTool = Tool(
    id = "audio-converter",
    category = ToolCategory.MEDIA,
    title = Res.string.audio_converter,
    description = Res.string.audio_converter_description,
    icon = Icons.Filled.AudioFile,
    keywords = listOf(
        "audio", "convert", "mp3", "wav", "flac", "ogg", "opus", "m4a", "aac", "wma", "bitrate",
        "аудио", "звук", "музыка", "конвертер", "в mp3", "перевести в mp3", "битрейт",
    ),
) { AudioConverterScreen() }

@Composable
private fun AudioConverterScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf(MediaFormat.MP3) }
    var kbps by rememberSaveable { mutableStateOf(192) }
    var rate by rememberSaveable { mutableStateOf(0) }
    var channels by rememberSaveable { mutableStateOf(0) }
    var range by remember { mutableStateOf(0f..1f) }
    var result by remember { mutableStateOf<MediaResult?>(null) }
    val task = remember { TaskState() }
    val noAudio = Res.string.no_audio_track.str()
    DisposableEffect(Unit) { onDispose { result?.discard() } }

    PickButton(Res.string.choose_audio.str(), audioExtensions + videoExtensions, empty = file == null) { files ->
        val picked = files.first()
        result?.discard()
        result = null
        file = picked
        info = null
        loadError = null
        range = 0f..1f
        scope.launch {
            runCatching { MediaEngine.probe(picked) }
                .onSuccess { if (it.hasAudio) info = it else loadError = noAudio }
                .onFailure { loadError = errorText(it) }
        }
    }
    loadError?.let { ErrorText(it) }
    val source = file ?: return
    val probed = info ?: return
    ResultCard(title = source.name) {
        FilePreview(source, probed)
        MediaInfoRows(source, probed)
    }

    val formats = MediaFormat.entries.filter { !it.video && it in MediaEngine.formats }
    val target = if (format in formats) format else formats.first()
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = formats, selected = target, onSelect = { format = it }, label = { it.extension.uppercase() })
    }
    if (target.lossy) {
        ToolSection(Res.string.bitrate.str()) {
            ChoiceChips(options = listOf(64, 96, 128, 192, 256, 320), selected = kbps, onSelect = { kbps = it }, label = { "$it ${Res.string.unit_kbit_s.str()}" })
        }
    }
    if (target != MediaFormat.OGG) {
        ToolSection(Res.string.sample_rate.str()) {
            ChoiceChips(
                options = listOf(0, 22_050, 44_100, 48_000),
                selected = rate,
                onSelect = { rate = it },
                label = { if (it == 0) Res.string.as_source.str() else "${(it / 1000.0).fmt(2)} ${Res.string.unit_khz.str()}" },
            )
        }
    }
    ToolSection(Res.string.channels.str()) {
        ChoiceChips(
            options = listOf(0, 1, 2),
            selected = channels,
            onSelect = { channels = it },
            label = {
                when (it) {
                    1 -> Res.string.mono.str()
                    2 -> Res.string.stereo.str()
                    else -> Res.string.as_source.str()
                }
            },
        )
    }
    TrimSlider(probed.durationMs, range) { range = it }

    ActionButton(
        text = Res.string.convert.str(),
        icon = Icons.Filled.GraphicEq,
        enabled = !task.running,
        onClick = {
            result?.discard()
            result = null
            val start = (range.start * probed.durationMs).toLong()
            val end = (range.endInclusive * probed.durationMs).toLong().coerceAtLeast(start + 100)
            val spec = MediaSpec(format = target, audioBitrateKbps = kbps, sampleRate = rate, channels = channels)
            task.launch(scope) { progress ->
                result = MediaEngine.export(MediaProject(MediaClip(source, ClipKind.AUDIO, start, end), spec), progress)
            }
        },
    )
    TaskProgress(task, Res.string.converting.str())
    result?.let { output ->
        ResultCard(title = Res.string.result.str()) {
            ResultPreview(output, renamed(source.name, target.extension))
            SizeChange(source.size(), output.size)
            SaveButton { output.save(renamed(source.name, target.extension)) }
        }
    }
}
