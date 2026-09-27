package com.vasmarfas.card.tools.documents.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.pickFiles
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.PageFormat
import com.vasmarfas.card.tools.documents.documentFonts
import com.vasmarfas.card.tools.documents.pdf.GraphicKind
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfEncryptedException
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdfExtensions
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.tools.media.imageExtensions
import com.vasmarfas.card.tools.time.pad2
import com.vasmarfas.card.tools.time.today
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.LocalChrome
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.rememberCopy
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.number
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.min

val pdfEditorTool = Tool(
    id = "pdf-editor",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.pdf_editor,
    description = Res.string.pdf_editor_description,
    icon = Icons.Filled.HistoryEdu,
    keywords = listOf(
        "pdf editor", "edit pdf", "edit pdf text", "sign pdf", "fill pdf form", "annotate pdf", "highlight pdf", "add text to pdf", "draw on pdf",
        "redact pdf", "whiteout", "watermark", "page numbers", "password protect pdf", "encrypt pdf", "compress pdf", "bookmarks",
        "crop pdf", "insert page", "stamp", "signature",
        "редактор pdf", "редактировать pdf", "изменить текст в pdf", "подписать pdf", "заполнить pdf форму", "пометки в pdf", "выделить текст в pdf",
        "добавить текст в pdf", "рисовать на pdf", "скрыть данные в pdf", "замазать", "водяной знак", "нумерация страниц",
        "пароль на pdf", "зашифровать pdf", "сжать pdf", "закладки", "обрезать pdf", "вставить страницу", "штамп", "подпись",
    ),
    expandable = true,
) { PdfEditorScreen() }

private class Locked(val name: String, val bytes: ByteArray)

private class Saved(val pages: Int, val bytes: Int)

@Composable
private fun PdfEditorScreen() {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<EditorSession?>(null) }
    var locked by remember { mutableStateOf<Locked?>(null) }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(session) {
        val current = session
        onDispose { current?.close() }
    }

    suspend fun open(name: String, bytes: ByteArray, secret: String) {
        loading = true
        error = null
        val opened = runCatching { openSession(name, bytes, secret) }
        loading = false
        opened.onSuccess {
            session = it
            locked = null
            password = ""
        }.onFailure { e ->
            when {
                e !is PdfEncryptedException -> error = getString(Res.string.not_a_pdf)
                secret.isEmpty() -> locked = Locked(name, bytes)
                else -> error = getString(Res.string.wrong_password)
            }
        }
    }

    session?.let { current ->
        Editor(current) { session = null }
        return
    }
    PickButton(Res.string.choose_pdf.str(), pdfExtensions, icon = Icons.Filled.PictureAsPdf, empty = locked == null) { files ->
        val file = files.first()
        locked = null
        scope.launch { open(file.name, file.readBytes(), "") }
    }
    val file = locked
    if (file == null) {
        NewBlankButton {
            scope.launch {
                val fonts = MarkFonts(documentFonts(serif = true))
                val page = EditPage(1, BlankPage(PageFormat.A4.width.toDouble(), PageFormat.A4.height.toDouble()))
                session = EditorSession(getString(Res.string.pdf_edit_untitled) + ".pdf", null, fonts, DocumentEdit(listOf(page)), emptyList(), signed = false)
            }
        }
    } else {
        Text(file.name, style = MaterialTheme.typography.bodyLarge)
        ToolInputField(value = password, onValueChange = { password = it }, label = Res.string.password.str(), supportingText = Res.string.pdf_edit_password_needed.str())
        ActionButton(
            text = Res.string.pdf_edit_open.str(),
            icon = Icons.Filled.LockOpen,
            enabled = password.isNotEmpty() && !loading,
            onClick = { scope.launch { open(file.name, file.bytes, password) } },
        )
    }
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
}

private suspend fun openSession(name: String, bytes: ByteArray, password: String): EditorSession {
    val fonts = MarkFonts(documentFonts(serif = true))
    return withContext(Dispatchers.Default) {
        var document = PdfDocument.parse(bytes, password)
        var data = bytes
        if (document.encrypted) {
            data = PdfEditWriter.write(document, startingEdit(document), fonts)
            document = PdfDocument.parse(data)
        }
        val fields = runCatching { PdfForms.read(document) }.getOrDefault(emptyList())
        EditorSession(name, document, fonts, startingEdit(document), fields, hasSignature(document)).also { it.addSource(document, data) }
    }
}

internal fun hasSignature(document: PdfDocument): Boolean =
    ((document.catalog.dict("AcroForm", document)?.int("SigFlags", document) ?: 0) and 1) != 0

internal fun startingEdit(document: PdfDocument): DocumentEdit {
    val pages = document.pages.mapIndexed { i, page -> EditPage(i + 1L, SourcePage(page)) }
    val outline = runCatching { PdfOutline.read(document) { pages.getOrNull(it)?.id } }.getOrDefault(emptyList())
    return DocumentEdit(pages, outline = outline)
}

@Composable
private fun Editor(session: EditorSession, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chrome = LocalChrome.current
    val renderer = remember(session) { MarkRenderer(session.fonts) }
    val view = remember(session) { StageView() }
    val signatures = remember { SignatureStore() }
    val task = remember(session) { TaskState() }
    var panel by remember(session) { mutableStateOf(Panel.PAGES) }
    var options by remember(session) { mutableStateOf(EditorSaveOptions()) }
    var picked by remember(session) { mutableStateOf(setOf<Long>()) }
    var saved by remember(session) { mutableStateOf<Saved?>(null) }
    var savedEdit by remember(session) { mutableStateOf<DocumentEdit?>(null) }
    var drawing by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteMark?>(null) }
    var editingField by remember { mutableStateOf<FormField?>(null) }
    var closing by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val unsaved = session.changed && session.edit !== savedEdit
    val copy = rememberCopy()

    fun pickImage() {
        scope.launch {
            imageError = null
            val file = runCatching { pickFiles(imageExtensions, PickKind.IMAGE) }.getOrDefault(emptyList()).firstOrNull() ?: return@launch
            val image = prepareImage(file.readBytes())
            if (image == null) imageError = getString(Res.string.image_not_readable) else session.pendingImage = image
        }
    }

    fun replaceImage(target: PickedGraphic) {
        scope.launch {
            imageError = null
            val file = runCatching { pickFiles(imageExtensions, PickKind.IMAGE) }.getOrDefault(emptyList()).firstOrNull() ?: return@launch
            val image = prepareImage(file.readBytes()) ?: run {
                imageError = getString(Res.string.image_not_readable)
                return@launch
            }
            val page = session.edit.pages.firstOrNull { it.id == target.pageId } ?: return@launch
            val bounds = page.objects[target.graphic.first]?.transform?.bounds(target.bounds) ?: target.bounds
            val turned = page.rotation % 180 != 0
            val (fw, fh) = Affine.frameSize(bounds, page.rotation)
            val scale = min(fw / image.image.width, fh / image.image.height)
            val w = image.image.width * scale
            val h = image.image.height * scale
            val bw = if (turned) h else w
            val bh = if (turned) w else h
            val box = PdfRect(bounds.left + (bounds.width - bw) / 2, bounds.bottom + (bounds.height - bh) / 2, bounds.left + (bounds.width + bw) / 2, bounds.bottom + (bounds.height + bh) / 2)
            session.images[image.image] = image.bitmap
            val mark = ImageMark(session.id(), box, image.image, angle = page.rotation)
            session.updatePage(page.id) { it.copy(objects = it.objects + (target.graphic.first to ObjectEdit(removed = true)), marks = it.marks + mark) }
            session.picked = null
            session.selected = mark.id
        }
    }

    fun save() {
        val edit = session.edit
        val chosen = options
        saved = null
        task.launch(scope) { progress ->
            val bytes = buildPdf(session, renderer, edit, chosen, progress)
            if (saveBytes(bytes, renamed(session.name, "pdf", if (session.main == null) "" else "-edited"))) {
                savedEdit = edit
                saved = Saved(edit.pages.size, bytes.size)
            }
        }
    }

    val input = remember(session) {
        StageInput(
            session, renderer, view, scope,
            object : StageActions {
                override fun editNote(note: NoteMark) {
                    editingNote = note
                }

                override fun editField(field: FormField) {
                    editingField = field
                }

                override fun needSignature() {
                    drawing = true
                }

                override fun needImage() = pickImage()
            },
            signatures,
        )
    }
    val stamps = Stamp.entries.associateWith { it.label().str().uppercase() }
    val date = today().let { stringResource(Res.string.pdf_edit_date_pattern, it.day.pad2(), it.month.number.pad2(), it.year.toString()) }
    input.stampText = { if (it == Stamp.DATE) date else stamps.getValue(it) }

    LaunchedEffect(session.current) {
        view.reset()
        input.selection = null
        input.cropDraft = null
        session.picked = null
    }
    LaunchedEffect(session.tool, session.current) {
        val source = session.page.source as? SourcePage ?: return@LaunchedEffect
        when (session.tool) {
            EditTool.SELECT -> session.objects(source)
            EditTool.TEXT_SELECT -> session.text(source, session.page.objects)
            else -> Unit
        }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = unsaved && !chrome.immersive,
        onBackCompleted = { closing = true },
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(session.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val count = session.edit.pages.size
            Text(pluralStringResource(Res.plurals.page_count, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = ::save, enabled = !task.running) { Icon(Icons.Filled.Save, contentDescription = Res.string.pdf_edit_save_pdf.str()) }
        IconButton(onClick = { if (unsaved) closing = true else onClose() }) { Icon(Icons.Filled.Close, contentDescription = Res.string.pdf_edit_close.str()) }
    }
    if (session.signed) Text(Res.string.pdf_edit_signed_warning.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

    val tools = remember(session) {
        buildList {
            add(EditTool.SELECT)
            add(EditTool.VIEW)
            if (session.fields.isNotEmpty()) add(EditTool.FORM)
            addAll(
                listOf(
                    EditTool.TEXT, EditTool.SIGN, EditTool.PEN, EditTool.MARKER, EditTool.TEXT_SELECT, EditTool.SHAPE, EditTool.STAMP, EditTool.IMAGE,
                    EditTool.NOTE, EditTool.WHITEOUT, EditTool.REDACT, EditTool.ERASER, EditTool.CROP,
                ),
            )
        }
    }
    ToolStrip(session, tools) { tool ->
        session.tool = tool
        session.selected = null
        session.editing = null
        session.picked = null
        input.selection = null
        input.cropDraft = null
        if (tool == EditTool.SIGN && signatures.all.isEmpty()) drawing = true
        if (tool == EditTool.IMAGE && session.pendingImage == null) pickImage()
    }
    OptionsRow(
        session, input, renderer, signatures,
        onDrawSignature = { drawing = true },
        onPickImage = ::pickImage,
        onApplyCrop = { all ->
            input.cropDraft?.let { crop -> applyCrop(session, crop, all) }
            input.cropDraft = null
        },
    )
    imageError?.let { ErrorText(it) }

    PageStage(session, renderer, input, view) {
        val drag = input.drag
        val rect = if (drag != null && !drag.done) null else input.selectionRect(session.page)
        if (rect != null) SelectionBar(rect, selectionActions(session, input, copy, ::replaceImage) { editingNote = it })
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { session.current -= 1 }, enabled = session.current > 0) {
            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = Res.string.pdf_edit_previous_page.str())
        }
        Text("${session.current + 1} / ${session.edit.pages.size}", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { session.current += 1 }, enabled = session.current < session.edit.pages.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = Res.string.pdf_edit_next_page.str())
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { view.zoomTo(view.zoom / 1.25f, view.center, session.page) }, enabled = view.zoom > 1f) {
            Icon(Icons.Filled.ZoomOut, contentDescription = Res.string.zoom_out.str())
        }
        IconButton(onClick = { view.zoomTo(view.zoom * 1.25f, view.center, session.page) }, enabled = view.zoom < MAX_ZOOM) {
            Icon(Icons.Filled.ZoomIn, contentDescription = Res.string.zoom_in.str())
        }
        IconButton(onClick = session::undo, enabled = session.undoDepth > 0) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = Res.string.undo.str()) }
        IconButton(onClick = session::redo, enabled = session.redoDepth > 0) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = Res.string.redo.str()) }
    }
    val strip = rememberLazyListState()
    LaunchedEffect(session.current) { strip.animateScrollToItem((session.current - 2).coerceAtLeast(0)) }
    LazyRow(state = strip, modifier = Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(session.edit.pages, key = { _, page -> page.id }) { i, page ->
            PageThumb(
                session, page, selected = page.id in picked, current = i == session.current, number = i + 1,
                onClick = { session.current = i },
                onToggle = if (panel == Panel.PAGES) ({ picked = if (page.id in picked) picked - page.id else picked + page.id }) else null,
            )
        }
    }

    val panels = Panel.entries.filter { it != Panel.FORM || session.fields.isNotEmpty() }
    ChoiceChips(
        options = panels,
        selected = panel,
        onSelect = { panel = it },
        label = {
            when (it) {
                Panel.PAGES -> Res.string.pdf_edit_panel_pages.str()
                Panel.SEARCH -> Res.string.search.str()
                Panel.FORM -> Res.string.pdf_edit_tool_form.str()
                Panel.OUTLINE -> Res.string.pdf_edit_panel_bookmarks.str()
                Panel.DOCUMENT -> Res.string.pdf_edit_panel_document.str()
            }
        },
        icon = {
            when (it) {
                Panel.PAGES -> Icons.Filled.Layers
                Panel.SEARCH -> Icons.Filled.Search
                Panel.FORM -> Icons.Filled.Checklist
                Panel.OUTLINE -> Icons.Filled.Bookmarks
                Panel.DOCUMENT -> Icons.Filled.Tune
            }
        },
    )
    when (panel) {
        Panel.PAGES -> PagesPanel(session, renderer, picked) { picked = it }
        Panel.SEARCH -> SearchPanel(session, renderer)
        Panel.FORM -> FormPanel(session)
        Panel.OUTLINE -> OutlinePanel(session)
        Panel.DOCUMENT -> DocumentPanel(session, options) { options = it }
    }

    ToolSection(Res.string.pdf_edit_saving.str()) {
        SwitchRow(Res.string.pdf_edit_keep_editable.str(), options.keepEditable, { options = options.copy(keepEditable = it) }, description = Res.string.pdf_edit_keep_editable_hint.str())
        if (session.fields.isNotEmpty()) SwitchRow(Res.string.pdf_edit_flatten_form.str(), options.flattenForm, { options = options.copy(flattenForm = it) })
        if (session.edit.pages.any { page -> page.marks.any { it is CoverMark && it.redact } }) {
            SwitchRow(Res.string.pdf_edit_burn_redactions.str(), options.burnRedactions, { options = options.copy(burnRedactions = it) }, description = Res.string.pdf_edit_burn_redactions_hint.str())
        }
        ActionButton(text = Res.string.pdf_edit_save_pdf.str(), icon = Icons.Filled.Save, enabled = !task.running, onClick = ::save)
        TaskProgress(task, Res.string.pdf_edit_building.str())
        saved?.let {
            Text(
                pluralStringResource(Res.plurals.page_count, it.pages, it.pages) + " · " + formatBytes(it.bytes.toLong(), binary = false),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (drawing) {
        SignatureDialog(session.style.signatureColor, onDismiss = { drawing = false }) { signature ->
            signatures.add(signature)
            session.style = session.style.copy(signature = 0)
            session.tool = EditTool.SIGN
            drawing = false
        }
    }
    editingNote?.let { note ->
        NoteDialog(
            note,
            onDismiss = {
                if ((session.mark(note.id) as? NoteMark)?.text?.isEmpty() == true) session.remove(note.id)
                editingNote = null
            },
            onDelete = {
                session.remove(note.id)
                editingNote = null
            },
        ) { text ->
            if (text.isBlank()) session.remove(note.id) else session.replace(note.copy(text = text))
            editingNote = null
        }
    }
    editingField?.let { field ->
        FieldDialog(field, session.value(field), onDismiss = { editingField = null }) { value ->
            session.setField(field, value)
            editingField = null
        }
    }
    if (closing) {
        ConfirmCloseDialog(onDismiss = { closing = false }) {
            closing = false
            onClose()
        }
    }
}

@Composable
private fun selectionActions(session: EditorSession, input: StageInput, copy: (String) -> Unit, replace: (PickedGraphic) -> Unit, editNote: (NoteMark) -> Unit): List<BarAction> {
    val edit = Res.string.edit.str()
    val remove = Res.string.delete.str()
    val page = session.page
    session.mark(session.selected)?.let { mark ->
        return buildList {
            if (mark is TextMark) add(BarAction(Icons.Filled.Edit, edit) { session.editing = mark.id })
            if (mark is NoteMark) add(BarAction(Icons.Filled.Edit, edit) { editNote(mark) })
            add(BarAction(Icons.Filled.FileCopy, Res.string.duplicate.str()) { session.add(duplicate(session, mark), select = true) })
            add(BarAction(Icons.Filled.Delete, remove) { session.remove(mark.id) })
        }
    }
    when (val picked = session.picked) {
        is PickedText -> return listOf(
            BarAction(Icons.Filled.Edit, Res.string.pdf_edit_edit_text.str()) { input.editBlock(page, picked.block) },
            BarAction(Icons.Filled.ContentCopy, Res.string.copy.str()) { copy(picked.block.text) },
            BarAction(Icons.Filled.Delete, remove) { session.removeObjects(picked.pageId, picked.keys) },
        )
        is PickedGraphic -> return buildList {
            if (picked.graphic.kind == GraphicKind.IMAGE) add(BarAction(Icons.Filled.FindReplace, Res.string.pdf_edit_replace_image.str()) { replace(picked) })
            add(BarAction(Icons.Filled.Delete, remove) { session.removeObjects(picked.pageId, picked.keys) })
        }
        null -> Unit
    }
    val selection = input.selection ?: return emptyList()
    fun markup(kind: MarkupKind, color: Int) {
        session.add(MarkupMark(session.id(), kind, selection.quads, color, selection.text))
        input.selection = null
    }
    return listOf(
        BarAction(Icons.Filled.ContentCopy, Res.string.copy.str()) { copy(selection.text) },
        BarAction(Icons.Filled.Highlight, Res.string.pdf_edit_highlight.str()) { markup(MarkupKind.HIGHLIGHT, 0xFFFFEB3B.toInt()) },
        BarAction(Icons.Filled.FormatUnderlined, Res.string.pdf_edit_underline.str()) { markup(MarkupKind.UNDERLINE, 0xFF1565C0.toInt()) },
        BarAction(Icons.Filled.FormatStrikethrough, Res.string.pdf_edit_strike.str()) { markup(MarkupKind.STRIKEOUT, 0xFFD32F2F.toInt()) },
        BarAction(Icons.Filled.Waves, Res.string.pdf_edit_squiggly.str()) { markup(MarkupKind.SQUIGGLY, 0xFFD32F2F.toInt()) },
        BarAction(Icons.Filled.HideSource, Res.string.pdf_edit_tool_redact.str()) {
            session.updatePage(page.id) { p -> p.copy(marks = p.marks + selection.quads.map { q -> CoverMark(session.id(), q.bounds(), 0xFF000000.toInt(), redact = true) }) }
            input.selection = null
        },
    )
}

private fun applyCrop(session: EditorSession, crop: PdfRect, all: Boolean) {
    if (!all) {
        session.updatePage(session.page.id) { it.copy(crop = crop) }
        return
    }
    val base = session.page.source.cropBox
    fun same(box: PdfRect) = abs(box.left - base.left) < 0.5 && abs(box.bottom - base.bottom) < 0.5 && abs(box.right - base.right) < 0.5 && abs(box.top - base.top) < 0.5
    session.pages { pages -> pages.map { if (same(it.source.cropBox)) it.copy(crop = crop) else it } }
}
