package com.vasmarfas.card.tools.documents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.photoCapture
import com.vasmarfas.card.core.pixels
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.takePhoto
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.DecodedImage
import com.vasmarfas.card.tools.media.EditButton
import com.vasmarfas.card.tools.media.EditButtons
import com.vasmarfas.card.tools.media.ImageTarget
import com.vasmarfas.card.tools.media.PhotoBox
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.tools.media.decodeImage
import com.vasmarfas.card.tools.media.encodeImage
import com.vasmarfas.card.tools.media.imageExtensions
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val docScannerTool = Tool(
    id = "doc-scanner",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.doc_scanner,
    description = Res.string.doc_scanner_description,
    icon = Icons.Filled.DocumentScanner,
    keywords = listOf(
        "document scanner", "scan document", "scanner", "scan to pdf", "perspective correction", "receipt scan", "whiteboard",
        "black and white scan",
        "сканер документов", "сканировать документ", "отсканировать", "скан в pdf", "выровнять перспективу", "скан чека",
        "фото документа", "чёрно-белый скан",
    ),
) { DocScannerScreen() }

private const val PREVIEW_SIDE = 1000
private const val SKETCH_SIDE = 400
private const val SOURCE_SIDE = 3200
private const val PAGE_SIDE = 2400
private const val THUMB_SIDE = 240

private enum class ScanView { EDGES, RESULT }

@Stable
private class ScanPage(val file: PlatformFile, val preview: ImageBitmap, val sketch: DecodedImage, val found: Quad?) {
    var quad by mutableStateOf(found ?: Quad.inset(0.05f))
    var turns by mutableStateOf(0)
    val page: Quad get() = quad.turned(turns)
}

private suspend fun loadPage(file: PlatformFile): ScanPage? {
    val image = runCatching { decodeImage(file.readBytes(), PREVIEW_SIDE) }.getOrNull() ?: return null
    return withContext(Dispatchers.Default) {
        val small = image.limitedTo(SKETCH_SIDE)
        val sketch = small.raster()
        ScanPage(file, image, sketch, DocScan.findPage(sketch))
    }
}

private fun ImageBitmap.raster(): DecodedImage = DecodedImage(width, height, pixels())

private fun render(source: DecodedImage, quad: Quad, filter: ScanFilter, longSide: Int): ImageBitmap {
    val (width, height) = DocScan.pageSize(quad, source.width, source.height, longSide)
    val page = DocScan.warp(source, quad, width, height)
    DocScan.applyFilter(page, filter)
    return imageBitmapOf(page.pixels, page.width, page.height)
}

@Composable
private fun DocScannerScreen() {
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<ScanPage>() }
    var selected by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf(ScanView.EDGES) }
    var filter by rememberSaveable { mutableStateOf(ScanFilter.COLOR) }
    var format by rememberSaveable { mutableStateOf(PageFormat.A4) }
    var result by remember { mutableStateOf<PdfResult?>(null) }
    val task = remember { TaskState() }
    val unreadable = Res.string.image_not_readable.str()
    val cameraFailed = Res.string.doc_scan_camera_failed.str()

    fun add(files: List<PlatformFile>) {
        loading = true
        loadError = null
        result = null
        scope.launch {
            val failed = ArrayList<String>()
            val first = pages.size
            files.forEach { file ->
                val page = loadPage(file)
                if (page == null) {
                    failed += file.name
                } else {
                    pages += page
                    if (pages.size == first + 1) selected = first
                }
            }
            if (failed.isNotEmpty()) loadError = failed.joinToString() + ": " + unreadable
            loading = false
        }
    }

    fun shoot() {
        scope.launch {
            runCatching { takePhoto() }
                .onSuccess { photo -> if (photo != null) add(listOf(photo)) }
                .onFailure { loadError = cameraFailed }
        }
    }

    if (pages.isEmpty()) {
        if (photoCapture) ActionButton(text = Res.string.doc_scan_shoot.str(), icon = Icons.Filled.PhotoCamera, onClick = ::shoot)
        PickButton(Res.string.choose_images.str(), imageExtensions, PickKind.IMAGE, multiple = true, icon = Icons.Filled.AddPhotoAlternate, empty = true, onPicked = ::add)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photoCapture) ActionButton(text = Res.string.doc_scan_shoot.str(), icon = Icons.Filled.PhotoCamera, onClick = ::shoot)
            PickButton(Res.string.add_files.str(), imageExtensions, PickKind.IMAGE, multiple = true, icon = Icons.Filled.AddPhotoAlternate, onPicked = ::add)
        }
    }
    if (loading) LoadingRow()
    loadError?.let { ErrorText(it) }
    if (pages.isEmpty()) return
    val index = selected.coerceIn(0, pages.lastIndex)
    val page = pages[index]

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.forEachIndexed { i, scan -> PageThumb(scan, i + 1, filter, i == index) { selected = i } }
    }
    SegmentedChoice(
        options = ScanView.entries,
        selected = view,
        onSelect = { view = it },
        label = {
            when (it) {
                ScanView.EDGES -> Res.string.doc_scan_edges.str()
                ScanView.RESULT -> Res.string.result.str()
            }
        },
    )
    when (view) {
        ScanView.EDGES -> {
            QuadView(page.preview, page.quad, page.turns) { page.quad = it }
            Text(Res.string.doc_scan_hint.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page.found == null) Text(Res.string.doc_scan_not_found.str(), style = MaterialTheme.typography.bodyMedium)
        }
        ScanView.RESULT -> ResultPreview(page, filter)
    }
    EditButtons {
        EditButton(Res.string.rotate_right.str(), Icons.Filled.Rotate90DegreesCw, true) { page.turns = (page.turns + 1) % 4 }
        EditButton(Res.string.doc_scan_detect.str(), Icons.Filled.AutoFixHigh, page.found != null && page.quad != page.found) {
            page.found?.let { page.quad = it }
        }
        EditButton(Res.string.doc_scan_whole.str(), Icons.Filled.Fullscreen, page.quad != Quad.inset(0f)) { page.quad = Quad.inset(0f) }
        EditButton(Res.string.move_earlier.str(), Icons.AutoMirrored.Filled.ArrowBack, index > 0) {
            pages.add(index - 1, pages.removeAt(index))
            selected = index - 1
            result = null
        }
        EditButton(Res.string.move_later.str(), Icons.AutoMirrored.Filled.ArrowForward, index < pages.lastIndex) {
            pages.add(index + 1, pages.removeAt(index))
            selected = index + 1
            result = null
        }
        EditButton(Res.string.remove.str(), Icons.Filled.Delete, true) {
            pages.removeAt(index)
            selected = (index - 1).coerceAtLeast(0)
            result = null
        }
    }

    ToolSection(Res.string.doc_scan_look.str()) {
        SegmentedChoice(
            options = ScanFilter.entries,
            selected = filter,
            onSelect = { filter = it },
            label = {
                when (it) {
                    ScanFilter.COLOR -> Res.string.doc_scan_color.str()
                    ScanFilter.GRAY -> Res.string.doc_scan_gray.str()
                    ScanFilter.BLACK_WHITE -> Res.string.doc_scan_bw.str()
                }
            },
        )
    }
    ToolSection(Res.string.page_size.str()) {
        ChoiceChips(
            options = PageFormat.entries,
            selected = format,
            onSelect = { format = it },
            label = { if (it == PageFormat.LETTER) "Letter" else it.name },
        )
    }
    ActionButton(
        text = Res.string.make_pdf.str(),
        icon = Icons.Filled.PictureAsPdf,
        enabled = !task.running && !loading,
        onClick = {
            result = null
            val sources = pages.map { it.file to it.page }
            val look = filter
            val paper = format
            task.launch(scope) { progress ->
                val images = sources.mapIndexed { i, (file, quad) ->
                    val image = decodeImage(file.readBytes(), SOURCE_SIDE) ?: throw IllegalStateException("${file.name}: $unreadable")
                    val scan = withContext(Dispatchers.Default) {
                        val bitmap = render(image.raster(), quad, look, PAGE_SIDE)
                        PreparedImage(encodeImage(bitmap, ImageTarget.JPEG, 85), bitmap.width, bitmap.height)
                    }
                    progress((i + 1f) / (sources.size + 1))
                    scan
                }
                val bytes = withContext(Dispatchers.Default) { ImagePdf.build(images, paper, 0f) }
                result = PdfResult("scan.pdf", bytes, images.size)
            }
        },
    )
    TaskProgress(task, Res.string.rendering.str())
    result?.let { PdfResultCard(it) }
}

@Composable
private fun PageThumb(page: ScanPage, number: Int, filter: ScanFilter, selected: Boolean, onClick: () -> Unit) {
    val quad = page.page
    val thumb by produceState<ImageBitmap?>(null, page, quad, filter) {
        value = withContext(Dispatchers.Default) { render(page.sketch, quad, filter, THUMB_SIDE) }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(84.dp)
            .clip(shape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text("$number", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ResultPreview(page: ScanPage, filter: ScanFilter) {
    val quad = page.page
    val source = remember(page) { page.preview.raster() }
    val scan by produceState<ImageBitmap?>(null, source, quad, filter) {
        value = withContext(Dispatchers.Default) { render(source, quad, filter, PREVIEW_SIDE) }
    }
    val image = scan
    if (image == null) {
        LoadingRow()
        return
    }
    PhotoBox(image) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun QuadView(image: ImageBitmap, quad: Quad, turns: Int, onChange: (Quad) -> Unit) {
    val touch = with(LocalDensity.current) { 36.dp.toPx() }
    val current by rememberUpdatedState(quad)
    val change by rememberUpdatedState(onChange)
    var active by remember { mutableStateOf(-1) }
    val accent = MaterialTheme.colorScheme.primary
    PhotoBox(image) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val corners = current.scaled(size.width.toFloat(), size.height.toFloat()).corners
                    val nearest = corners.indices.minBy { (corners[it] - down.position).getDistance() }
                    if ((corners[nearest] - down.position).getDistance() > touch) return@awaitEachGesture
                    down.consume()
                    val start = current.corners[nearest]
                    active = nearest
                    drag(down.id) { pointer ->
                        pointer.consume()
                        val shift = pointer.position - down.position
                        val to = Offset((start.x + shift.x / size.width).coerceIn(0f, 1f), (start.y + shift.y / size.height).coerceIn(0f, 1f))
                        val moved = current.moved(nearest, to)
                        if (moved.convex) change(moved)
                    }
                    active = -1
                }
            },
        ) {
            val q = quad.scaled(size.width, size.height)
            val outline = Path().apply {
                moveTo(q.topLeft.x, q.topLeft.y)
                lineTo(q.topRight.x, q.topRight.y)
                lineTo(q.bottomRight.x, q.bottomRight.y)
                lineTo(q.bottomLeft.x, q.bottomLeft.y)
                close()
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addPath(outline)
            }
            drawPath(shade, Color.Black.copy(alpha = 0.45f))
            drawPath(outline, Color.Black.copy(alpha = 0.4f), style = Stroke(3.5.dp.toPx()))
            drawPath(outline, Color.White, style = Stroke(1.5.dp.toPx()))
            val top = quad.turned(turns).scaled(size.width, size.height)
            drawLine(accent, top.topLeft, top.topRight, 4.dp.toPx(), cap = StrokeCap.Round)
            q.corners.forEachIndexed { i, corner ->
                drawCircle(Color.Black.copy(alpha = 0.4f), 11.dp.toPx(), corner)
                drawCircle(if (i == active) accent else Color.White, 9.dp.toPx(), corner)
            }
            if (active >= 0) loupe(image, outline, q.corners[active])
        }
    }
}

private fun DrawScope.loupe(image: ImageBitmap, outline: Path, at: Offset) {
    val radius = 48.dp.toPx()
    val gap = 12.dp.toPx()
    val zoom = 2.5f
    val center = Offset(if (at.x < size.width / 2 && at.y < size.height / 2) size.width - radius - gap else radius + gap, radius + gap)
    clipPath(Path().apply { addOval(Rect(center, radius)) }) {
        drawRect(Color.Black, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        withTransform({
            translate(center.x - at.x * zoom, center.y - at.y * zoom)
            scale(zoom, zoom, Offset.Zero)
        }) {
            drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            drawPath(outline, Color.White, style = Stroke(1.5.dp.toPx() / zoom))
        }
    }
    drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
    drawLine(Color.White, center - Offset(8.dp.toPx(), 0f), center + Offset(8.dp.toPx(), 0f), 1.dp.toPx())
    drawLine(Color.White, center - Offset(0f, 8.dp.toPx()), center + Offset(0f, 8.dp.toPx()), 1.dp.toPx())
}
