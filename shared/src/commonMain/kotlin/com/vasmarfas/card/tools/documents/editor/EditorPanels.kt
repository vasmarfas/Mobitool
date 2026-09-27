package com.vasmarfas.card.tools.documents.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.keyboardDialogProperties
import com.vasmarfas.card.core.pickFiles
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.documents.pdf.PdfDict
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfEncryptedException
import com.vasmarfas.card.tools.media.EditButton
import com.vasmarfas.card.tools.media.EditButtons
import com.vasmarfas.card.tools.media.LabeledSlider
import com.vasmarfas.card.tools.media.imageExtensions
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal enum class Panel { PAGES, SEARCH, FORM, OUTLINE, DOCUMENT }

@Composable
internal fun PageThumb(session: EditorSession, page: EditPage, selected: Boolean, current: Boolean, number: Int, onClick: () -> Unit, onToggle: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val source = page.source
    val key = source to page.objects
    val bitmap = session.thumbs[key]
    LaunchedEffect(key) {
        if (bitmap == null && source is SourcePage) session.render(source, 180, page.objects)?.let { session.thumbs[key] = it }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(shape)
            .border(2.dp, if (current) colors.primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val frame = page.frame
        val landscape = frame.width > frame.height
        Box(Modifier.size(if (landscape) 96.dp else 68.dp, if (landscape) 68.dp else 96.dp).background(Color.White, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            val image = bitmap ?: (source as? ImagePage)?.let { session.images[it.image] }
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(1.dp).rotate(page.turn.toFloat()).scale(if (page.turn % 180 == 0) 1f else 0.72f),
                )
            }
            if (page.marks.isNotEmpty()) {
                Box(Modifier.align(Alignment.BottomStart).padding(3.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.tertiary))
            }
            if (onToggle != null) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (selected) colors.primary else colors.outlineVariant,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).clickable(onClick = onToggle),
                )
            }
        }
        Text("$number", style = MaterialTheme.typography.labelMedium, color = if (current) colors.primary else colors.onSurfaceVariant)
    }
}

@Composable
internal fun PagesPanel(session: EditorSession, renderer: MarkRenderer, picked: Set<Long>, onPicked: (Set<Long>) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val pages = session.edit.pages
    val targets = pages.filter { it.id in picked }.ifEmpty { listOf(session.page) }
    val ids = targets.map { it.id }.toSet()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (picked.isEmpty()) Res.string.pdf_edit_pages_hint.str() else pluralStringResource(Res.plurals.pdf_edit_pages_picked, picked.size, picked.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (picked.isNotEmpty()) TextButton(onClick = { onPicked(emptySet()) }) { Text(Res.string.reset.str()) }
    }
    EditButtons {
        EditButton(Res.string.rotate_left.str(), Icons.Filled.Rotate90DegreesCcw, true) { session.pages { list -> list.map { if (it.id in ids) it.copy(turn = (it.turn + 270) % 360) else it } } }
        EditButton(Res.string.rotate_right.str(), Icons.Filled.Rotate90DegreesCw, true) { session.pages { list -> list.map { if (it.id in ids) it.copy(turn = (it.turn + 90) % 360) else it } } }
        EditButton(Res.string.move_earlier.str(), Icons.AutoMirrored.Filled.ArrowBack, pages.firstOrNull()?.id !in ids) {
            session.pages { list ->
                val out = list.toMutableList()
                for (i in 1 until out.size) if (out[i].id in ids && out[i - 1].id !in ids) out.add(i - 1, out.removeAt(i))
                out
            }
        }
        EditButton(Res.string.move_later.str(), Icons.AutoMirrored.Filled.ArrowForward, pages.lastOrNull()?.id !in ids) {
            session.pages { list ->
                val out = list.toMutableList()
                for (i in out.size - 2 downTo 0) if (out[i].id in ids && out[i + 1].id !in ids) out.add(i + 1, out.removeAt(i))
                out
            }
        }
        EditButton(Res.string.duplicate.str(), Icons.Filled.FileCopy, true) {
            session.pages { list -> list.flatMap { if (it.id in ids) listOf(it, copyPage(session, it)) else listOf(it) } }
        }
        EditButton(Res.string.delete_selected.str(), Icons.Filled.Delete, targets.size < pages.size) {
            session.pages { list -> list.filterNot { it.id in ids } }
            onPicked(emptySet())
        }
        EditButton(Res.string.pdf_edit_blank_page.str(), Icons.Filled.PostAdd, true) {
            val frame = session.page.frame
            val blank = EditPage(session.id(), BlankPage(frame.width, frame.height))
            val at = session.current + 1
            session.pages { list -> list.toMutableList().apply { add(at.coerceAtMost(size), blank) } }
            session.current = at
        }
        EditButton(Res.string.pdf_edit_insert_pdf.str(), Icons.Filled.PictureAsPdf, true) {
            scope.launch {
                error = null
                val file = runCatching { pickFiles(setOf("pdf")) }.getOrDefault(emptyList()).firstOrNull() ?: return@launch
                val bytes = file.readBytes()
                val parsed = runCatching { withContext(Dispatchers.Default) { PdfDocument.parse(bytes) } }
                val document = parsed.getOrNull()
                if (document == null || document.pageCount == 0) {
                    error = getString(if (parsed.exceptionOrNull() is PdfEncryptedException) Res.string.pdf_protected else Res.string.not_a_pdf)
                    return@launch
                }
                session.addSource(document, bytes)
                val added = document.pages.map { EditPage(session.id(), SourcePage(it)) }
                val at = session.current + 1
                session.pages { list -> list.toMutableList().apply { addAll(at.coerceAtMost(size), added) } }
            }
        }
        EditButton(Res.string.pdf_edit_insert_images.str(), Icons.Filled.AddPhotoAlternate, true) {
            scope.launch {
                error = null
                val files = runCatching { pickFiles(imageExtensions, PickKind.IMAGE, multiple = true) }.getOrDefault(emptyList())
                val width = session.page.frame.width
                val added = files.mapNotNull { file ->
                    val prepared = prepareImage(file.readBytes()) ?: return@mapNotNull null
                    session.images[prepared.image] = prepared.bitmap
                    EditPage(session.id(), ImagePage(prepared.image, width, width * prepared.image.height / prepared.image.width))
                }
                if (added.isEmpty() && files.isNotEmpty()) error = getString(Res.string.image_not_readable)
                val at = session.current + 1
                if (added.isNotEmpty()) session.pages { list -> list.toMutableList().apply { addAll(at.coerceAtMost(size), added) } }
            }
        }
        EditButton(Res.string.pdf_edit_extract.str(), Icons.Filled.ContentCopy, true) {
            scope.launch {
                error = null
                runCatching {
                    val bytes = buildPdf(session, renderer, session.edit.copy(pages = targets, outline = emptyList()), EditorSaveOptions()) {}
                    saveBytes(bytes, renamed(session.name, "pdf", "-pages"))
                }.onFailure { error = it.message ?: it.toString() }
            }
        }
    }
    error?.let { ErrorText(it) }
}

private fun copyPage(session: EditorSession, page: EditPage): EditPage = page.copy(
    id = session.id(),
    marks = page.marks.map { mark ->
        when (mark) {
            is InkMark -> mark.copy(id = session.id())
            is ShapeMark -> mark.copy(id = session.id())
            is TextMark -> mark.copy(id = session.id())
            is ImageMark -> mark.copy(id = session.id())
            is SignatureMark -> mark.copy(id = session.id())
            is NoteMark -> mark.copy(id = session.id())
            is CoverMark -> mark.copy(id = session.id())
            is MarkupMark -> mark.copy(id = session.id())
        }
    },
)

@Composable
internal fun SearchPanel(session: EditorSession, renderer: MarkRenderer) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    fun run() {
        val query = session.query
        busy = true
        scope.launch {
            val hits = ArrayList<SearchHit>()
            for (page in session.edit.pages) {
                val source = page.source as? SourcePage
                if (source != null) for (match in session.text(source, page.objects).search(query)) hits += SearchHit(page.id, match)
                for (mark in page.marks) {
                    if (mark is TextMark) for (match in markMatches(renderer.layout(mark), mark, query)) hits += SearchHit(page.id, match)
                }
            }
            session.results = hits
            session.hit = 0
            hits.firstOrNull()?.let { first -> session.current = session.edit.pages.indexOfFirst { it.id == first.pageId }.coerceAtLeast(0) }
            busy = false
            searched = true
        }
    }
    ToolInputField(
        value = session.query,
        onValueChange = {
            session.query = it
            searched = false
            if (it.isBlank()) session.results = emptyList()
        },
        label = Res.string.search.str(),
        trailingIcon = { IconButton(onClick = ::run, enabled = session.query.isNotBlank() && !busy) { Icon(Icons.Filled.Search, contentDescription = Res.string.search.str()) } },
    )
    val results = session.results
    if (searched) {
        Text(
            if (results.isEmpty()) Res.string.nothing_found.str() else pluralStringResource(Res.plurals.pdf_edit_matches, results.size, results.size),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (results.isEmpty()) return
    fun go(index: Int) {
        session.hit = (index + results.size) % results.size
        val target = results[session.hit].pageId
        session.current = session.edit.pages.indexOfFirst { it.id == target }.coerceAtLeast(0)
    }
    EditButtons {
        EditButton(Res.string.pdf_edit_previous_match.str(), Icons.AutoMirrored.Filled.ArrowBack, true) { go(session.hit - 1) }
        EditButton(Res.string.pdf_edit_next_match.str(), Icons.AutoMirrored.Filled.ArrowForward, true) { go(session.hit + 1) }
        EditButton(Res.string.pdf_edit_highlight_all.str(), Icons.Filled.Highlight, true) {
            session.pages { list ->
                list.map { page ->
                    val found = results.filter { it.pageId == page.id }
                    if (found.isEmpty()) page else page.copy(marks = page.marks + found.map { MarkupMark(session.id(), MarkupKind.HIGHLIGHT, it.match.quads, 0xFFFFEB3B.toInt(), it.match.text) })
                }
            }
        }
        EditButton(Res.string.pdf_edit_redact_all.str(), Icons.Filled.HideSource, true) {
            session.pages { list ->
                list.map { page ->
                    val found = results.filter { it.pageId == page.id }
                    if (found.isEmpty()) page else page.copy(marks = page.marks + found.flatMap { hit -> hit.match.quads.map { CoverMark(session.id(), it.bounds(), 0xFF000000.toInt(), redact = true) } })
                }
            }
            session.results = emptyList()
        }
    }
    Text("${session.hit + 1} / ${results.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun FormPanel(session: EditorSession) {
    val fields = session.fields.filter { it.kind != FieldKind.BUTTON && it.kind != FieldKind.SIGNATURE }
    Text(pluralStringResource(Res.plurals.pdf_edit_fields, fields.size, fields.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (field in fields.sortedBy { it.widgets.first().pageIndex }) {
        val label = field.tooltip ?: field.name
        val value = session.value(field)
        val pageIndex = session.edit.pages.indexOfFirst { (it.source as? SourcePage)?.page?.let { p -> p.document === session.main && p.index == field.widgets.first().pageIndex } == true }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label + if (field.required) " *" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (pageIndex >= 0) TextButton(onClick = { session.current = pageIndex }) { Text(stringResource(Res.string.pdf_edit_page_number, pageIndex + 1)) }
            }
            when (field.kind) {
                FieldKind.TEXT -> ToolInputField(
                    value = (value as? FieldValue.Text)?.value.orEmpty(),
                    onValueChange = { text -> if (!field.readOnly) session.setField(field, FieldValue.Text(if (field.maxLength > 0) text.take(field.maxLength) else text)) },
                    label = label,
                    singleLine = !field.multiline,
                    minLines = if (field.multiline) 3 else 1,
                )
                FieldKind.CHECKBOX -> SwitchRow(label, (value as? FieldValue.Check)?.state != null, { on -> if (!field.readOnly) session.setField(field, FieldValue.Check(if (on) field.widgets.first().onState ?: "Yes" else null)) })
                FieldKind.RADIO -> ChoiceChips(
                    options = field.widgets.mapNotNull { it.onState }.distinct(),
                    selected = (value as? FieldValue.Check)?.state,
                    onSelect = { state -> if (!field.readOnly) session.setField(field, FieldValue.Check(state)) },
                    label = { it },
                )
                FieldKind.COMBO, FieldKind.LIST -> if (field.options.isNotEmpty()) {
                    val current = (value as? FieldValue.Choice)?.values?.firstOrNull()
                    DropdownChoice(
                        options = field.options,
                        selected = field.options.firstOrNull { it.export == current } ?: field.options.first(),
                        onSelect = { option -> if (!field.readOnly) session.setField(field, FieldValue.Choice(listOf(option.export))) },
                        label = label,
                        text = { it.label },
                    )
                }
                FieldKind.BUTTON, FieldKind.SIGNATURE -> Unit
            }
        }
    }
    EditButtons {
        EditButton(Res.string.pdf_edit_clear_form.str(), Icons.Filled.Delete, fields.isNotEmpty()) {
            session.commit(
                session.edit.copy(
                    fields = fields.associate { field ->
                        field.name to when (field.kind) {
                            FieldKind.TEXT -> FieldValue.Text("")
                            FieldKind.COMBO, FieldKind.LIST -> FieldValue.Choice(emptyList())
                            else -> FieldValue.Check(null)
                        }
                    },
                ),
            )
        }
    }
}

private class OutlineRow(val item: OutlineItem, val depth: Int, val path: List<Int>)

private fun flatten(items: List<OutlineItem>, depth: Int = 0, prefix: List<Int> = emptyList()): List<OutlineRow> =
    items.flatMapIndexed { i, item -> listOf(OutlineRow(item, depth, prefix + i)) + flatten(item.children, depth + 1, prefix + i) }

private fun update(items: List<OutlineItem>, path: List<Int>, change: (OutlineItem) -> OutlineItem?): List<OutlineItem> =
    items.flatMapIndexed { i, item ->
        when {
            i != path.first() -> listOf(item)
            path.size == 1 -> listOfNotNull(change(item))
            else -> listOf(item.copy(children = update(item.children, path.drop(1), change)))
        }
    }

@Composable
internal fun OutlinePanel(session: EditorSession) {
    var renaming by remember { mutableStateOf<OutlineRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val outline = session.edit.outline
    if (outline.isEmpty()) Text(Res.string.pdf_edit_no_bookmarks.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (row in flatten(outline)) {
        val index = session.edit.pages.indexOfFirst { it.id == row.item.pageId }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = index >= 0) { session.current = index }.padding(start = (row.depth * 16).dp)) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(row.item.title.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (index >= 0) stringResource(Res.string.pdf_edit_page_number, index + 1) else Res.string.pdf_edit_bookmark_no_page.str(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { renaming = row }) { Icon(Icons.Filled.Edit, contentDescription = Res.string.edit.str()) }
            IconButton(onClick = { session.commit(session.edit.copy(outline = update(outline, row.path) { null })) }) { Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str()) }
        }
    }
    EditButtons { EditButton(Res.string.pdf_edit_add_bookmark.str(), Icons.Filled.BookmarkAdd, true) { adding = true } }
    renaming?.let { row ->
        TitleDialog(row.item.title, onDismiss = { renaming = null }) { title ->
            session.commit(session.edit.copy(outline = update(outline, row.path) { it.copy(title = title) }))
            renaming = null
        }
    }
    if (adding) {
        val initial = stringResource(Res.string.pdf_edit_page_number, session.current + 1)
        TitleDialog(initial, onDismiss = { adding = false }) { title ->
            session.commit(session.edit.copy(outline = outline + OutlineItem(title, session.page.id)))
            adding = false
        }
    }
}

@Composable
internal fun TitleDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = keyboardDialogProperties(),
        title = { Text(Res.string.pdf_edit_bookmark.str()) },
        text = {
            ToolInputField(value = text, onValueChange = { text = it }, label = Res.string.pdf_edit_bookmark_title.str(), modifier = Modifier.focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(Res.string.done.str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Res.string.cancel.str()) } },
    )
}

private val infoFields = listOf("Title" to Res.string.pdf_edit_info_title, "Author" to Res.string.pdf_edit_info_author, "Subject" to Res.string.subject, "Keywords" to Res.string.pdf_edit_info_keywords)

@Composable
internal fun DocumentPanel(session: EditorSession, save: EditorSaveOptions, onSave: (EditorSaveOptions) -> Unit) {
    val edit = session.edit
    ToolSection(Res.string.pdf_edit_info.str()) {
        for ((key, label) in infoFields) {
            ToolInputField(
                value = edit.info[key] ?: session.main?.info?.get(key).orEmpty(),
                onValueChange = { session.type("info:$key", session.edit.copy(info = session.edit.info + (key to it))) },
                label = label.str(),
            )
        }
    }
    ToolSection(Res.string.pdf_edit_numbering.str()) {
        val numbering = edit.numbering
        SwitchRow(Res.string.pdf_edit_numbering_on.str(), numbering != null, { on -> session.commit(session.edit.copy(numbering = if (on) PageNumbering() else null)) })
        if (numbering != null) {
            fun set(next: PageNumbering) = session.commit(session.edit.copy(numbering = next))
            val formats = listOf("{n}", "{n} / {total}", Res.string.pdf_edit_numbering_format.str())
            ChoiceChips(options = formats, selected = numbering.format, onSelect = { set(numbering.copy(format = it)) }, label = { PageNumbering(it).label(0, session.edit.pages.size) })
            ChoiceChips(options = NumberPosition.entries, selected = numbering.position, onSelect = { set(numbering.copy(position = it)) }, label = { positionLabel(it) })
            LabeledSlider(Res.string.font_size.str(), "${numbering.size.toDouble().fmt(0)} ${Res.string.unit_point.str()}", numbering.size, 6f..24f) { set(numbering.copy(size = it.toInt().toFloat())) }
            NumberField(
                value = numbering.start.toString(),
                onValueChange = { text -> text.toIntOrNull()?.takeIf { it in 0..99_999 }?.let { set(numbering.copy(start = it)) } },
                label = Res.string.pdf_edit_numbering_start.str(),
            )
            SwitchRow(Res.string.pdf_edit_numbering_skip_first.str(), numbering.skipFirst, { set(numbering.copy(skipFirst = it)) })
        }
    }
    ToolSection(Res.string.pdf_edit_watermark.str()) {
        val watermark = edit.watermark
        val draft = Res.string.pdf_edit_stamp_draft.str().uppercase()
        SwitchRow(Res.string.pdf_edit_watermark_on.str(), watermark != null, { on -> session.commit(session.edit.copy(watermark = if (on) Watermark(draft) else null)) })
        if (watermark != null) {
            fun set(next: Watermark, typing: Boolean = false) = if (typing) session.type("watermark", session.edit.copy(watermark = next)) else session.commit(session.edit.copy(watermark = next))
            ToolInputField(value = watermark.text, onValueChange = { set(watermark.copy(text = it), typing = true) }, label = Res.string.text.str())
            LabeledSlider(Res.string.font_size.str(), "${watermark.size.toDouble().fmt(0)} ${Res.string.unit_point.str()}", watermark.size, 16f..140f) { set(watermark.copy(size = it.toInt().toFloat())) }
            LabeledSlider(Res.string.pdf_edit_opacity.str(), "${(watermark.opacity * 100).toInt()} %", watermark.opacity, 0.05f..1f) { set(watermark.copy(opacity = it)) }
            ChoiceChips(options = listOf(0, 30, 45, 60, 90), selected = watermark.angle, onSelect = { set(watermark.copy(angle = it)) }, label = { "$it°" })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorDots(listOf(0xFFC62828, 0xFF1565C0, 0xFF757575, 0xFF000000, 0xFF2E7D32).map { it.toInt() }, watermark.color) { set(watermark.copy(color = it)) }
            }
        }
    }
    ToolSection(Res.string.pdf_edit_protection.str()) {
        val security = edit.security
        SwitchRow(Res.string.pdf_edit_protection_on.str(), security != null, { on -> session.commit(session.edit.copy(security = if (on) Security("") else null)) })
        if (security != null) {
            fun set(next: Security, key: String? = null) = if (key != null) session.type(key, session.edit.copy(security = next)) else session.commit(session.edit.copy(security = next))
            ToolInputField(value = security.userPassword, onValueChange = { set(security.copy(userPassword = it), "password") }, label = Res.string.password.str(), supportingText = Res.string.pdf_edit_password_hint.str())
            ToolInputField(value = security.ownerPassword, onValueChange = { set(security.copy(ownerPassword = it), "owner") }, label = Res.string.pdf_edit_owner_password.str(), supportingText = Res.string.pdf_edit_owner_password_hint.str())
            SwitchRow(Res.string.pdf_edit_allow_print.str(), security.allowPrint, { set(security.copy(allowPrint = it)) })
            SwitchRow(Res.string.pdf_edit_allow_copy.str(), security.allowCopy, { set(security.copy(allowCopy = it)) })
            SwitchRow(Res.string.pdf_edit_allow_edit.str(), security.allowEdit, { set(security.copy(allowEdit = it)) })
        }
    }
    ToolSection(Res.string.pdf_edit_optimize.str()) {
        SwitchRow(Res.string.pdf_edit_compress_images.str(), save.compress, { onSave(save.copy(compress = it)) }, description = Res.string.pdf_edit_compress_images_hint.str())
        if (save.compress) {
            ChoiceChips(options = ImageQuality.entries, selected = save.quality, onSelect = { onSave(save.copy(quality = it)) }, label = { qualityLabel(it) })
        }
        val main = session.main
        val annotated = remember(main) {
            main != null && main.pages.any { page -> page.dict.array("Annots", main)?.items?.any { (main.resolve(it) as? PdfDict)?.name("Subtype", main) != "Widget" } == true }
        }
        if (annotated) {
            SwitchRow(Res.string.pdf_edit_flatten_annotations.str(), save.flattenAnnotations, { onSave(save.copy(flattenAnnotations = it, removeAnnotations = false)) }, description = Res.string.pdf_edit_flatten_annotations_hint.str())
            SwitchRow(Res.string.pdf_edit_remove_annotations.str(), save.removeAnnotations, { onSave(save.copy(removeAnnotations = it, flattenAnnotations = false)) })
        }
    }
}

@Composable
private fun positionLabel(position: NumberPosition): String = when (position) {
    NumberPosition.TOP_LEFT -> Res.string.pdf_edit_position_top_left.str()
    NumberPosition.TOP_CENTER -> Res.string.pdf_edit_position_top_center.str()
    NumberPosition.TOP_RIGHT -> Res.string.pdf_edit_position_top_right.str()
    NumberPosition.BOTTOM_LEFT -> Res.string.pdf_edit_position_bottom_left.str()
    NumberPosition.BOTTOM_CENTER -> Res.string.pdf_edit_position_bottom_center.str()
    NumberPosition.BOTTOM_RIGHT -> Res.string.pdf_edit_position_bottom_right.str()
}

@Composable
internal fun qualityLabel(quality: ImageQuality): String = when (quality) {
    ImageQuality.HIGH -> Res.string.pdf_edit_quality_high.str()
    ImageQuality.MEDIUM -> Res.string.pdf_edit_quality_medium.str()
    ImageQuality.LOW -> Res.string.pdf_edit_quality_low.str()
}

@Composable
internal fun NoteDialog(note: NoteMark, onDismiss: () -> Unit, onDelete: () -> Unit, onDone: (String) -> Unit) {
    var text by remember(note.id) { mutableStateOf(note.text) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = keyboardDialogProperties(),
        title = { Text(Res.string.note.str()) },
        text = {
            ToolInputField(value = text, onValueChange = { text = it }, label = Res.string.text.str(), singleLine = false, minLines = 3, modifier = Modifier.focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(Res.string.done.str()) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(Res.string.delete.str()) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text(Res.string.cancel.str()) }
            }
        },
    )
}

@Composable
internal fun FieldDialog(field: FormField, value: FieldValue?, onDismiss: () -> Unit, onDone: (FieldValue) -> Unit) {
    var text by remember(field.name) { mutableStateOf((value as? FieldValue.Text)?.value.orEmpty()) }
    val focus = remember { FocusRequester() }
    val label = field.tooltip ?: field.name
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = keyboardDialogProperties(),
        title = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when (field.kind) {
                FieldKind.TEXT -> {
                    ToolInputField(
                        value = text,
                        onValueChange = { text = if (field.maxLength > 0) it.take(field.maxLength) else it },
                        label = label,
                        modifier = Modifier.focusRequester(focus),
                        singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1,
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
                else -> Column {
                    val selected = (value as? FieldValue.Choice)?.values.orEmpty()
                    for (option in field.options) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDone(FieldValue.Choice(listOf(option.export))) }) {
                            Checkbox(checked = option.export in selected, onCheckedChange = { onDone(FieldValue.Choice(listOf(option.export))) })
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { if (field.kind == FieldKind.TEXT) TextButton(onClick = { onDone(FieldValue.Text(text)) }) { Text(Res.string.done.str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Res.string.cancel.str()) } },
    )
}

@Composable
internal fun ConfirmCloseDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Res.string.pdf_edit_close_title.str()) },
        text = { Text(Res.string.pdf_edit_close_body.str()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(Res.string.pdf_edit_close_confirm.str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Res.string.cancel.str()) } },
    )
}

@Composable
internal fun NewBlankButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Res.string.pdf_edit_new_document.str())
    }
}
