package com.vasmarfas.card.tools.documents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.PdfRaster
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.ImageOutput
import com.vasmarfas.card.tools.media.ImageOutputs
import com.vasmarfas.card.tools.media.ImageTarget
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.tools.media.encodeImage
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

val pdfToImagesTool = Tool(
    id = "pdf-to-images",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.pdf_to_images,
    description = Res.string.pdf_to_images_description,
    icon = Icons.Filled.Collections,
    keywords = listOf(
        "pdf to jpg", "pdf to png", "pdf to image", "render pdf", "pdf page as picture",
        "pdf в jpg", "pdf в картинки", "pdf в png", "страницу pdf в картинку", "сохранить страницу pdf как фото",
    ),
) { PdfToImagesScreen() }

private const val MAX_PIXELS = 25_000_000

@Composable
private fun PdfToImagesScreen() {
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedPdf?>(null) }
    var range by remember { mutableStateOf("") }
    var dpi by rememberSaveable { mutableStateOf(150) }
    var target by rememberSaveable { mutableStateOf(ImageTarget.JPEG) }
    var outputs by remember { mutableStateOf<List<ImageOutput>>(emptyList()) }
    val task = remember { TaskState() }

    PickButton(Res.string.choose_pdf.str(), pdfExtensions, icon = Icons.Filled.PictureAsPdf, empty = opened == null) { files ->
        outputs = emptyList()
        range = ""
        scope.launch { opened = openPdf(files.first()) }
    }
    val pdf = opened ?: return
    val document = pdf.document
    if (document == null) {
        ErrorText(pdf.detail)
        return
    }
    Text("${pdf.file.name} · ${pdf.detail}", style = MaterialTheme.typography.bodyLarge)
    val pages = if (range.isBlank()) (0 until document.pageCount).toList() else parsePages(range, document.pageCount)
    ToolInputField(value = range, onValueChange = { range = it }, label = Res.string.page_numbers_hint.str(), isError = pages == null)
    ToolSection(Res.string.video_resolution.str()) {
        ChoiceChips(options = listOf(72, 100, 150, 200, 300), selected = dpi, onSelect = { dpi = it }, label = { "$it dpi" })
    }
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = listOf(ImageTarget.JPEG, ImageTarget.PNG, ImageTarget.WEBP), selected = target, onSelect = { target = it }, label = { it.title })
    }
    ActionButton(
        text = Res.string.convert.str(),
        icon = Icons.Filled.Collections,
        enabled = !task.running && !pages.isNullOrEmpty(),
        onClick = {
            val chosen = pages.orEmpty()
            val format = target
            val resolution = dpi
            outputs = emptyList()
            task.launch(scope) { progress ->
                val bytes = pdf.file.readBytes()
                val raster = PdfRaster.open(bytes)
                try {
                    val done = ArrayList<ImageOutput>()
                    chosen.forEachIndexed { n, index ->
                        val page = document.page(index)
                        val scale = minOf(resolution / 72.0, sqrt(MAX_PIXELS / (page.width * page.height)))
                        val width = (page.width * scale).roundToInt().coerceIn(16, 8000)
                        val image = raster.render(index, width)
                        val encoded = withContext(Dispatchers.Default) { encodeImage(image, format, 90) }
                        done += ImageOutput(renamed(pdf.file.name, format.extension, "-${index + 1}"), encoded, 0, image.width, image.height)
                        progress((n + 1f) / chosen.size)
                    }
                    outputs = done
                } finally {
                    raster.close()
                }
            }
        },
    )
    TaskProgress(task, Res.string.converting.str())
    ImageOutputs(outputs, renamed(pdf.file.name, "zip", "-images"))
}
