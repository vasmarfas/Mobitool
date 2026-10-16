package com.vasmarfas.card.tools.documents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Ocr
import com.vasmarfas.card.core.OcrLanguage
import com.vasmarfas.card.core.OcrPage
import com.vasmarfas.card.core.PdfRaster
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.baseName
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.photoCapture
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.takePhoto
import com.vasmarfas.card.core.transformed
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.tools.media.decodeImage
import com.vasmarfas.card.tools.media.imageExtensions
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
import io.github.vinceglb.filekit.readBytes
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val photoToTextTool = Tool(
    id = "photo-to-text",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.photo_to_text,
    description = Res.string.photo_to_text_description,
    icon = Icons.Filled.ImageSearch,
    keywords = listOf(
        "ocr", "text recognition", "image to text", "photo to text", "picture to text", "screenshot to text", "scan to text",
        "extract text", "tesseract", "распознать текст", "распознавание текста", "текст с фото", "текст с картинки",
        "текст со скриншота", "фото в текст", "скан в текст", "оцифровать",
    ),
) { PhotoToTextScreen() }

private const val DECODE_SIDE = 4000
private const val PDF_WIDTH = 2200
private const val PREVIEW_SIDE = 1400
private const val LOW_CONFIDENCE = 60f

// the full picture is loaded again for each run, only a preview stays in memory: a scanned PDF of fifty
// pages would not fit otherwise
private data class OcrItem(
    val name: String,
    val load: suspend () -> ImageBitmap,
    val turns: Int = 0,
    val preview: ImageBitmap? = null,
    val page: OcrPage? = null,
)

internal fun languagesOf(names: String): Set<OcrLanguage> =
    names.split(',').mapNotNull { name -> OcrLanguage.entries.firstOrNull { it.name == name } }.toSet().ifEmpty { OcrLanguage.entries.toSet() }

internal fun namesOf(languages: Set<OcrLanguage>): String = languages.joinToString(",") { it.name }

@Composable
internal fun OcrLanguagePicker(selected: Set<OcrLanguage>, onChange: (Set<OcrLanguage>) -> Unit) {
    Text(Res.string.ocr_languages.str(), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OcrLanguage.entries.forEach { language ->
            FilterChip(
                selected = language in selected,
                onClick = {
                    val next = if (language in selected) selected - language else selected + language
                    if (next.isNotEmpty()) onChange(next)
                },
                label = { Text(language.title.str()) },
            )
        }
    }
}

@Composable
internal fun OcrPreview(image: ImageBitmap, page: OcrPage?) {
    val highlight = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(max = 420.dp).aspectRatio(image.width.toFloat() / image.height).clip(RoundedCornerShape(12.dp))) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
            if (page != null) {
                Canvas(Modifier.matchParentSize()) {
                    val sx = size.width / page.width
                    val sy = size.height / page.height
                    val stroke = Stroke(1.dp.toPx())
                    page.lines.forEach { line ->
                        val topLeft = Offset(line.left * sx, line.top * sy)
                        val box = Size((line.right - line.left) * sx, (line.bottom - line.top) * sy)
                        drawRect(highlight.copy(alpha = 0.16f), topLeft, box)
                        drawRect(highlight, topLeft, box, style = stroke)
                    }
                }
            }
        }
    }
}

@Composable
internal fun OcrResult(text: String, confidence: Float, fileName: String) {
    ResultCard(Res.string.text.str()) {
        if (text.isBlank()) {
            Hint(Res.string.ocr_nothing.str())
            return@ResultCard
        }
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        Hint(stringResource(Res.string.ocr_confidence, stringResource(Res.string.percent_value, confidence.roundToInt().toString())))
        if (confidence < LOW_CONFIDENCE) Hint(Res.string.ocr_low_confidence.str())
        val copy = rememberCopy()
        ActionButton(Res.string.copy.str(), onClick = { copy(text) })
        SaveButton(Res.string.save_txt.str()) { saveBytes(text.encodeToByteArray(), fileName) }
    }
}

@Composable
private fun PhotoToTextScreen() {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<OcrItem>() }
    val task = remember { TaskState() }
    var selected by remember { mutableStateOf(0) }
    var languageNames by rememberSaveable { mutableStateOf(namesOf(OcrLanguage.entries.toSet())) }
    var joinLines by rememberSaveable { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val languages = languagesOf(languageNames)
    val unreadable = Res.string.image_not_readable.str()
    val cameraFailed = Res.string.doc_scan_camera_failed.str()

    fun recognizePending() {
        task.launch(scope) { onProgress ->
            val pending = items.filter { it.page == null }
            pending.forEachIndexed { done, item ->
                onProgress(done.toFloat() / pending.size)
                val full = item.load().transformed(item.turns, mirror = false)
                val page = Ocr.recognize(full, languages)
                val index = items.indexOf(item)
                if (index >= 0) items[index] = item.copy(preview = full.limitedTo(PREVIEW_SIDE), page = page)
            }
        }
    }

    fun add(files: List<PlatformFile>) {
        loading = true
        loadError = null
        scope.launch {
            val failed = mutableListOf<String>()
            files.forEach { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null) {
                    failed += file.name
                } else if (file.name.endsWith(".pdf", ignoreCase = true)) {
                    val pages = runCatching { PdfRaster.open(bytes).let { raster -> raster.pageCount.also { raster.close() } } }.getOrNull()
                    if (pages == null) failed += file.name
                    else repeat(pages) { index ->
                        items += OcrItem("${file.baseName} · ${index + 1}", {
                            val raster = PdfRaster.open(bytes)
                            try {
                                raster.render(index, PDF_WIDTH)
                            } finally {
                                raster.close()
                            }
                        })
                    }
                } else if (decodeImage(bytes, 64) == null) {
                    failed += file.name
                } else {
                    items += OcrItem(file.name, { decodeImage(bytes, DECODE_SIDE) ?: error(unreadable) })
                }
            }
            if (failed.isNotEmpty()) loadError = failed.joinToString() + ": " + unreadable
            loading = false
            recognizePending()
        }
    }

    fun shoot() {
        scope.launch {
            runCatching { takePhoto() }
                .onSuccess { photo -> if (photo != null) add(listOf(photo)) }
                .onFailure { loadError = cameraFailed }
        }
    }

    LaunchedEffect(languageNames) {
        if (items.isEmpty()) return@LaunchedEffect
        for (i in items.indices) items[i] = items[i].copy(page = null)
        recognizePending()
    }

    OcrLanguagePicker(languages) { languageNames = namesOf(it) }
    val extensions = imageExtensions + "pdf"
    if (items.isEmpty()) {
        if (photoCapture) ActionButton(text = Res.string.doc_scan_shoot.str(), icon = Icons.Filled.PhotoCamera, onClick = ::shoot)
        PickButton(Res.string.ocr_choose.str(), extensions, PickKind.FILE, multiple = true, icon = Icons.Filled.AddPhotoAlternate, empty = true, onPicked = ::add)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photoCapture) ActionButton(text = Res.string.doc_scan_shoot.str(), icon = Icons.Filled.PhotoCamera, onClick = ::shoot)
            PickButton(Res.string.add_files.str(), extensions, PickKind.FILE, multiple = true, icon = Icons.Filled.AddPhotoAlternate, onPicked = ::add)
            TextButton(onClick = {
                task.cancel()
                items.clear()
                selected = 0
            }) { Text(Res.string.clear.str()) }
        }
    }
    if (loading) LoadingRow()
    loadError?.let { ErrorText(it) }
    Hint(Res.string.ocr_on_device.str())
    if (items.isEmpty()) return

    SwitchRow(Res.string.ocr_join_lines.str(), joinLines, { joinLines = it }, description = Res.string.ocr_join_lines_hint.str())
    TaskProgress(task, Res.string.ocr_progress.str())
    if (items.size > 1) {
        ChoiceChips(options = items.indices.toList(), selected = selected.coerceIn(items.indices), onSelect = { selected = it }, label = { (it + 1).toString() })
    }
    val current = items[selected.coerceIn(items.indices)]
    current.preview?.let { OcrPreview(it, current.page) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(current.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(
            enabled = current.page != null,
            onClick = {
                val index = items.indexOf(current)
                if (index >= 0) items[index] = current.copy(turns = (current.turns + 1) % 4, page = null)
                recognizePending()
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(Res.string.ocr_rotate.str(), modifier = Modifier.padding(start = 8.dp))
        }
    }
    val done = items.mapNotNull { it.page }
    if (done.isEmpty()) return
    val text = OcrText.pages(done.map { OcrText.text(it, joinLines) })
    val confidence = done.sumOf { it.confidence.toDouble() }.toFloat() / done.size
    OcrResult(text, confidence, renamed(items.first().name.substringBefore(" · "), "txt"))
}
