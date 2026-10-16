package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.Ocr
import com.vasmarfas.card.core.OcrLanguage
import com.vasmarfas.card.core.OcrPage
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.PreviewState
import com.vasmarfas.card.core.cropped
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.pixels
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.OcrLanguagePicker
import com.vasmarfas.card.tools.documents.OcrPreview
import com.vasmarfas.card.tools.documents.OcrResult
import com.vasmarfas.card.tools.documents.OcrText
import com.vasmarfas.card.tools.documents.languagesOf
import com.vasmarfas.card.tools.documents.namesOf
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.rememberCopy
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

val videoToTextTool = Tool(
    id = "video-to-text",
    category = ToolCategory.MEDIA,
    title = Res.string.video_to_text,
    description = Res.string.video_to_text_description,
    icon = Icons.Filled.Subtitles,
    keywords = listOf(
        "ocr", "subtitles", "hardcoded subtitles", "burned-in subtitles", "srt", "captions", "text from video", "video to text",
        "текст из видео", "текст с видео", "распознать текст на видео", "вшитые субтитры", "субтитры из видео", "надписи на видео",
    ),
) { VideoToTextScreen() }

private enum class TextArea(val top: Float, val height: Float, val title: StringResource) {
    BOTTOM(0.62f, 0.38f, Res.string.video_text_area_bottom),
    FULL(0f, 1f, Res.string.video_text_area_full),
    TOP(0f, 0.38f, Res.string.video_text_area_top),
}

private val steps = listOf(500L, 1000L, 2000L, 5000L)

private const val FRAME_SIDE = 1920

private const val MIN_LINE_CONFIDENCE = 45f

private const val SHOWN_SPANS = 300

private const val CLEAR_READING = 80f

private fun crop(image: ImageBitmap, area: TextArea): ImageBitmap =
    image.cropped(0, (image.height * area.top).roundToInt(), image.width, (image.height * area.height).roundToInt())

private suspend fun readFrame(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage {
    val bright = Ocr.recognize(imageBitmapOf(VideoText.brightText(image.pixels()), image.width, image.height), languages)
    if (bright.lines.isNotEmpty() && bright.confidence >= CLEAR_READING) return bright
    val plain = Ocr.recognize(image, languages)
    return if (plain.lines.isNotEmpty() && plain.confidence > bright.confidence) plain else bright
}

private fun captionOf(page: OcrPage): String =
    OcrText.text(OcrPage(page.lines.filter { it.confidence >= MIN_LINE_CONFIDENCE }, page.width, page.height), joinLines = false)

@Composable
private fun VideoToTextScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var languageNames by rememberSaveable { mutableStateOf(namesOf(OcrLanguage.entries.toSet())) }
    var area by rememberSaveable { mutableStateOf(TextArea.BOTTOM) }
    var step by rememberSaveable { mutableStateOf(1000L) }
    var withTimes by rememberSaveable { mutableStateOf(true) }
    var frameImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var framePage by remember { mutableStateOf<OcrPage?>(null) }
    var frameAt by remember { mutableStateOf(0L) }
    val samples = remember { mutableStateListOf<TextSample>() }
    val frameTask = remember { TaskState() }
    val videoTask = remember { TaskState() }
    val languages = languagesOf(languageNames)

    PickButton(Res.string.choose_video.str(), videoExtensions, PickKind.VIDEO, icon = Icons.Filled.VideoLibrary, empty = file == null) { files ->
        val picked = files.first()
        frameTask.cancel()
        videoTask.cancel()
        file = picked
        info = null
        loadError = null
        frameImage = null
        framePage = null
        samples.clear()
        scope.launch {
            runCatching { MediaEngine.probe(picked) }
                .onSuccess { info = it }
                .onFailure { loadError = errorText(it) }
        }
    }
    loadError?.let { ErrorText(it) }
    OcrLanguagePicker(languages) { languageNames = namesOf(it) }
    Hint(Res.string.ocr_on_device.str())
    val source = file ?: return
    val probed = info
    if (probed == null) {
        if (loadError == null) LoadingRow()
        return
    }
    val duration = probed.durationMs.coerceAtLeast(1)
    val player = remember(source) { PreviewState() }
    FilePreview(source, probed, player)

    Text(Res.string.video_text_area.str(), style = MaterialTheme.typography.titleSmall)
    ChoiceChips(options = TextArea.entries, selected = area, onSelect = { area = it }, label = { it.title.str() })

    val frameFailed = Res.string.video_text_no_frame.str()
    ActionButton(Res.string.video_text_this_frame.str(), icon = Icons.Filled.Screenshot, enabled = !frameTask.running, onClick = {
        frameTask.launch(scope) {
            val at = player.positionMs
            val image = crop(MediaEngine.frame(source, at, FRAME_SIDE) ?: error(frameFailed), area)
            framePage = null
            frameImage = image
            frameAt = at
            framePage = readFrame(image, languages)
        }
    })
    TaskProgress(frameTask, Res.string.ocr_progress.str())
    frameImage?.let { OcrPreview(it, framePage) }
    framePage?.let { page ->
        OcrResult(OcrText.text(page, joinLines = false), page.confidence, renamed(source.name, "txt", "-" + formatClock(frameAt).replace(':', '.')))
    }

    Text(Res.string.video_text_step.str(), style = MaterialTheme.typography.titleSmall)
    ChoiceChips(
        options = steps,
        selected = step,
        onSelect = { step = it },
        label = { stringResource(Res.string.video_text_step_value, (it / 1000.0).fmt(1)) },
    )
    val frames = (duration / step + 1).toInt()
    ActionButton(pluralStringResource(Res.plurals.video_text_read_frames, frames, frames), icon = Icons.Filled.Subtitles, enabled = !videoTask.running, onClick = {
        val every = step
        val where = area
        samples.clear()
        videoTask.launch(scope) { onProgress ->
            var index = 0
            MediaEngine.frames(source, 0, duration, 1000.0 / every, FRAME_SIDE) { image ->
                val at = index * every
                index++
                samples += TextSample(at, captionOf(readFrame(crop(image, where), languages)))
                onProgress((at.toFloat() / duration).coerceIn(0f, 1f))
            }
        }
    })
    TaskProgress(videoTask, Res.string.ocr_progress.str())
    if (samples.isEmpty()) return
    val spans = remember(samples.size, step) { VideoText.spans(samples.toList(), step) }
    ResultCard(pluralStringResource(Res.plurals.video_text_captions, spans.size, spans.size)) {
        if (spans.isEmpty()) {
            Hint(Res.string.ocr_nothing.str())
            return@ResultCard
        }
        SwitchRow(Res.string.video_text_with_times.str(), withTimes, { withTimes = it })
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spans.take(SHOWN_SPANS).forEach { span ->
                    Row {
                        Text(formatClock(span.startMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                        Text(span.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (spans.size > SHOWN_SPANS) Hint(stringResource(Res.string.video_text_more, spans.size - SHOWN_SPANS))
        val text = VideoText.plain(spans, withTimes)
        val copy = rememberCopy()
        ActionButton(Res.string.copy.str(), onClick = { copy(text) })
        SaveButton(Res.string.save_txt.str()) { saveBytes(text.encodeToByteArray(), renamed(source.name, "txt")) }
        SaveButton(Res.string.video_text_save_srt.str()) { saveBytes(VideoText.srt(spans).encodeToByteArray(), renamed(source.name, "srt")) }
    }
}
