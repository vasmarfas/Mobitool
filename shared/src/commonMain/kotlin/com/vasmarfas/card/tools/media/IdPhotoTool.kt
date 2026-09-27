package com.vasmarfas.card.tools.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.scaled
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

val idPhotoTool = Tool(
    id = "id-photo",
    category = ToolCategory.MEDIA,
    title = Res.string.id_photo,
    description = Res.string.id_photo_description,
    icon = Icons.Filled.AssignmentInd,
    keywords = listOf(
        "id photo", "passport photo", "visa photo", "photo 35x45", "2x2 photo", "photo for documents", "print sheet 10x15",
        "фото на документы", "фото на паспорт", "фото 3х4", "фото 3 на 4", "фото на визу", "фото 35 на 45", "фото для документов",
    ),
) { IdPhotoScreen() }

internal enum class IdFormat(
    val width: Float,
    val height: Float,
    val crown: Float,
    val chin: Float,
    val tag: String,
    val label: StringResource,
    val hint: StringResource,
) {
    PASSPORT(35f, 45f, 5f, 39f, "35x45", Res.string.id_photo_35x45, Res.string.id_photo_35x45_hint),
    SMALL(30f, 40f, 4.5f, 30.5f, "30x40", Res.string.id_photo_30x40, Res.string.id_photo_30x40_hint),
    US(50.8f, 50.8f, 5f, 36f, "2x2in", Res.string.id_photo_2x2, Res.string.id_photo_2x2_hint),
    CHINA(33f, 48f, 4f, 34.5f, "33x48", Res.string.id_photo_33x48, Res.string.id_photo_33x48_hint),
}

internal const val SHEET_WIDTH = 101.6f
internal const val SHEET_HEIGHT = 152.4f
private const val SHEET_MARGIN = 2f
private const val SHEET_GAP = 2f
internal const val PHOTO_DPI = 600
internal const val SHEET_DPI = 300

internal fun millimetres(mm: Float, dpi: Int): Int = (mm / 25.4f * dpi).roundToInt()

internal fun sheetGrid(format: IdFormat): Pair<Int, Int> {
    fun fits(space: Float, size: Float) = ((space - 2 * SHEET_MARGIN + SHEET_GAP) / (size + SHEET_GAP)).toInt()
    return fits(SHEET_WIDTH, format.width) to fits(SHEET_HEIGHT, format.height)
}

internal fun idPhoto(image: ImageBitmap, crop: Rect, format: IdFormat): ImageBitmap =
    PhotoEditor.cut(image, crop).scaled(millimetres(format.width, PHOTO_DPI), millimetres(format.height, PHOTO_DPI))

internal fun printSheet(photo: ImageBitmap, format: IdFormat): ImageBitmap {
    val (columns, rows) = sheetGrid(format)
    val sheet = ImageBitmap(millimetres(SHEET_WIDTH, SHEET_DPI), millimetres(SHEET_HEIGHT, SHEET_DPI))
    val width = millimetres(format.width, SHEET_DPI)
    val height = millimetres(format.height, SHEET_DPI)
    val gap = millimetres(SHEET_GAP, SHEET_DPI)
    val cell = photo.scaled(width, height)
    val left = (sheet.width - columns * width - (columns - 1) * gap) / 2
    val top = (sheet.height - rows * height - (rows - 1) * gap) / 2
    val cut = Paint().apply {
        color = Color(0xFFB0B0B0)
        style = PaintingStyle.Stroke
    }
    Canvas(sheet).apply {
        drawRect(0f, 0f, sheet.width.toFloat(), sheet.height.toFloat(), Paint().apply { color = Color.White })
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = (left + column * (width + gap)).toFloat()
                val y = (top + row * (height + gap)).toFloat()
                drawImage(cell, Offset(x, y), Paint())
                drawRect(x - 1, y - 1, x + width, y + height, cut)
            }
        }
    }
    return sheet
}

internal fun ByteArray.withDpi(dpi: Int): ByteArray {
    if (size < 18 || this[2] != 0xFF.toByte() || this[3] != 0xE0.toByte() || decodeToString(6, 10) != "JFIF") return this
    return copyOf().also {
        it[13] = 1
        it[14] = (dpi shr 8).toByte()
        it[15] = dpi.toByte()
        it[16] = (dpi shr 8).toByte()
        it[17] = dpi.toByte()
    }
}

private class Loaded(val image: ImageBitmap, val preview: ImageBitmap, val name: String)

@Composable
private fun IdPhotoScreen() {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf(IdFormat.PASSPORT) }
    var crop by remember { mutableStateOf<Rect?>(null) }
    val unreadable = Res.string.image_not_readable.str()

    PickButton(Res.string.open_photo.str(), imageExtensions, PickKind.IMAGE, icon = Icons.Filled.AddPhotoAlternate, empty = loaded == null) { files ->
        val file = files.first()
        loading = true
        loadError = null
        scope.launch {
            val image = runCatching { decodeImage(file.readBytes()) }.getOrNull()
            if (image == null) {
                loadError = unreadable
            } else {
                loaded = Loaded(image, withContext(Dispatchers.Default) { image.limitedTo(1600) }, file.name)
                crop = null
            }
            loading = false
        }
    }
    if (loading) LoadingRow()
    loadError?.let { ErrorText(it) }
    val photo = loaded ?: return

    ChoiceChips(options = IdFormat.entries, selected = format, onSelect = { format = it; crop = null }, label = { it.label.str() })
    Text(format.hint.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ratio = format.width / format.height * photo.image.height / photo.image.width
    val frame = crop ?: PhotoEditor.fitCrop(ratio)
    CropView(photo.preview, frame, ratio, onChange = { crop = it }, onDone = {}, overlay = { headGuide(it, format) })
    Text(Res.string.id_photo_guide.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SaveButton(Res.string.id_photo_save_one.str()) {
        val bytes = withContext(Dispatchers.Default) { encodeImage(idPhoto(photo.image, frame, format), ImageTarget.JPEG, 95).withDpi(PHOTO_DPI) }
        saveBytes(bytes, renamed(photo.name, "jpg", "-${format.tag}"))
    }
    val (columns, rows) = sheetGrid(format)
    Text(stringResource(Res.string.id_photo_sheet, columns * rows), style = MaterialTheme.typography.bodyMedium)
    SaveButton(Res.string.id_photo_save_sheet.str()) {
        val bytes = withContext(Dispatchers.Default) {
            encodeImage(printSheet(idPhoto(photo.image, frame, format), format), ImageTarget.JPEG, 95).withDpi(SHEET_DPI)
        }
        saveBytes(bytes, renamed(photo.name, "jpg", "-${format.tag}-10x15"))
    }
}

private fun DrawScope.headGuide(frame: Rect, format: IdFormat) {
    val top = frame.top + frame.height * format.crown / format.height
    val bottom = frame.top + frame.height * format.chin / format.height
    val head = bottom - top
    val color = Color.White.copy(alpha = 0.9f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    drawLine(color, Offset(frame.left, top), Offset(frame.right, top), 1.5.dp.toPx(), pathEffect = dash)
    drawLine(color, Offset(frame.left, bottom), Offset(frame.right, bottom), 1.5.dp.toPx(), pathEffect = dash)
    drawOval(color, Offset(frame.center.x - head * 0.37f, top), Size(head * 0.74f, head), style = Stroke(1.5.dp.toPx()))
}
