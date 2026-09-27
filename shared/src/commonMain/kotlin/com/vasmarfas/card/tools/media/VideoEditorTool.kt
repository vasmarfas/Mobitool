package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.ClipBox
import com.vasmarfas.card.core.ClipKind
import com.vasmarfas.card.core.MediaClip
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.MediaResult
import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.PreviewState
import com.vasmarfas.card.core.TrackKind
import com.vasmarfas.card.core.VideoQuality
import com.vasmarfas.card.core.asFile
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.discard
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.save
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.EmptyState
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val videoEditorTool = Tool(
    id = "video-editor",
    category = ToolCategory.MEDIA,
    title = Res.string.video_editor,
    description = Res.string.video_editor_description,
    icon = Icons.Filled.MovieCreation,
    keywords = listOf(
        "video editor", "montage", "join videos", "trim", "slideshow", "music", "clips", "timeline", "tracks", "picture in picture", "4k",
        "видеоредактор", "монтаж", "склеить видео", "обрезать видео", "слайдшоу", "фото в видео", "наложить музыку", "дорожки", "картинка в картинке",
    ),
    expandable = true,
) { VideoEditorScreen() }

private enum class Aspect(val ratio: Double?, val label: String) {
    SOURCE(null, ""),
    WIDE(16.0 / 9, "16:9"),
    TALL(9.0 / 16, "9:16"),
    SQUARE(1.0, "1:1"),
    CLASSIC(4.0 / 3, "4:3"),
    PORTRAIT(4.0 / 5, "4:5"),
}

// short side, 0 takes the typed width and height
private val shortSides = listOf(2160, 1440, 1080, 720, 480, 0)

private fun sideLabel(side: Int): String = when (side) {
    2160 -> "4K"
    0 -> ""
    else -> "${side}p"
}

private enum class Corner(val x: Float, val y: Float) { CENTER(0.5f, 0.5f), TOP_LEFT(0f, 0f), TOP_RIGHT(1f, 0f), BOTTOM_LEFT(0f, 1f), BOTTOM_RIGHT(1f, 1f) }

private fun Corner.box(scale: Float): ClipBox {
    val margin = 0.04f
    fun axis(edge: Float) = when (edge) {
        0f -> margin + scale / 2
        1f -> 1 - margin - scale / 2
        else -> 0.5f
    }
    return ClipBox(axis(x).coerceIn(0f, 1f), axis(y).coerceIn(0f, 1f), scale)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoEditorScreen() {
    if (!MediaEngine.canEdit) {
        EmptyState(icon = Icons.Filled.MovieCreation, title = Res.string.editing_not_supported.str(), description = Res.string.editing_not_supported_hint.str())
        return
    }
    val scope = rememberCoroutineScope()
    val timeline = remember { Timeline() }
    val cache = remember { MediaCache(scope) }
    val preview = remember { PreviewState() }
    val zoom = remember { TimelineZoom() }
    var draft by remember { mutableStateOf<Pair<Int, MediaClip>?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf(MediaFormat.MP4) }
    var shortSide by rememberSaveable { mutableStateOf(1080) }
    var aspect by rememberSaveable { mutableStateOf(Aspect.SOURCE) }
    var customWidth by rememberSaveable { mutableStateOf("1920") }
    var customHeight by rememberSaveable { mutableStateOf("1080") }
    var fps by rememberSaveable { mutableStateOf(30) }
    var quality by rememberSaveable { mutableStateOf(VideoQuality.MEDIUM) }
    var result by remember { mutableStateOf<MediaResult?>(null) }
    val task = remember { TaskState() }
    DisposableEffect(Unit) { onDispose { result?.discard() } }

    val formats = MediaFormat.entries.filter { it.video && it in MediaEngine.formats }
    val target = if (format in formats) format else formats.first()
    val firstInfo = timeline.tracks.filter { it.kind == TrackKind.VIDEO }.flatMap { it.clips }.minByOrNull { it.clip.atMs }?.let { cache.infos[it.clip.file] }
    val (width, height) = if (shortSide == 0) {
        ((customWidth.toIntOrNull() ?: 1920).coerceIn(16, 7680) / 2 * 2) to ((customHeight.toIntOrNull() ?: 1080).coerceIn(16, 7680) / 2 * 2)
    } else {
        val ratio = aspect.ratio ?: firstInfo?.let { it.width.toDouble() / it.height.coerceAtLeast(1) }?.takeIf { it > 0 } ?: (16.0 / 9)
        val (w, h) = if (ratio >= 1) (shortSide * ratio).roundToInt() to shortSide else shortSide to (shortSide / ratio).roundToInt()
        (w / 2 * 2) to (h / 2 * 2)
    }
    val spec = MediaSpec(format = target, width = width, height = height, frameRate = fps, quality = quality)
    val editing = draft
    val project = remember(timeline.tracks, spec, editing) {
        val base = timeline.project(spec)
        if (editing == null) base else base.copy(tracks = base.tracks.mapIndexed { i, track ->
            val ids = timeline.tracks[i].clips.map { it.id }
            track.copy(clips = track.clips.mapIndexed { j, clip -> if (ids[j] == editing.first) editing.second else clip })
        })
    }
    val totalMs = timeline.durationMs

    fun add(files: List<PlatformFile>, kind: ClipKind) {
        addError = null
        scope.launch {
            val clips = files.mapNotNull { file ->
                runCatching { cache.probe(file) }
                    .onFailure { addError = "${file.name}: ${errorText(it)}" }
                    .getOrNull()
                    ?.let { info ->
                        when {
                            kind == ClipKind.IMAGE -> MediaClip(file, kind, 0, 3000) to Long.MAX_VALUE
                            info.durationMs <= 0 -> null
                            kind == ClipKind.AUDIO && !info.hasAudio -> null
                            else -> MediaClip(file, kind, 0, info.durationMs) to info.durationMs
                        }?.also { cache.prepare(file, kind) }
                    }
            }
            timeline.add(if (kind == ClipKind.AUDIO) TrackKind.AUDIO else TrackKind.VIDEO, clips, preview.positionMs)
        }
    }

    PreviewFrame(project.takeIf { !timeline.isEmpty }, preview, width.toFloat() / height)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayButton(preview, totalMs)
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatClock(preview.positionMs)} / ${formatClock(totalMs)}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { timeline.undo() }, enabled = timeline.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = Res.string.undo.str()) }
        IconButton(onClick = { timeline.redo() }, enabled = timeline.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = Res.string.redo.str()) }
    }
    TimelineView(timeline, cache, preview, zoom)
    if (timeline.isEmpty) Hint(Res.string.video_editor_empty.str())

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickButton(Res.string.add_video.str(), videoExtensions, PickKind.VIDEO, multiple = true, icon = Icons.Filled.VideoLibrary) { add(it, ClipKind.VIDEO) }
        PickButton(Res.string.add_photo.str(), imageExtensions, PickKind.IMAGE, multiple = true, icon = Icons.Filled.AddPhotoAlternate) { add(it, ClipKind.IMAGE) }
        PickButton(Res.string.add_music.str(), audioExtensions, multiple = true, icon = Icons.Filled.LibraryMusic) { add(it, ClipKind.AUDIO) }
        AddTrackButton(timeline)
    }
    addError?.let { ErrorText(it) }

    val selected = timeline.selected
    if (selected != null) {
        val clip = draft?.takeIf { it.first == selected.id }?.second ?: selected.clip
        val playheadInside = preview.positionMs > clip.atMs + Timeline.MIN_CLIP_MS && preview.positionMs < clip.endAtMs - Timeline.MIN_CLIP_MS
        EditButtons {
            EditButton(Res.string.split_at_playhead.str(), Icons.Filled.ContentCut, playheadInside) { timeline.split(selected.id, preview.positionMs) }
            EditButton(Res.string.duplicate.str(), Icons.Filled.ContentCopy, true) { timeline.duplicate(selected.id) }
            EditButton(Res.string.remove.str(), Icons.Filled.Delete, true) { timeline.delete(selected.id) }
            if (clip.kind == ClipKind.AUDIO) EditButton(Res.string.repeat_music.str(), Icons.Filled.Repeat, true) { timeline.loopToEnd(selected.id) }
        }
        val track = timeline.trackOf(selected.id)
        val overlay = track != null && track.kind == TrackKind.VIDEO && timeline.tracks.indexOf(track) > 0
        ClipInspector(
            clip = clip,
            overlay = overlay,
            onChange = { draft = selected.id to it },
            onDone = {
                draft?.let { (id, changed) -> timeline.update(id) { changed } }
                draft = null
            },
        )
    }

    ToolSection(Res.string.output_file.str()) {
        ChoiceChips(options = formats, selected = target, onSelect = { format = it }, label = { it.extension.uppercase() })
        ChoiceChips(options = shortSides, selected = shortSide, onSelect = { shortSide = it }, label = { if (it == 0) Res.string.custom_size.str() else sideLabel(it) })
        if (shortSide == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(customWidth, { customWidth = it }, Res.string.width_px.str(), modifier = Modifier.weight(1f))
                NumberField(customHeight, { customHeight = it }, Res.string.height_px.str(), modifier = Modifier.weight(1f))
            }
        } else {
            ChoiceChips(options = Aspect.entries, selected = aspect, onSelect = { aspect = it }, label = { if (it == Aspect.SOURCE) Res.string.like_first_clip.str() else it.label })
        }
        ChoiceChips(options = listOf(24, 25, 30, 50, 60), selected = fps, onSelect = { fps = it }, label = { "$it ${Res.string.unit_frames_per_second.str()}" })
        SegmentedChoice(options = VideoQuality.entries, selected = quality, onSelect = { quality = it }, label = { qualityLabel(it) })
        Text("$width × $height · ${formatClock(totalMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ActionButton(
        text = Res.string.render_video.str(),
        icon = Icons.Filled.Movie,
        enabled = !task.running && totalMs > 0,
        onClick = {
            result?.discard()
            result = null
            preview.playing = false
            val output = timeline.project(spec)
            task.launch(scope) { progress -> result = MediaEngine.export(output, progress) }
        },
    )
    TaskProgress(task, Res.string.rendering.str())
    result?.let { output ->
        ResultCard(title = Res.string.result.str()) {
            ResultPreview(output, "video.${target.extension}")
            Text(formatBytes(output.size, binary = false), style = MaterialTheme.typography.bodyLarge)
            SaveButton { output.save("video.${target.extension}") }
        }
    }
}

@Composable
private fun AddTrackButton(timeline: Timeline) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(Res.string.add_track.str())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(Res.string.video_track.str()) },
                onClick = {
                    timeline.addTrack(TrackKind.VIDEO)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(Res.string.audio_track.str()) },
                onClick = {
                    timeline.addTrack(TrackKind.AUDIO)
                    open = false
                },
            )
        }
    }
}

@Composable
private fun ClipInspector(clip: MediaClip, overlay: Boolean, onChange: (MediaClip) -> Unit, onDone: () -> Unit) {
    ResultCard(title = clip.file.name) {
        Text(
            "${formatClock(clip.atMs)} – ${formatClock(clip.endAtMs)} · ${formatClock(clip.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (clip.kind == ClipKind.IMAGE) {
            LabeledSlider(Res.string.photo_duration.str(), formatClock(clip.durationMs), clip.durationMs / 1000f, 0.5f..30f, onDone) {
                onChange(clip.copy(endMs = (it * 1000).toLong()))
            }
        } else {
            val loudest = if (currentPlatform == PlatformKind.IOS) 1f else 2f
            LabeledSlider(Res.string.clip_volume.str(), "${(clip.volume * 100).roundToInt()} %", clip.volume, 0f..loudest, onDone) { onChange(clip.copy(volume = it)) }
        }
        val fadeLimit = (clip.durationMs / 2000f).coerceIn(0.1f, 5f)
        val (fadeIn, fadeOut) = if (clip.kind == ClipKind.AUDIO) Res.string.fade_in to Res.string.fade_out else Res.string.picture_fade_in to Res.string.picture_fade_out
        LabeledSlider(fadeIn.str(), formatClock(clip.fadeInMs), clip.fadeInMs / 1000f, 0f..fadeLimit, onDone) { onChange(clip.copy(fadeInMs = (it * 1000).toLong())) }
        LabeledSlider(fadeOut.str(), formatClock(clip.fadeOutMs), clip.fadeOutMs / 1000f, 0f..fadeLimit, onDone) { onChange(clip.copy(fadeOutMs = (it * 1000).toLong())) }
        if (clip.kind == ClipKind.AUDIO) return@ResultCard
        SwitchRow(Res.string.fill_frame.str(), clip.fill, { onChange(clip.copy(fill = it)); onDone() }, description = Res.string.fill_frame_hint.str())
        if (!overlay) return@ResultCard
        LabeledSlider(Res.string.picture_size.str(), "${(clip.box.scale * 100).roundToInt()} %", clip.box.scale, 0.1f..1f, onDone) { scale ->
            val corner = Corner.entries.minBy { c -> c.box(clip.box.scale).let { (it.x - clip.box.x) * (it.x - clip.box.x) + (it.y - clip.box.y) * (it.y - clip.box.y) } }
            onChange(clip.copy(box = corner.box(scale)))
        }
        ChoiceChips(
            options = Corner.entries,
            selected = Corner.entries.firstOrNull { it.box(clip.box.scale) == clip.box },
            onSelect = {
                onChange(clip.copy(box = it.box(clip.box.scale)))
                onDone()
            },
            label = {
                when (it) {
                    Corner.CENTER -> Res.string.position_center.str()
                    Corner.TOP_LEFT -> Res.string.position_top_left.str()
                    Corner.TOP_RIGHT -> Res.string.position_top_right.str()
                    Corner.BOTTOM_LEFT -> Res.string.position_bottom_left.str()
                    Corner.BOTTOM_RIGHT -> Res.string.position_bottom_right.str()
                }
            },
        )
        LabeledSlider(Res.string.opacity.str(), "${(clip.opacity * 100).roundToInt()} %", clip.opacity, 0.1f..1f, onDone) { onChange(clip.copy(opacity = it)) }
    }
}

@Composable
internal fun ResultPreview(output: MediaResult, name: String) {
    val file = remember(output) { output.asFile(name) }
    var info by remember(output) { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(output) { info = runCatching { MediaEngine.probe(file) }.getOrNull() }
    info?.let { FilePreview(file, it) }
}
