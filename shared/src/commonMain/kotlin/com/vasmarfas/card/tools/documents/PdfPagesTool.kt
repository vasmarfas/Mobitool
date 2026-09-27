package com.vasmarfas.card.tools.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PdfRaster
import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.editor.MarkFonts
import com.vasmarfas.card.tools.documents.editor.PdfEditWriter
import com.vasmarfas.card.tools.documents.editor.startingEdit
import com.vasmarfas.card.tools.media.EditButton
import com.vasmarfas.card.tools.media.EditButtons
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val pdfPagesTool = Tool(
    id = "pdf-pages",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.pdf_pages,
    description = Res.string.pdf_pages_description,
    icon = Icons.Filled.FilterNone,
    keywords = listOf(
        "split pdf", "extract pages", "delete pages", "rotate pdf", "reorder pages", "remove page from pdf",
        "разделить pdf", "извлечь страницы", "удалить страницу из pdf", "повернуть pdf", "переставить страницы", "страницы pdf",
    ),
) { PdfPagesScreen() }

@Stable
private class PageSlot(val index: Int) {
    var rotation by mutableStateOf(0)
    var selected by mutableStateOf(false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfPagesScreen() {
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedPdf?>(null) }
    var raster by remember { mutableStateOf<PdfRaster?>(null) }
    val slots = remember { mutableStateListOf<PageSlot>() }
    val thumbnails = remember { mutableStateMapOf<Int, ImageBitmap>() }
    var range by remember { mutableStateOf("") }

    PickButton(Res.string.choose_pdf.str(), pdfExtensions, icon = Icons.Filled.PictureAsPdf, empty = opened == null) { files ->
        scope.launch {
            raster = null
            slots.clear()
            thumbnails.clear()
            val pdf = openPdf(files.first())
            opened = pdf
            val document = pdf.document ?: return@launch
            slots += (0 until document.pageCount).map { PageSlot(it) }
            raster = runCatching { PdfRaster.open(files.first().readBytes()) }.getOrNull()
        }
    }
    DisposableEffect(raster) {
        val current = raster
        onDispose { current?.close() }
    }
    val pdf = opened ?: return
    val document = pdf.document
    if (document == null) {
        ErrorText(pdf.detail)
        return
    }
    LaunchedEffect(raster) {
        val renderer = raster ?: return@LaunchedEffect
        for (i in 0 until document.pageCount) {
            runCatching { renderer.render(i, 180) }.onSuccess { thumbnails[i] = it }
        }
    }
    Text("${pdf.file.name} · ${pdf.detail}", style = MaterialTheme.typography.bodyLarge)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot -> PageCard(slot, thumbnails[slot.index]) }
    }

    ToolInputField(
        value = range,
        onValueChange = { text ->
            range = text
            parsePages(text, document.pageCount)?.let { chosen ->
                val set = chosen.toSet()
                slots.forEach { it.selected = it.index in set }
            }
        },
        label = Res.string.page_numbers_hint.str(),
    )
    val selected = slots.count { it.selected }
    EditButtons {
        EditButton(Res.string.select_all.str(), Icons.Filled.SelectAll, selected < slots.size) { slots.forEach { it.selected = true } }
        EditButton(Res.string.select_none.str(), Icons.Filled.Deselect, selected > 0) { slots.forEach { it.selected = false } }
        EditButton(Res.string.rotate_selected.str(), Icons.Filled.Rotate90DegreesCw, selected > 0) {
            slots.filter { it.selected }.forEach { it.rotation = (it.rotation + 90) % 360 }
        }
        EditButton(Res.string.move_earlier.str(), Icons.AutoMirrored.Filled.ArrowBack, selected > 0 && !slots.first().selected) {
            for (i in 1 until slots.size) if (slots[i].selected && !slots[i - 1].selected) slots.add(i - 1, slots.removeAt(i))
        }
        EditButton(Res.string.move_later.str(), Icons.AutoMirrored.Filled.ArrowForward, selected > 0 && !slots.last().selected) {
            for (i in slots.size - 2 downTo 0) if (slots[i].selected && !slots[i + 1].selected) slots.add(i + 1, slots.removeAt(i))
        }
        EditButton(Res.string.delete_selected.str(), Icons.Filled.Delete, selected in 1 until slots.size) { slots.removeAll { it.selected } }
        EditButton(Res.string.keep_selected.str(), Icons.Filled.ContentCut, selected in 1 until slots.size) { slots.removeAll { !it.selected } }
    }

    SaveButton(Res.string.save_as_one.str()) {
        val fonts = MarkFonts(documentFonts(serif = true))
        val start = startingEdit(document)
        val edit = start.copy(pages = slots.map { start.pages[it.index].copy(turn = it.rotation) })
        saveBytes(withContext(Dispatchers.Default) { PdfEditWriter.write(document, edit, fonts) }, renamed(pdf.file.name, "pdf", "-pages"))
    }
    if (slots.size > 1) {
        SaveButton(Res.string.save_each_page.str()) {
            val fonts = MarkFonts(documentFonts(serif = true))
            val start = startingEdit(document)
            val zip = withContext(Dispatchers.Default) {
                ZipWriter().apply {
                    slots.forEachIndexed { n, slot ->
                        val edit = start.copy(pages = listOf(start.pages[slot.index].copy(turn = slot.rotation)), outline = emptyList())
                        add(renamed(pdf.file.name, "pdf", "-${n + 1}"), PdfEditWriter.write(document, edit, fonts))
                    }
                }.toByteArray()
            }
            saveBytes(zip, renamed(pdf.file.name, "zip", "-pages"))
        }
    }
}

@Composable
private fun PageCard(slot: PageSlot, thumbnail: ImageBitmap?) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(shape)
            .border(2.dp, if (slot.selected) colors.primary else Color.Transparent, shape)
            .clickable { slot.selected = !slot.selected }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp, 112.dp).background(colors.surfaceContainerHighest, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp).rotate(slot.rotation.toFloat()).scale(if (slot.rotation % 180 == 0) 1f else 0.75f))
            }
            if (slot.selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
            }
        }
        TextButton(onClick = { slot.rotation = (slot.rotation + 90) % 360 }) {
            Icon(Icons.Filled.Rotate90DegreesCw, contentDescription = Res.string.rotate_right.str(), modifier = Modifier.size(16.dp))
            Text(" ${slot.index + 1}", style = MaterialTheme.typography.labelLarge)
        }
    }
}
