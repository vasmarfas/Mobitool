package com.vasmarfas.card.tools.documents.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.core.PdfRaster
import com.vasmarfas.card.tools.documents.pdf.PageContent
import com.vasmarfas.card.tools.documents.pdf.PageGraphic
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class EditTool(val captures: Boolean) {
    VIEW(false),
    SELECT(false),
    TEXT(false),
    PEN(true),
    MARKER(true),
    SHAPE(true),
    SIGN(false),
    IMAGE(false),
    STAMP(false),
    NOTE(false),
    TEXT_SELECT(true),
    WHITEOUT(true),
    REDACT(true),
    ERASER(true),
    CROP(true),
    FORM(false),
}

internal enum class Stamp { APPROVED, PAID, COPY, DRAFT, CONFIDENTIAL, REJECTED, DATE, CHECK, CROSS, DOT }

internal data class ToolStyle(
    val penColor: Int = 0xFF1565C0.toInt(),
    val penWidth: Float = 2f,
    val markerColor: Int = 0x99FFEB3B.toInt(),
    val markerWidth: Float = 14f,
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    val shapeColor: Int = 0xFFD32F2F.toInt(),
    val shapeWidth: Float = 2f,
    val shapeFill: Boolean = false,
    val textColor: Int = 0xFF000000.toInt(),
    val textSize: Float = 12f,
    val textFont: MarkFont = MarkFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: MarkAlign = MarkAlign.START,
    val stamp: Stamp = Stamp.APPROVED,
    val stampColor: Int = 0xFF2E7D32.toInt(),
    val coverColor: Int = 0xFFFFFFFF.toInt(),
    val signatureColor: Int = 0xFF0D2A6B.toInt(),
    val signature: Int = 0,
)

@Stable
internal class EditorSession(
    val name: String,
    val main: PdfDocument?,
    val fonts: MarkFonts,
    initial: DocumentEdit,
    val fields: List<FormField>,
    val signed: Boolean,
) {
    var edit by mutableStateOf(initial)
        private set
    var current by mutableStateOf(0)
    var tool by mutableStateOf(EditTool.SELECT)
    var style by mutableStateOf(ToolStyle())
    var selected by mutableStateOf<Long?>(null)
    var editing by mutableStateOf<Long?>(null)
    var preview by mutableStateOf<Mark?>(null)
    var undoDepth by mutableStateOf(0)
        private set
    var redoDepth by mutableStateOf(0)
        private set
    var pendingImage by mutableStateOf<PendingImage?>(null)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchHit>>(emptyList())
    var hit by mutableStateOf(0)
    var picked by mutableStateOf<Picked?>(null)
    val images = HashMap<MarkImage, ImageBitmap>()
    val texts = mutableStateMapOf<Pair<PageKey, Map<Int, ObjectEdit>>, PageText>()
    val objects = mutableStateMapOf<PageKey, PageObjects>()
    private val edited = LinkedHashMap<Pair<SourcePage, Map<Int, ObjectEdit>>, PdfRaster>()
    private val undo = ArrayDeque<DocumentEdit>()
    private val redo = ArrayDeque<DocumentEdit>()
    private val sources = LinkedHashMap<PdfDocument, ByteArray>()
    private val rasters = HashMap<PdfDocument, PdfRaster>()
    private val rasterLock = Mutex()
    private var nextId = (initial.pages.maxOfOrNull { it.id } ?: 0L) + 1

    val page: EditPage get() = edit.pages[current.coerceIn(0, edit.pages.lastIndex)]

    val changed: Boolean get() = undoDepth > 0

    fun id(): Long = nextId++

    fun addSource(document: PdfDocument, bytes: ByteArray) {
        sources[document] = bytes
    }

    fun commit(next: DocumentEdit) {
        typing = null
        preview = null
        if (next == edit) return
        undo.addLast(edit)
        if (undo.size > 150) undo.removeFirst()
        redo.clear()
        edit = next
        sync()
    }

    fun type(key: String, next: DocumentEdit) {
        if (typing != key || undo.isEmpty()) {
            commit(next)
            typing = key
            return
        }
        redo.clear()
        edit = next
        sync()
    }

    private var typing: String? = null

    val thumbs = mutableStateMapOf<Pair<PageSource, Map<Int, ObjectEdit>>, ImageBitmap>()

    fun revert(to: DocumentEdit) {
        if (undo.lastOrNull() !== to) return
        undo.removeLast()
        edit = to
        settle()
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(edit)
        edit = previous
        settle()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(edit)
        edit = next
        settle()
    }

    private fun settle() {
        preview = null
        editing = null
        picked = null
        if (selected != null && edit.pages.none { page -> page.marks.any { it.id == selected } }) selected = null
        current = current.coerceIn(0, edit.pages.lastIndex)
        sync()
    }

    private fun sync() {
        undoDepth = undo.size
        redoDepth = redo.size
    }

    fun pages(change: (List<EditPage>) -> List<EditPage>) {
        val next = change(edit.pages)
        if (next.isEmpty()) return
        val id = page.id
        commit(edit.copy(pages = next))
        current = next.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: current.coerceIn(0, next.lastIndex)
    }

    fun updatePage(id: Long, change: (EditPage) -> EditPage) = commit(edit.copy(pages = edit.pages.map { if (it.id == id) change(it) else it }))

    fun add(mark: Mark, select: Boolean = false) {
        updatePage(page.id) { it.copy(marks = it.marks + mark) }
        if (select) selected = mark.id
    }

    fun replace(mark: Mark) {
        val owner = edit.pages.firstOrNull { page -> page.marks.any { it.id == mark.id } } ?: return
        updatePage(owner.id) { page -> page.copy(marks = page.marks.map { if (it.id == mark.id) mark else it }) }
    }

    fun remove(id: Long) {
        val owner = edit.pages.firstOrNull { page -> page.marks.any { it.id == id } } ?: return
        updatePage(owner.id) { page -> page.copy(marks = page.marks.filterNot { it.id == id }) }
        if (selected == id) selected = null
        if (editing == id) editing = null
    }

    fun removeObjects(pageId: Long, keys: Set<Int>) {
        updatePage(pageId) { page -> page.copy(objects = page.objects + keys.associateWith { ObjectEdit(removed = true) }) }
        picked = null
    }

    fun transformObjects(pageId: Long, keys: Set<Int>, t: Affine) {
        updatePage(pageId) { page ->
            page.copy(objects = page.objects + keys.associateWith { key -> ObjectEdit(transform = page.objects[key]?.transform?.then(t) ?: t) })
        }
    }

    fun setField(field: FormField, value: FieldValue) {
        val next = edit.copy(fields = edit.fields + (field.name to value))
        if (value is FieldValue.Text) type("field:${field.name}", next) else commit(next)
    }

    fun value(field: FormField): FieldValue? = edit.fields[field.name] ?: field.value

    fun mark(id: Long?): Mark? = if (id == null) null else page.marks.firstOrNull { it.id == id }

    fun marks(page: EditPage): List<Mark> {
        val live = preview ?: return page.marks
        return page.marks.map { if (it.id == live.id) live else it }
    }

    suspend fun render(source: SourcePage, width: Int, objects: Map<Int, ObjectEdit> = emptyMap()): ImageBitmap? {
        val (raster, index) = raster(source, objects) ?: return null
        return runCatching { raster.render(index, width) }.getOrNull()
    }

    suspend fun render(source: SourcePage, objects: Map<Int, ObjectEdit>, pageWidth: Int, x: Int, y: Int, w: Int, h: Int): ImageBitmap? {
        val (raster, index) = raster(source, objects) ?: return null
        return runCatching { raster.render(index, pageWidth, x, y, w, h) }.getOrNull()
    }

    suspend fun renderFlattened(source: SourcePage, width: Int, objects: Map<Int, ObjectEdit>): ImageBitmap? {
        val document = source.page.document
        val onePage = DocumentEdit(listOf(EditPage(1, source, objects = objects)), fields = if (document === main) edit.fields else emptyMap())
        val options = SaveOptions(flattenForm = true, flattenAnnotations = true)
        val bytes = runCatching { withContext(Dispatchers.Default) { PdfEditWriter.write(document, onePage, fonts, options) } }.getOrNull() ?: return null
        val raster = runCatching { PdfRaster.open(bytes) }.getOrNull() ?: return null
        return try {
            runCatching { raster.render(0, width) }.getOrNull()
        } finally {
            raster.close()
        }
    }

    private suspend fun raster(source: SourcePage, objects: Map<Int, ObjectEdit>): Pair<PdfRaster, Int>? = rasterLock.withLock {
        val document = source.page.document
        if (objects.isEmpty()) {
            val raster = rasters[document] ?: sources[document]?.let { bytes -> runCatching { PdfRaster.open(bytes) }.getOrNull()?.also { rasters[document] = it } }
            return@withLock raster?.let { it to source.page.index }
        }
        val key = source to objects
        edited[key]?.let { return@withLock it to 0 }
        val bytes = runCatching { withContext(Dispatchers.Default) { rewritten(source, objects) } }.getOrNull()
        val raster = bytes?.let { runCatching { PdfRaster.open(it) }.getOrNull() } ?: return@withLock null
        edited[key] = raster
        if (edited.size > 6) edited.remove(edited.keys.first())?.close()
        raster to 0
    }

    suspend fun objects(source: SourcePage): PageObjects {
        val key = PageKey(source.page.document, source.page.index)
        objects[key]?.let { return it }
        val found = withContext(Dispatchers.Default) {
            val content = runCatching { PageContent.of(source.page) }.getOrNull()
            PageObjects(content?.let { TextBlocks.build(it.runs, it.graphics) }.orEmpty(), content?.graphics.orEmpty())
        }
        objects[key] = found
        return found
    }

    private fun rewritten(source: SourcePage, objects: Map<Int, ObjectEdit>) = PdfEditWriter.write(null, DocumentEdit(listOf(EditPage(1, source, objects = objects))), fonts)

    suspend fun text(source: SourcePage, objects: Map<Int, ObjectEdit>): PageText {
        val page = PageKey(source.page.document, source.page.index)
        texts[page to objects]?.let { return it }
        val text = withContext(Dispatchers.Default) {
            val glyphs = runCatching {
                if (objects.isEmpty()) PdfText.glyphs(source.page.document, source.page.index) else PdfText.glyphs(PdfDocument.parse(rewritten(source, objects)), 0)
            }
            PageText(glyphs.getOrDefault(emptyList()))
        }
        if (objects.isNotEmpty()) texts.keys.filter { it.first == page && it.second.isNotEmpty() }.forEach { texts.remove(it) }
        texts[page to objects] = text
        return text
    }

    fun close() {
        for (raster in rasters.values + edited.values) raster.close()
        rasters.clear()
        edited.clear()
    }
}

internal data class PageKey(val document: PdfDocument, val index: Int)

internal class PageObjects(val blocks: List<TextBlock>, val graphics: List<PageGraphic>)

internal sealed interface Picked {
    val pageId: Long
    val keys: Set<Int>
    val bounds: PdfRect
}

internal class PickedText(override val pageId: Long, val block: TextBlock) : Picked {
    override val keys: Set<Int> get() = block.ops
    override val bounds: PdfRect get() = block.bounds
}

internal class PickedGraphic(override val pageId: Long, val graphic: PageGraphic) : Picked {
    override val keys: Set<Int> get() = setOf(graphic.first)
    override val bounds: PdfRect get() = graphic.bounds
}

internal class SearchHit(val pageId: Long, val match: TextMatch)

internal class PendingImage(val image: MarkImage, val bitmap: ImageBitmap)

internal fun pdfDate(millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "D:${t.year}${two(t.month.number)}${two(t.day)}${two(t.hour)}${two(t.minute)}${two(t.second)}Z"
}

internal fun EditPage.placeAround(u: Double, v: Double, width: Double, height: Double): PdfRect {
    val frame = frame
    val left = (u - width / 2).coerceIn(0.0, (frame.width - width).coerceAtLeast(0.0))
    val top = (v - height / 2).coerceIn(0.0, (frame.height - height).coerceAtLeast(0.0))
    return frame.toUser(left, top, left + width, top + height)
}
