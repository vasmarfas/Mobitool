package com.vasmarfas.card.tools.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaResult
import com.vasmarfas.card.core.PcmPlayer
import com.vasmarfas.card.core.discard
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.save
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.expandedHeight
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private const val MAX_SOURCE_MS = 15 * 60_000L

val audioEditorTool = Tool(
    id = "audio-editor",
    category = ToolCategory.MEDIA,
    title = Res.string.audio_editor,
    description = Res.string.audio_editor_description,
    icon = Icons.Filled.GraphicEq,
    keywords = listOf(
        "audio editor", "trim", "cut", "ringtone", "merge", "join", "fade", "normalize", "reverse", "silence", "waveform",
        "аудиоредактор", "обрезать песню", "рингтон", "склеить аудио", "вырезать", "затухание", "громкость", "нормализация",
    ),
    expandable = true,
) { AudioEditorScreen() }

@Stable
private class AudioEditor {
    var project by mutableStateOf<AudioProject?>(null)
    var timeline by mutableStateOf<List<AudioSegment>>(emptyList())
    var name by mutableStateOf("audio")
    var selStart by mutableStateOf(0)
    var selEnd by mutableStateOf(0)
    var viewFrom by mutableStateOf(0)
    var viewTo by mutableStateOf(0)
    var clipboard by mutableStateOf<List<AudioSegment>>(emptyList())
    var undoDepth by mutableStateOf(0)
    var redoDepth by mutableStateOf(0)
    private val undo = ArrayDeque<List<AudioSegment>>()
    private val redo = ArrayDeque<List<AudioSegment>>()

    val length: Int get() = AudioEdit.length(timeline)
    val hasSelection: Boolean get() = selEnd > selStart

    fun reset(project: AudioProject, timeline: List<AudioSegment>, name: String) {
        this.project = project
        this.timeline = timeline
        this.name = name
        undo.clear()
        redo.clear()
        undoDepth = 0
        redoDepth = 0
        selStart = 0
        selEnd = 0
        showAll()
    }

    fun apply(next: List<AudioSegment>, selection: IntRange? = null) {
        undo.addLast(timeline)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        timeline = next
        undoDepth = undo.size
        redoDepth = 0
        if (selection != null) {
            selStart = selection.first
            selEnd = selection.last + 1
        }
        clamp()
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(timeline)
        timeline = previous
        undoDepth = undo.size
        redoDepth = redo.size
        clamp()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(timeline)
        timeline = next
        undoDepth = undo.size
        redoDepth = redo.size
        clamp()
    }

    fun showAll() {
        viewFrom = 0
        viewTo = max(1, length)
    }

    fun zoom(factor: Double) {
        val total = max(1, length)
        val center = if (hasSelection) (selStart + selEnd) / 2 else if (selStart > 0) selStart else (viewFrom + viewTo) / 2
        val span = ((viewTo - viewFrom) * factor).toInt().coerceIn(min(total, 400), total)
        viewFrom = (center - span / 2).coerceIn(0, total - span)
        viewTo = viewFrom + span
    }

    private fun clamp() {
        val total = length
        selStart = selStart.coerceIn(0, total)
        selEnd = selEnd.coerceIn(selStart, total)
        if (viewTo > total || viewTo <= viewFrom) showAll()
    }
}

@Composable
private fun AudioEditorScreen() {
    val scope = rememberCoroutineScope()
    val editor = remember { AudioEditor() }
    val player = remember { PcmPlayer() }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var playhead by remember { mutableStateOf(-1) }
    var format by rememberSaveable { mutableStateOf(MediaFormat.MP3) }
    var kbps by rememberSaveable { mutableStateOf(192) }
    var result by remember { mutableStateOf<MediaResult?>(null) }
    val task = remember { TaskState() }
    val playbackFailed = Res.string.playback_failed.str()
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            result?.discard()
        }
    }

    fun load(file: PlatformFile, append: Boolean) {
        loading = true
        loadError = null
        scope.launch {
            runCatching { MediaEngine.decodeAudio(file, MAX_SOURCE_MS) }
                .onSuccess { pcm ->
                    val current = editor.project
                    if (append && current != null) {
                        val index = current.add(pcm)
                        editor.apply(editor.timeline + AudioSegment(index, 0, current.frames(index)))
                        editor.showAll()
                    } else {
                        val project = AudioProject(pcm.sampleRate, pcm.channels)
                        val index = project.add(pcm)
                        editor.reset(project, listOf(AudioSegment(index, 0, project.frames(index))), file.name)
                    }
                }
                .onFailure { loadError = errorText(it) }
            loading = false
        }
    }

    if (editor.project == null) {
        PickButton(Res.string.open_audio.str(), audioExtensions + videoExtensions, empty = !loading) { load(it.first(), append = false) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickButton(Res.string.open_audio.str(), audioExtensions + videoExtensions) { load(it.first(), append = false) }
            PickButton(Res.string.append_file.str(), audioExtensions + videoExtensions, icon = Icons.Filled.Add) { load(it.first(), append = true) }
        }
    }
    if (loading) LoadingRow(Res.string.decoding_audio.str())
    loadError?.let { ErrorText(it) }
    val project = editor.project ?: return

    fun framesToMs(frames: Int): Long = frames * 1000L / project.sampleRate

    Waveform(editor, project, playhead)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { editor.zoom(0.5) }) { Icon(Icons.Filled.ZoomIn, contentDescription = Res.string.zoom_in.str()) }
        IconButton(onClick = { editor.zoom(2.0) }) { Icon(Icons.Filled.ZoomOut, contentDescription = Res.string.zoom_out.str()) }
        IconButton(onClick = { editor.showAll() }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = Res.string.whole_track.str()) }
        Spacer(Modifier.weight(1f))
        Text(formatClock(framesToMs(editor.length)), style = MaterialTheme.typography.labelLarge)
    }
    val span = editor.viewTo - editor.viewFrom
    if (span < editor.length) {
        Slider(
            value = editor.viewFrom.toFloat(),
            onValueChange = {
                editor.viewFrom = it.toInt()
                editor.viewTo = editor.viewFrom + span
            },
            valueRange = 0f..(editor.length - span).toFloat(),
        )
    }
    Text(
        if (editor.hasSelection) {
            "${Res.string.selection.str()}: ${formatClock(framesToMs(editor.selStart))} – ${formatClock(framesToMs(editor.selEnd))} " +
                "(${formatClock(framesToMs(editor.selEnd - editor.selStart))})"
        } else {
            "${Res.string.cursor.str()}: ${formatClock(framesToMs(editor.selStart))}"
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    val playing = playhead >= 0
    ActionButton(
        text = if (playing) Res.string.stop.str() else Res.string.play.str(),
        icon = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
        onClick = {
            if (playing) {
                player.stop()
            } else {
                val from = editor.selStart
                val to = if (editor.hasSelection) editor.selEnd else editor.length
                val source = AudioEdit.source(editor.timeline, project, from, to)
                player.start(project.sampleRate, project.channels, source.read)
                if (player.playing) {
                    scope.launch {
                        playhead = from
                        delay(50)
                        while (player.playing) {
                            playhead = (from + player.position).toInt().coerceAtMost(to)
                            delay(33)
                        }
                        playhead = -1
                    }
                } else {
                    loadError = playbackFailed
                }
            }
        },
    )

    val selected = editor.hasSelection
    fun range() = editor.selStart until editor.selEnd
    ToolSection(Res.string.edit_selection.str()) {
        EditButtons {
            EditButton(Res.string.delete.str(), Icons.Filled.Delete, selected) {
                editor.apply(AudioEdit.delete(editor.timeline, editor.selStart, editor.selEnd), editor.selStart until editor.selStart)
            }
            EditButton(Res.string.keep_only_selection.str(), Icons.Filled.Crop, selected) {
                editor.apply(AudioEdit.keep(editor.timeline, editor.selStart, editor.selEnd), 0 until 0)
                editor.showAll()
            }
            EditButton(Res.string.cut_piece.str(), Icons.Filled.ContentCut, selected) {
                editor.clipboard = AudioEdit.slice(editor.timeline, editor.selStart, editor.selEnd)
                editor.apply(AudioEdit.delete(editor.timeline, editor.selStart, editor.selEnd), editor.selStart until editor.selStart)
            }
            EditButton(Res.string.copy.str(), Icons.Filled.ContentCopy, selected) {
                editor.clipboard = AudioEdit.slice(editor.timeline, editor.selStart, editor.selEnd)
            }
            EditButton(Res.string.paste.str(), Icons.Filled.ContentPaste, editor.clipboard.isNotEmpty()) {
                val clip = editor.clipboard
                val at = editor.selStart
                editor.apply(AudioEdit.insert(editor.timeline, at, clip), at until at + AudioEdit.length(clip))
            }
            EditButton(Res.string.silence.str(), Icons.AutoMirrored.Filled.VolumeOff, selected) {
                editor.apply(AudioEdit.silence(editor.timeline, editor.selStart, editor.selEnd), range())
            }
            EditButton(Res.string.fade_in.str(), Icons.AutoMirrored.Filled.TrendingUp, selected) {
                editor.apply(AudioEdit.fade(editor.timeline, editor.selStart, editor.selEnd, rising = true), range())
            }
            EditButton(Res.string.fade_out.str(), Icons.AutoMirrored.Filled.TrendingDown, selected) {
                editor.apply(AudioEdit.fade(editor.timeline, editor.selStart, editor.selEnd, rising = false), range())
            }
            EditButton(Res.string.louder_3db.str(), Icons.AutoMirrored.Filled.VolumeUp, selected) {
                editor.apply(AudioEdit.gain(editor.timeline, editor.selStart, editor.selEnd, 1.4125f), range())
            }
            EditButton(Res.string.quieter_3db.str(), Icons.AutoMirrored.Filled.VolumeDown, selected) {
                editor.apply(AudioEdit.gain(editor.timeline, editor.selStart, editor.selEnd, 0.7079f), range())
            }
            EditButton(Res.string.reverse_audio.str(), Icons.Filled.SwapHoriz, selected) {
                editor.apply(AudioEdit.reverse(editor.timeline, editor.selStart, editor.selEnd), range())
            }
        }
    }
    ToolSection(Res.string.whole_track.str()) {
        EditButtons {
            EditButton(Res.string.normalize.str(), Icons.Filled.GraphicEq, editor.length > 0) {
                editor.apply(AudioEdit.normalize(editor.timeline, project))
            }
            EditButton(Res.string.undo.str(), Icons.AutoMirrored.Filled.Undo, editor.undoDepth > 0) { editor.undo() }
            EditButton(Res.string.redo.str(), Icons.AutoMirrored.Filled.Redo, editor.redoDepth > 0) { editor.redo() }
        }
    }

    val formats = MediaFormat.entries.filter { !it.video && it in MediaEngine.formats }
    val target = if (format in formats) format else formats.first()
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = formats, selected = target, onSelect = { format = it }, label = { it.extension.uppercase() })
    }
    if (target.lossy) {
        ToolSection(Res.string.bitrate.str()) {
            ChoiceChips(options = listOf(96, 128, 192, 256, 320), selected = kbps, onSelect = { kbps = it }, label = { "$it ${Res.string.unit_kbit_s.str()}" })
        }
    }
    ActionButton(
        text = Res.string.export_audio.str(),
        icon = Icons.Filled.GraphicEq,
        enabled = !task.running && editor.length > 0,
        onClick = {
            result?.discard()
            result = null
            val timeline = editor.timeline
            task.launch(scope) { progress ->
                result = MediaEngine.encodeAudio(AudioEdit.source(timeline, project), target, kbps, progress)
            }
        },
    )
    TaskProgress(task, Res.string.exporting.str())
    result?.let { output ->
        ResultCard(title = Res.string.result.str()) {
            Text(formatClock(framesToMs(editor.length)) + " · " + formatBytes(output.size, binary = false))
            SaveButton { output.save(renamed(editor.name, target.extension, "-edit")) }
        }
    }
}

@Composable
private fun Waveform(editor: AudioEditor, project: AudioProject, playhead: Int) {
    val colors = MaterialTheme.colorScheme
    val height = expandedHeight(160.dp, 420.dp)
    var widthPx by remember { mutableStateOf(0) }
    val columns = (widthPx / 3).coerceAtLeast(1)
    val levels = remember(editor.timeline, editor.viewFrom, editor.viewTo, columns) {
        AudioEdit.waveform(editor.timeline, project, editor.viewFrom, editor.viewTo, columns)
    }
    LaunchedEffect(editor.timeline) { if (editor.viewTo <= editor.viewFrom) editor.showAll() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(editor.viewFrom, editor.viewTo) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun frameAt(x: Float) = (editor.viewFrom + (x / size.width).coerceIn(0f, 1f) * (editor.viewTo - editor.viewFrom)).toInt()
                    val anchor = frameAt(down.position.x)
                    editor.selStart = anchor
                    editor.selEnd = anchor
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val at = frameAt(change.position.x)
                        editor.selStart = min(anchor, at)
                        editor.selEnd = max(anchor, at)
                        change.consume()
                    } while (change.pressed)
                }
            },
    ) {
        val mid = size.height / 2
        val span = (editor.viewTo - editor.viewFrom).coerceAtLeast(1).toFloat()
        fun x(frame: Int) = (frame - editor.viewFrom) / span * size.width
        if (editor.hasSelection) {
            val left = x(editor.selStart).coerceIn(0f, size.width)
            val right = x(editor.selEnd).coerceIn(0f, size.width)
            drawRect(colors.primary.copy(alpha = 0.18f), topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        }
        val step = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = max(1f, level * mid)
            val cx = i * step + step / 2
            drawLine(colors.primary, Offset(cx, mid - h), Offset(cx, mid + h), strokeWidth = max(1f, step * 0.7f))
        }
        val cursor = if (playhead >= 0) playhead else if (!editor.hasSelection) editor.selStart else -1
        if (cursor >= 0) {
            val cx = x(cursor)
            if (cx in 0f..size.width) drawLine(colors.tertiary, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
