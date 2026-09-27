package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.ClipKind
import com.vasmarfas.card.core.MediaClip
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.MediaProject
import com.vasmarfas.card.core.MediaResult
import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.PreviewState
import com.vasmarfas.card.core.VideoQuality
import com.vasmarfas.card.core.discard
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.save
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch

val videoConverterTool = Tool(
    id = "video-converter",
    category = ToolCategory.MEDIA,
    title = Res.string.video_converter,
    description = Res.string.video_converter_description,
    icon = Icons.Filled.VideoFile,
    keywords = listOf(
        "video", "convert", "mp4", "mkv", "avi", "mov", "webm", "compress", "extract audio", "mp3", "resize",
        "видео", "конвертер", "сжать видео", "звук из видео", "перекодировать", "уменьшить видео",
    ),
) { VideoConverterScreen() }

// short side, 0 keeps the source, -1 takes the typed width and height
private val videoHeights = listOf(0, 2160, 1440, 1080, 720, 480, 360, -1)

@Composable
internal fun MediaInfoRows(file: PlatformFile, info: MediaInfo) {
    KeyValueRow(Res.string.duration.str(), formatClock(info.durationMs), copyable = false)
    if (info.hasVideo) KeyValueRow(Res.string.video_resolution.str(), "${info.width} × ${info.height}", copyable = false)
    val codecs = listOfNotNull(info.videoCodec, info.audioCodec?.let { codec -> codec + if (info.sampleRate > 0) " · ${info.sampleRate} ${Res.string.unit_hz.str()}" else "" })
    if (codecs.isNotEmpty()) KeyValueRow(Res.string.codecs.str(), codecs.joinToString(" / "), copyable = false)
    KeyValueRow(Res.string.size.str(), formatBytes(file.size(), binary = false), copyable = false)
}

@Composable
private fun VideoConverterScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    val player = remember(file) { PreviewState() }
    var loadError by remember { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf(MediaFormat.MP4) }
    var height by rememberSaveable { mutableStateOf(0) }
    var customWidth by rememberSaveable { mutableStateOf("1920") }
    var customHeight by rememberSaveable { mutableStateOf("1080") }
    var quality by rememberSaveable { mutableStateOf(VideoQuality.MEDIUM) }
    var copy by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(false) }
    var limitMb by rememberSaveable { mutableStateOf("25") }
    var mute by rememberSaveable { mutableStateOf(false) }
    var audioKbps by rememberSaveable { mutableStateOf(192) }
    var range by remember { mutableStateOf(0f..1f) }
    var result by remember { mutableStateOf<MediaResult?>(null) }
    val task = remember { TaskState() }
    DisposableEffect(Unit) { onDispose { result?.discard() } }

    PickButton(Res.string.choose_video.str(), videoExtensions, PickKind.VIDEO, empty = file == null) { files ->
        val picked = files.first()
        result?.discard()
        result = null
        file = picked
        info = null
        loadError = null
        range = 0f..1f
        scope.launch {
            runCatching { MediaEngine.probe(picked) }
                .onSuccess { info = it }
                .onFailure { loadError = errorText(it) }
        }
    }
    loadError?.let { ErrorText(it) }
    val source = file ?: return
    val probed = info ?: return
    ResultCard(title = source.name) {
        FilePreview(source, probed, player)
        MediaInfoRows(source, probed)
    }

    val formats = MediaFormat.entries.filter { it in MediaEngine.formats && (probed.hasVideo || !it.video) }
    val target = if (format in formats) format else formats.first()
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = formats, selected = target, onSelect = { format = it }, label = { it.extension.uppercase() })
    }
    if (target.video) {
        SwitchRow(
            Res.string.copy_streams.str(),
            copy,
            { copy = it },
            description = Res.string.copy_streams_hint.str(),
        )
        if (!copy) {
            ToolSection(Res.string.video_resolution.str()) {
                ChoiceChips(
                    options = videoHeights,
                    selected = height,
                    onSelect = { height = it },
                    label = {
                        when (it) {
                            0 -> Res.string.as_source.str()
                            -1 -> Res.string.custom_size.str()
                            2160 -> "4K"
                            else -> "${it}p"
                        }
                    },
                )
                if (height == -1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(customWidth, { customWidth = it }, Res.string.width_px.str(), modifier = Modifier.weight(1f))
                        NumberField(customHeight, { customHeight = it }, Res.string.height_px.str(), modifier = Modifier.weight(1f))
                    }
                }
            }
            SwitchRow(Res.string.limit_file_size.str(), limit, { limit = it })
            if (limit) {
                NumberField(limitMb, { limitMb = it }, Res.string.target_size.str(), suffix = Res.string.unit_mb.str())
            } else {
                SegmentedChoice(
                    options = VideoQuality.entries,
                    selected = quality,
                    onSelect = { quality = it },
                    label = { qualityLabel(it) },
                )
            }
        }
        if (probed.hasAudio) SwitchRow(Res.string.without_sound.str(), mute, { mute = it })
    } else if (target.lossy) {
        ToolSection(Res.string.bitrate.str()) {
            ChoiceChips(options = listOf(96, 128, 192, 256, 320), selected = audioKbps, onSelect = { audioKbps = it }, label = { "$it ${Res.string.unit_kbit_s.str()}" })
        }
    }
    TrimSlider(probed.durationMs, range) { changed ->
        val moved = if (changed.start != range.start) changed.start else changed.endInclusive
        range = changed
        player.playing = false
        player.seek((moved * probed.durationMs).toLong())
    }

    ActionButton(
        text = Res.string.convert.str(),
        icon = Icons.Filled.Movie,
        enabled = !task.running,
        onClick = {
            result?.discard()
            result = null
            val start = (range.start * probed.durationMs).toLong()
            val end = (range.endInclusive * probed.durationMs).toLong().coerceAtLeast(start + 100)
            val landscape = probed.width >= probed.height
            val custom = height == -1
            val spec = MediaSpec(
                format = target,
                width = when {
                    custom -> (customWidth.toIntOrNull() ?: probed.width).coerceIn(16, 7680) / 2 * 2
                    height > 0 && !landscape -> height
                    else -> 0
                },
                height = when {
                    custom -> (customHeight.toIntOrNull() ?: probed.height).coerceIn(16, 7680) / 2 * 2
                    height > 0 && landscape -> height
                    else -> 0
                },
                quality = quality,
                targetBytes = if (limit) ((limitMb.toDoubleLenient() ?: 0.0) * 1_000_000).toLong() else 0,
                audioBitrateKbps = audioKbps,
                keepAudio = probed.hasAudio && !(target.video && mute),
                copyStreams = copy && target.video,
            )
            task.launch(scope) { progress ->
                result = MediaEngine.export(MediaProject(MediaClip(source, ClipKind.VIDEO, start, end), spec), progress)
            }
        },
    )
    TaskProgress(task, Res.string.converting.str())
    result?.let { output ->
        ResultCard(title = Res.string.result.str()) {
            if (target.video) ResultPreview(output, renamed(source.name, target.extension))
            SizeChange(source.size(), output.size)
            SaveButton { output.save(renamed(source.name, target.extension)) }
        }
    }
}

internal val MediaFormat.lossy: Boolean get() = this == MediaFormat.MP3 || this == MediaFormat.M4A || this == MediaFormat.OGG

@Composable
internal fun qualityLabel(quality: VideoQuality): String = when (quality) {
    VideoQuality.HIGH -> Res.string.quality_high.str()
    VideoQuality.MEDIUM -> Res.string.quality_medium.str()
    VideoQuality.LOW -> Res.string.quality_low.str()
}
