package com.vasmarfas.card.tools.documents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.editor.ImageQuality
import com.vasmarfas.card.tools.documents.editor.compressPdf
import com.vasmarfas.card.tools.documents.editor.hasSignature
import com.vasmarfas.card.tools.documents.editor.qualityLabel
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfEncryptedException
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.tools.media.SizeChange
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

val compressPdfTool = Tool(
    id = "compress-pdf",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.compress_pdf,
    description = Res.string.compress_pdf_description,
    icon = Icons.Filled.UnfoldLess,
    keywords = listOf(
        "compress pdf", "reduce pdf size", "shrink pdf", "smaller pdf", "optimize pdf", "pdf too large", "pdf for email",
        "сжать pdf", "уменьшить pdf", "уменьшить размер pdf", "pdf меньше", "оптимизировать pdf", "pdf слишком большой", "pdf для почты",
    ),
) { CompressPdfScreen() }

private class CompressedPdf(val name: String, val bytes: ByteArray, val before: Long, val signed: Boolean)

@Composable
private fun CompressPdfScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var quality by remember { mutableStateOf(ImageQuality.MEDIUM) }
    var result by remember { mutableStateOf<CompressedPdf?>(null) }
    val task = remember { TaskState() }

    PickButton(Res.string.choose_pdf.str(), pdfExtensions, icon = Icons.Filled.PictureAsPdf, empty = file == null) {
        file = it.first()
        result = null
    }
    val source = file ?: return
    Text(source.name, style = MaterialTheme.typography.bodyLarge)
    ToolSection(Res.string.image_quality.str()) {
        ChoiceChips(options = ImageQuality.entries, selected = quality, onSelect = { quality = it; result = null }, label = { qualityLabel(it) })
    }
    ActionButton(
        text = Res.string.compress.str(),
        icon = Icons.Filled.Compress,
        enabled = !task.running,
        onClick = {
            result = null
            val level = quality
            task.launch(scope) { progress ->
                val bytes = source.readBytes()
                val document = try {
                    withContext(Dispatchers.Default) { PdfDocument.parse(bytes) }
                } catch (e: PdfEncryptedException) {
                    throw IllegalStateException(getString(Res.string.pdf_protected))
                } catch (e: Exception) {
                    throw IllegalStateException(getString(Res.string.not_a_pdf))
                }
                val out = compressPdf(document, level, progress)
                result = CompressedPdf(renamed(source.name, "pdf", "-compressed"), out, bytes.size.toLong(), hasSignature(document))
            }
        },
    )
    TaskProgress(task, Res.string.compressing.str())
    val done = result ?: return
    ResultCard(title = Res.string.result.str()) {
        if (done.bytes.size < done.before) {
            SizeChange(done.before, done.bytes.size.toLong())
            if (done.signed) Text(Res.string.pdf_edit_signed_warning.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            SaveButton { saveBytes(done.bytes, done.name) }
        } else {
            Text(Res.string.compress_pdf_no_gain.str(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
