package com.vasmarfas.card.tools.documents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource

val documentConverterTool = Tool(
    id = "document-converter",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.document_converter,
    description = Res.string.document_converter_description,
    icon = Icons.Filled.Description,
    keywords = listOf(
        "docx to pdf", "word to pdf", "odt", "rtf", "epub", "fb2", "fb2 to epub", "epub to pdf", "html to pdf", "markdown to pdf", "md to docx", "txt to pdf",
        "docx в pdf", "ворд в pdf", "конвертер документов", "fb2 в epub", "epub в fb2", "книга в pdf", "текст в pdf", "html в docx",
    ),
) { DocumentConverterScreen() }

private val documentExtensions = setOf("docx", "odt", "rtf", "epub", "fb2", "zip", "html", "htm", "xhtml", "md", "markdown", "txt")

// null is PDF, the typesetter writes it rather than Documents
private val targets: List<DocFormat?> = listOf(null) + DocFormat.entries.filter { it.writable }

private val DocFormat.label: String get() = if (this == DocFormat.MARKDOWN) "Markdown" else name

private class OpenedDocument(val name: String, val format: DocFormat, val doc: Doc)

private class Converted(val name: String, val bytes: ByteArray, val pages: Int)

@Composable
private fun DocumentConverterScreen() {
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<DocFormat?>(null) }
    var page by rememberSaveable { mutableStateOf(PageFormat.A4) }
    var serif by rememberSaveable { mutableStateOf(true) }
    var size by rememberSaveable { mutableStateOf(11) }
    var numbers by rememberSaveable { mutableStateOf(true) }
    var result by remember { mutableStateOf<Converted?>(null) }
    val task = remember { TaskState() }

    PickButton(Res.string.choose_document.str(), documentExtensions, icon = Icons.Filled.FindInPage, empty = opened == null) { files ->
        val file = files.first()
        opened = null
        result = null
        loadError = null
        loading = true
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                val format = Documents.detect(bytes, file.name) ?: throw DocumentFormatException("Not a supported document: ${file.name}", Res.string.document_unsupported)
                OpenedDocument(file.name, format, withContext(Dispatchers.Default) { Documents.read(bytes, format) })
            }
                .onSuccess {
                    opened = it
                    if (target == it.format) target = null
                }
                .onFailure { loadError = getString((it as? DocumentFormatException)?.reason ?: Res.string.document_damaged) }
            loading = false
        }
    }
    if (loading) LoadingRow(Res.string.reading_document.str())
    loadError?.let { ErrorText(it) }
    val source = opened ?: return
    ResultCard(title = source.name) {
        KeyValueRow(Res.string.format.str(), source.format.label, copyable = false)
        source.doc.title?.let { KeyValueRow(Res.string.doc_title.str(), it, mono = false, copyable = false) }
        source.doc.author?.let { KeyValueRow(Res.string.doc_author.str(), it, mono = false, copyable = false) }
    }

    ToolSection(Res.string.format.str()) {
        ChoiceChips(
            options = targets.filter { it != source.format },
            selected = target,
            onSelect = {
                target = it
                result = null
            },
            label = { it?.label ?: "PDF" },
        )
    }
    if (target == null) {
        ToolSection(Res.string.page_size.str()) {
            ChoiceChips(options = PageFormat.entries, selected = page, onSelect = { page = it }, label = { if (it == PageFormat.LETTER) "Letter" else it.name })
        }
        ToolSection(Res.string.font.str()) {
            ChoiceChips(options = listOf(true, false), selected = serif, onSelect = { serif = it }, label = { if (it) Res.string.font_serif.str() else Res.string.font_sans.str() })
        }
        ToolSection(Res.string.font_size.str()) {
            ChoiceChips(options = listOf(10, 11, 12, 14), selected = size, onSelect = { size = it }, label = { "$it ${Res.string.unit_point.str()}" })
        }
        SwitchRow(Res.string.page_numbers.str(), numbers, { numbers = it })
    }

    ActionButton(
        text = Res.string.convert.str(),
        icon = Icons.Filled.Description,
        enabled = !task.running,
        onClick = {
            result = null
            val format = target
            val options = TypesetOptions(page, if (page == PageFormat.A5) 42.5f else 56.7f, size.toFloat(), numbers)
            val body = serif
            task.launch(scope) { progress ->
                val converted = if (format == null) {
                    val fonts = documentFonts(body)
                    val pictures = preparePictures(source.doc.blocks)
                    progress(0.5f)
                    val bytes = withContext(Dispatchers.Default) { PdfTypesetter.typeset(source.doc, fonts, pictures, options) }
                    Converted(renamed(source.name, "pdf"), bytes, PdfDocument.parse(bytes).pageCount)
                } else {
                    Converted(renamed(source.name, format.extension), withContext(Dispatchers.Default) { Documents.write(source.doc, format) }, 0)
                }
                result = converted
            }
        },
    )
    TaskProgress(task, Res.string.converting.str())
    result?.let { output ->
        ResultCard(title = Res.string.result.str()) {
            val sizeText = formatBytes(output.bytes.size.toLong(), binary = false)
            Text(
                if (output.pages > 0) pluralStringResource(Res.plurals.page_count, output.pages, output.pages) + " · " + sizeText else sizeText,
                style = MaterialTheme.typography.bodyLarge,
            )
            SaveButton { saveBytes(output.bytes, output.name) }
        }
    }
}
