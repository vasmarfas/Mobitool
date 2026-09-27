package com.vasmarfas.card.tools.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.expandedHeight
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val photoEditorTool = Tool(
    id = "photo-editor",
    category = ToolCategory.MEDIA,
    title = Res.string.photo_editor,
    description = Res.string.photo_editor_description,
    icon = Icons.Filled.Tune,
    keywords = listOf(
        "photo editor", "image editor", "crop", "rotate", "flip", "mirror", "straighten", "filter", "brightness", "contrast", "saturation", "vignette",
        "фоторедактор", "редактор фото", "обрезать фото", "кадрировать", "повернуть фото", "отразить", "выровнять горизонт", "фильтры", "яркость",
        "контраст", "насыщенность", "виньетка",
    ),
    expandable = true,
) { PhotoEditorScreen() }

private const val PREVIEW_SIDE = 1280
private const val MIN_CROP = 0.04f

private enum class EditMode { CROP, ADJUST, FILTERS }

// zero width is a free crop, a negative one keeps the photo's proportions
private data class CropAspect(val width: Int, val height: Int) {
    fun ratio(frame: ImageBitmap): Float? = when {
        width == 0 -> null
        width < 0 -> 1f
        else -> width.toFloat() / height * frame.height / frame.width
    }

    fun turned(): CropAspect = if (width > 0) CropAspect(height, width) else this
}

private val freeAspect = CropAspect(0, 0)
private val aspects = listOf(
    freeAspect, CropAspect(-1, -1), CropAspect(1, 1), CropAspect(4, 3), CropAspect(3, 4), CropAspect(3, 2), CropAspect(16, 9), CropAspect(9, 16), CropAspect(4, 5),
)

@Stable
private class PhotoSession(val source: ImageBitmap, val name: String) {
    val preview: ImageBitmap = source.limitedTo(PREVIEW_SIDE)
    var edit by mutableStateOf(PhotoEdit())
        private set
    var aspect by mutableStateOf(freeAspect)
    var undoDepth by mutableStateOf(0)
        private set
    var redoDepth by mutableStateOf(0)
        private set
    private var committed = PhotoEdit()
    private val undo = ArrayDeque<PhotoEdit>()
    private val redo = ArrayDeque<PhotoEdit>()

    fun show(next: PhotoEdit) {
        edit = next
    }

    fun commit(next: PhotoEdit = edit) {
        edit = next
        if (next == committed) return
        undo.addLast(committed)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        committed = next
        sync()
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(committed)
        committed = previous
        edit = previous
        sync()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(committed)
        committed = next
        edit = next
        sync()
    }

    private fun sync() {
        undoDepth = undo.size
        redoDepth = redo.size
    }
}

@Composable
private fun PhotoEditorScreen() {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PhotoSession?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(EditMode.CROP) }
    var target by rememberSaveable { mutableStateOf(ImageTarget.JPEG) }
    var quality by rememberSaveable { mutableStateOf(90) }
    var longSide by rememberSaveable { mutableStateOf(0) }
    val unreadable = Res.string.image_not_readable.str()

    PickButton(Res.string.open_photo.str(), imageExtensions, PickKind.IMAGE, icon = Icons.Filled.AddPhotoAlternate, empty = session == null) { files ->
        val file = files.first()
        loading = true
        loadError = null
        scope.launch {
            val image = runCatching { decodeImage(file.readBytes()) }.getOrNull()
            if (image == null) loadError = unreadable else session = withContext(Dispatchers.Default) { PhotoSession(image, file.name) }
            loading = false
        }
    }
    if (loading) LoadingRow()
    loadError?.let { ErrorText(it) }
    val photo = session ?: return
    val edit = photo.edit
    val frame = remember(photo, edit.quarterTurns, edit.mirror, edit.straighten) { PhotoEditor.framed(photo.preview, edit) }

    SegmentedChoice(
        options = EditMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                EditMode.CROP -> Res.string.crop_mode.str()
                EditMode.ADJUST -> Res.string.adjust_mode.str()
                EditMode.FILTERS -> Res.string.filters.str()
            }
        },
    )
    when (mode) {
        EditMode.CROP -> CropPanel(photo, frame)
        EditMode.ADJUST -> {
            Adjusted(frame, edit)
            AdjustPanel(photo)
        }
        EditMode.FILTERS -> {
            Adjusted(frame, edit)
            FilterPanel(photo, frame)
        }
    }
    EditButtons {
        EditButton(Res.string.undo.str(), Icons.AutoMirrored.Filled.Undo, photo.undoDepth > 0, photo::undo)
        EditButton(Res.string.redo.str(), Icons.AutoMirrored.Filled.Redo, photo.redoDepth > 0, photo::redo)
        EditButton(Res.string.reset_all.str(), Icons.Filled.RestartAlt, edit != PhotoEdit()) {
            photo.aspect = freeAspect
            photo.commit(PhotoEdit())
        }
    }

    val targets = listOf(ImageTarget.JPEG, ImageTarget.PNG, ImageTarget.WEBP)
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = targets, selected = target, onSelect = { target = it }, label = { it.title })
        if (target.lossy) {
            LabeledSlider(Res.string.image_quality.str(), "$quality", quality.toFloat(), 40f..100f) { quality = it.roundToInt() }
        }
    }
    val turned = edit.quarterTurns % 2 == 1
    val fullWidth = (edit.crop.width * if (turned) photo.source.height else photo.source.width).roundToInt()
    val fullHeight = (edit.crop.height * if (turned) photo.source.width else photo.source.height).roundToInt()
    ToolSection(Res.string.long_side.str()) {
        ChoiceChips(
            options = listOf(0, 4096, 2560, 1920, 1280, 1080).filter { it < max(fullWidth, fullHeight) },
            selected = longSide,
            onSelect = { longSide = it },
            label = { if (it == 0) Res.string.as_source.str() else "$it px" },
        )
        val scale = if (longSide in 1 until max(fullWidth, fullHeight)) longSide.toDouble() / max(fullWidth, fullHeight) else 1.0
        Text("${(fullWidth * scale).roundToInt()} × ${(fullHeight * scale).roundToInt()} px", style = MaterialTheme.typography.bodyMedium)
    }
    SaveButton {
        val bytes = withContext(Dispatchers.Default) {
            encodeImage(PhotoEditor.render(photo.source, edit).limitedTo(longSide), target, quality)
        }
        saveBytes(bytes, renamed(photo.name, target.extension, "-edit"))
    }
}

@Composable
private fun CropPanel(photo: PhotoSession, frame: ImageBitmap) {
    val edit = photo.edit
    CropView(
        image = frame,
        crop = edit.crop,
        ratio = photo.aspect.ratio(frame),
        onChange = { photo.show(edit.copy(crop = it)) },
        onDone = { photo.commit() },
    )
    ChoiceChips(
        options = aspects,
        selected = photo.aspect,
        onSelect = { aspect ->
            photo.aspect = aspect
            aspect.ratio(frame)?.let { photo.commit(edit.copy(crop = PhotoEditor.fitCrop(it))) }
        },
        label = {
            when {
                it.width == 0 -> Res.string.crop_free.str()
                it.width < 0 -> Res.string.crop_original.str()
                else -> "${it.width}:${it.height}"
            }
        },
    )
    EditButtons {
        EditButton(Res.string.rotate_left.str(), Icons.Filled.Rotate90DegreesCcw, true) {
            photo.aspect = photo.aspect.turned()
            photo.commit(edit.turned(clockwise = false))
        }
        EditButton(Res.string.rotate_right.str(), Icons.Filled.Rotate90DegreesCw, true) {
            photo.aspect = photo.aspect.turned()
            photo.commit(edit.turned(clockwise = true))
        }
        EditButton(Res.string.mirror.str(), Icons.Filled.Flip, true) { photo.commit(edit.flipped()) }
    }
    LabeledSlider(
        Res.string.straighten.str(),
        edit.straighten.toDouble().fmt(1) + "°",
        edit.straighten,
        -45f..45f,
        onFinished = { photo.commit() },
    ) { photo.show(edit.copy(straighten = (it * 2).roundToInt() / 2f)) }
}

@Composable
private fun PhotoBox(image: ImageBitmap, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.heightIn(max = expandedHeight(420.dp, 460.dp)).aspectRatio(image.width.toFloat() / image.height),
            content = content,
        )
    }
}

@Composable
internal fun CropView(
    image: ImageBitmap,
    crop: Rect,
    ratio: Float?,
    onChange: (Rect) -> Unit,
    onDone: () -> Unit,
    overlay: (DrawScope.(Rect) -> Unit)? = null,
) {
    val touch = with(LocalDensity.current) { 28.dp.toPx() }
    val current by rememberUpdatedState(crop)
    val lock by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    PhotoBox(image) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(image) {
                var handle = CropHandle.MOVE
                var start = current
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { at ->
                        start = current
                        total = Offset.Zero
                        handle = handleAt(start, at, size, touch)
                    },
                    onDragEnd = { done() },
                    onDragCancel = { done() },
                    onDrag = { pointer, drag ->
                        pointer.consume()
                        total += drag
                        change(PhotoEditor.dragCrop(start, handle, total.x / size.width, total.y / size.height, lock, MIN_CROP))
                    },
                )
            },
        ) {
            val r = Rect(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height)
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset.Zero, Size(size.width, r.top))
            drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
            drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
            if (overlay != null) {
                overlay(r)
            } else {
                val grid = Color.White.copy(alpha = 0.5f)
                for (i in 1..2) {
                    val x = r.left + r.width * i / 3
                    val y = r.top + r.height * i / 3
                    drawLine(grid, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(grid, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
            }
            val arm = min(20.dp.toPx(), min(r.width, r.height) / 2)
            val stroke = 4.dp.toPx()
            val corners = listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f))
            val outline = Color.Black.copy(alpha = 0.4f)
            drawRect(outline, r.topLeft, r.size, style = Stroke(3.5.dp.toPx()))
            corners.forEach { (corner, inward) ->
                drawLine(outline, corner, corner + Offset(arm * inward.x, 0f), stroke + 2.dp.toPx())
                drawLine(outline, corner, corner + Offset(0f, arm * inward.y), stroke + 2.dp.toPx())
            }
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
            corners.forEach { (corner, inward) ->
                drawLine(Color.White, corner, corner + Offset(arm * inward.x, 0f), stroke)
                drawLine(Color.White, corner, corner + Offset(0f, arm * inward.y), stroke)
            }
        }
    }
}

private fun handleAt(crop: Rect, at: Offset, size: IntSize, touch: Float): CropHandle {
    val l = crop.left * size.width
    val r = crop.right * size.width
    val t = crop.top * size.height
    val b = crop.bottom * size.height
    val nearLeft = abs(at.x - l) < touch && abs(at.x - l) <= abs(at.x - r)
    val nearRight = abs(at.x - r) < touch && !nearLeft
    val nearTop = abs(at.y - t) < touch && abs(at.y - t) <= abs(at.y - b)
    val nearBottom = abs(at.y - b) < touch && !nearTop
    val alongX = at.x > l - touch && at.x < r + touch
    val alongY = at.y > t - touch && at.y < b + touch
    return when {
        nearLeft && nearTop -> CropHandle.TOP_LEFT
        nearRight && nearTop -> CropHandle.TOP_RIGHT
        nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
        nearLeft && alongY -> CropHandle.LEFT
        nearRight && alongY -> CropHandle.RIGHT
        nearTop && alongX -> CropHandle.TOP
        nearBottom && alongX -> CropHandle.BOTTOM
        else -> CropHandle.MOVE
    }
}

@Composable
private fun Adjusted(frame: ImageBitmap, edit: PhotoEdit) {
    val image = remember(frame, edit.crop) { PhotoEditor.cut(frame, edit.crop) }
    val matrix = PhotoEditor.colorMatrix(edit)
    PhotoBox(image) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = matrix?.let(ColorFilter::colorMatrix),
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (edit.vignette > 0f) drawRect(PhotoEditor.vignetteBrush(size, edit.vignette))
            },
        )
    }
}

@Composable
private fun AdjustPanel(photo: PhotoSession) {
    val edit = photo.edit
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AdjustSlider(photo, Res.string.exposure.str(), edit.exposure, -2f..2f, signed(edit.exposure.toDouble().fmt(1)) + " EV") { edit.copy(exposure = it) }
        AdjustSlider(photo, Res.string.brightness.str(), edit.brightness, -1f..1f, percent(edit.brightness)) { edit.copy(brightness = it) }
        AdjustSlider(photo, Res.string.contrast.str(), edit.contrast, -1f..1f, percent(edit.contrast)) { edit.copy(contrast = it) }
        AdjustSlider(photo, Res.string.saturation.str(), edit.saturation, -1f..1f, percent(edit.saturation)) { edit.copy(saturation = it) }
        AdjustSlider(photo, Res.string.warmth.str(), edit.warmth, -1f..1f, percent(edit.warmth)) { edit.copy(warmth = it) }
        AdjustSlider(photo, Res.string.tint.str(), edit.tint, -1f..1f, percent(edit.tint)) { edit.copy(tint = it) }
        AdjustSlider(photo, Res.string.vignette.str(), edit.vignette, 0f..1f, percent(edit.vignette)) { edit.copy(vignette = it) }
    }
}

private fun signed(text: String): String = if (text.startsWith("-") || text == "0") text else "+$text"

private fun percent(value: Float): String = signed((value * 100).roundToInt().toString())

@Composable
private fun AdjustSlider(photo: PhotoSession, label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, update: (Float) -> PhotoEdit) {
    LabeledSlider(label, text, value, range, onFinished = { photo.commit() }) { photo.show(update(if (abs(it) < 0.02f) 0f else it)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(photo: PhotoSession, frame: ImageBitmap) {
    val edit = photo.edit
    val thumbnail = remember(frame, edit.crop) { PhotoEditor.cut(frame, edit.crop).limitedTo(200) }
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoFilter.entries.forEach { filter ->
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .width(84.dp)
                    .clip(shape)
                    .border(2.dp, if (filter == edit.filter) colors.primary else Color.Transparent, shape)
                    .clickable { photo.commit(edit.copy(filter = filter)) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = PhotoEditor.colorMatrix(edit.copy(filter = filter))?.let(ColorFilter::colorMatrix),
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
                Text(filterLabel(filter), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun filterLabel(filter: PhotoFilter): String = when (filter) {
    PhotoFilter.NONE -> Res.string.filter_original.str()
    PhotoFilter.MONO -> Res.string.filter_mono.str()
    PhotoFilter.NOIR -> Res.string.filter_noir.str()
    PhotoFilter.SEPIA -> Res.string.filter_sepia.str()
    PhotoFilter.VINTAGE -> Res.string.filter_vintage.str()
    PhotoFilter.WARM -> Res.string.filter_warm.str()
    PhotoFilter.COOL -> Res.string.filter_cool.str()
    PhotoFilter.VIVID -> Res.string.filter_vivid.str()
    PhotoFilter.MATTE -> Res.string.filter_matte.str()
    PhotoFilter.INVERT -> Res.string.filter_invert.str()
}
