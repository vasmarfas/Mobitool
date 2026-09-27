package com.vasmarfas.card.tools.documents.editor

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.pixels
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.documents.documentFonts
import com.vasmarfas.card.tools.documents.pdf.PdfArray
import com.vasmarfas.card.tools.documents.pdf.PdfDict
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfFilters
import com.vasmarfas.card.tools.documents.pdf.PdfInt
import com.vasmarfas.card.tools.documents.pdf.PdfName
import com.vasmarfas.card.tools.documents.pdf.PdfObject
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfRef
import com.vasmarfas.card.tools.documents.pdf.PdfStream
import com.vasmarfas.card.tools.media.Exif
import com.vasmarfas.card.tools.media.ImageTarget
import com.vasmarfas.card.tools.media.decodeImage
import com.vasmarfas.card.tools.media.encodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class ImageQuality(val side: Int, val jpeg: Int) { HIGH(2400, 85), MEDIUM(1600, 74), LOW(1100, 62) }

internal data class EditorSaveOptions(
    val keepEditable: Boolean = false,
    val flattenForm: Boolean = false,
    val burnRedactions: Boolean = true,
    val compress: Boolean = false,
    val quality: ImageQuality = ImageQuality.MEDIUM,
    val flattenAnnotations: Boolean = false,
    val removeAnnotations: Boolean = false,
)

internal suspend fun prepareImage(bytes: ByteArray): PendingImage? {
    val decoded = decodeImage(bytes) ?: return null
    val bitmap = decoded.limitedTo(2400)
    val components = jpegComponents(bytes)
    if (components != null && bitmap === decoded && Exif.orientation(bytes) == 1) {
        return PendingImage(MarkImage(bitmap.width, bitmap.height, jpeg = bytes, gray = components == 1), bitmap)
    }
    return withContext(Dispatchers.Default) {
        val pixels = bitmap.pixels()
        if (pixels.all { it ushr 24 == 0xFF }) {
            PendingImage(MarkImage(bitmap.width, bitmap.height, jpeg = encodeImage(bitmap, ImageTarget.JPEG, 90)), bitmap)
        } else {
            val rgb = ByteArray(pixels.size * 3)
            val alpha = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgb[3 * i] = (p shr 16).toByte()
                rgb[3 * i + 1] = (p shr 8).toByte()
                rgb[3 * i + 2] = p.toByte()
                alpha[i] = (p ushr 24).toByte()
            }
            PendingImage(MarkImage(bitmap.width, bitmap.height, rgb = rgb, alpha = alpha), bitmap)
        }
    }
}

private fun jpegComponents(bytes: ByteArray): Int? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var i = 2
    while (i + 9 < bytes.size) {
        if (bytes[i] != 0xFF.toByte()) return null
        val marker = bytes[i + 1].toInt() and 0xFF
        val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) return (bytes[i + 9].toInt() and 0xFF).takeIf { it == 1 || it == 3 }
        if (marker == 0xD9 || marker == 0xDA) return null
        i += 2 + length
    }
    return null
}

internal suspend fun buildPdf(session: EditorSession, renderer: MarkRenderer, source: DocumentEdit, options: EditorSaveOptions, onProgress: (Float) -> Unit): ByteArray {
    var edit = source
    val redacted = edit.pages.filter { page -> page.marks.any { it is CoverMark && it.redact } }
    if (options.burnRedactions && redacted.isNotEmpty()) {
        val burnt = HashMap<Long, EditPage>()
        redacted.forEachIndexed { i, page ->
            burnt[page.id] = burn(session, renderer, page) ?: throw IllegalStateException(getString(Res.string.pdf_edit_redaction_failed))
            onProgress(0.4f * (i + 1) / redacted.size)
        }
        edit = edit.copy(pages = edit.pages.map { burnt[it.id] ?: it })
    }
    val replacements = if (options.compress) compress(edit, options.quality) { onProgress(0.4f + 0.4f * it) } else emptyMap()
    onProgress(0.85f)
    val save = SaveOptions(
        keepMarksEditable = options.keepEditable,
        flattenForm = options.flattenForm,
        flattenAnnotations = options.flattenAnnotations,
        removeAnnotations = options.removeAnnotations,
    )
    return withContext(Dispatchers.Default) { PdfEditWriter.write(session.main, edit, session.fonts, save, replacements, pdfDate(currentEpochMillis())) }
}

internal suspend fun compressPdf(document: PdfDocument, quality: ImageQuality, onProgress: (Float) -> Unit): ByteArray {
    val fonts = MarkFonts(documentFonts(serif = true))
    val edit = startingEdit(document)
    val replacements = compress(edit, quality) { onProgress(0.9f * it) }
    return withContext(Dispatchers.Default) { PdfEditWriter.write(document, edit, fonts, replacements = replacements, date = pdfDate(currentEpochMillis())) }
}

private suspend fun burn(session: EditorSession, renderer: MarkRenderer, page: EditPage): EditPage? {
    val source = page.source
    val frame = PageFrame(source.cropBox, source.rotation)
    val dpi = minOf(200.0, 72 * sqrt(BURN_PIXELS / (frame.width * frame.height)))
    val width = (frame.width * dpi / 72).roundToInt().coerceIn(200, 3200)
    val raster = (source as? SourcePage)?.let { session.renderFlattened(it, width, page.objects) ?: return null }
    val picture = (source as? ImagePage)?.let { session.images[it.image] ?: return null }
    val out = ImageBitmap(raster?.width ?: width, raster?.height ?: (width * frame.height / frame.width).roundToInt().coerceAtLeast(1))
    val toScreen = frame.displayAffine().scaled(out.width / frame.width)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(out.width.toFloat(), out.height.toFloat())) {
        drawRect(Color.White)
        raster?.let { drawImage(it) }
        picture?.let { drawImage(it, dstSize = IntSize(out.width, out.height), filterQuality = FilterQuality.Medium) }
        with(renderer) {
            for (mark in page.marks) if (mark !is NoteMark) draw(if (mark is CoverMark) mark.copy(redact = false) else mark, toScreen, session.images)
        }
    }
    val jpeg = withContext(Dispatchers.Default) { encodeImage(out, ImageTarget.JPEG, 88) }
    val crop = page.crop?.let { crop ->
        val d = frame.displayAffine().rect(crop)
        PdfRect(d.left.toDouble(), frame.height - d.bottom, d.right.toDouble(), frame.height - d.top)
    }
    val notes = page.marks.filterIsInstance<NoteMark>().map { it.copy(x = frame.toDisplayU(it.x, it.y), y = frame.height - frame.toDisplayV(it.x, it.y)) }
    return EditPage(page.id, ImagePage(MarkImage(out.width, out.height, jpeg = jpeg), frame.width, frame.height), turn = page.turn, crop = crop, marks = notes)
}

private val passThrough = setOf("FlateDecode", "Fl", "LZWDecode", "LZW", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85", "RunLengthDecode", "RL")

// a bigger picture is inflated in one piece, which needs a few times its size on the heap at once
private const val MAX_SAMPLES = 40_000_000

private const val BURN_PIXELS = 25_000_000.0

private suspend fun compress(edit: DocumentEdit, quality: ImageQuality, onProgress: (Float) -> Unit): Map<PdfDocument, Map<Int, PdfObject>> {
    val found = LinkedHashMap<Pair<PdfDocument, Int>, PdfStream>()
    for (page in edit.pages) {
        val source = (page.source as? SourcePage)?.page ?: continue
        collect(source.document, source.resources, found, 0)
    }
    val out = HashMap<PdfDocument, HashMap<Int, PdfObject>>()
    var done = 0
    for ((key, stream) in found) {
        val recoded = try {
            recode(key.first, stream, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        recoded?.let { out.getOrPut(key.first) { HashMap() }[key.second] = it }
        onProgress(++done / found.size.toFloat())
    }
    return out
}

private fun collect(doc: PdfDocument, resources: PdfDict, found: MutableMap<Pair<PdfDocument, Int>, PdfStream>, depth: Int) {
    val xobjects = resources.dict("XObject", doc) ?: return
    for (value in xobjects.entries.values) {
        val ref = value as? PdfRef ?: continue
        if ((doc to ref.number) in found) continue
        val stream = doc.resolve(ref) as? PdfStream ?: continue
        when (stream.dict.name("Subtype", doc)) {
            "Image" -> found[doc to ref.number] = stream
            "Form" -> if (depth < 6) stream.dict.dict("Resources", doc)?.let { collect(doc, it, found, depth + 1) }
        }
    }
}

private fun components(doc: PdfDocument, space: PdfObject?): Int? = when (val value = doc.resolve(space)) {
    is PdfName -> when (value.name) {
        "DeviceRGB", "CalRGB" -> 3
        "DeviceGray", "CalGray" -> 1
        else -> null
    }
    is PdfArray -> when ((doc.resolve(value.items.firstOrNull()) as? PdfName)?.name) {
        "ICCBased" -> (doc.resolve(value.items.getOrNull(1)) as? PdfStream)?.dict?.int("N", doc)?.takeIf { it == 1 || it == 3 }
        "CalRGB" -> 3
        "CalGray" -> 1
        else -> null
    }
    else -> null
}

private suspend fun recode(doc: PdfDocument, stream: PdfStream, quality: ImageQuality): PdfStream? {
    val dict = stream.dict
    val width = dict.int("Width", doc) ?: return null
    val height = dict.int("Height", doc) ?: return null
    if (width.toLong() * height < 250_000 || dict.boolean("ImageMask", doc) == true || dict["Decode"] != null || dict["Mask"] is PdfArray) return null
    if ((dict.int("BitsPerComponent", doc) ?: 8) != 8) return null
    val channels = components(doc, dict["ColorSpace"]) ?: return null
    val filters = PdfFilters.filterNames(dict, doc)
    val bitmap = when {
        filters.lastOrNull() == "DCTDecode" && filters.dropLast(1).all { it in passThrough } -> {
            val jpeg = runCatching { PdfFilters.decode(stream.data, dict, doc) }.getOrNull() ?: return null
            decodeImage(jpeg)
        }
        filters.all { it in passThrough } -> withContext(Dispatchers.Default) {
            if (width.toLong() * height * channels > MAX_SAMPLES) return@withContext null
            val raw = runCatching { doc.decodedStream(stream) }.getOrNull() ?: return@withContext null
            if (raw.size < width.toLong() * height * channels) return@withContext null
            val step = (maxOf(width, height) / quality.side).coerceIn(1, minOf(width, height))
            val w = width / step
            val h = height / step
            val area = step * step
            val sums = IntArray(w * channels)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                sums.fill(0)
                for (row in y * step until (y + 1) * step) {
                    val start = row * width * channels
                    for (i in 0 until w * step * channels) sums[i / (step * channels) * channels + i % channels] += raw[start + i].toInt() and 0xFF
                }
                for (x in 0 until w) {
                    val s = x * channels
                    pixels[y * w + x] = if (channels == 3) {
                        (0xFF shl 24) or ((sums[s] / area) shl 16) or ((sums[s + 1] / area) shl 8) or (sums[s + 2] / area)
                    } else {
                        val v = sums[s] / area
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
            imageBitmapOf(pixels, w, h)
        }
        else -> null
    } ?: return null
    val scaled = bitmap.limitedTo(quality.side)
    val jpeg = withContext(Dispatchers.Default) { encodeImage(scaled, ImageTarget.JPEG, quality.jpeg) }
    if (jpeg.size >= stream.data.size * 0.9) return null
    val out = PdfDict()
    for ((key, value) in dict.entries) if (key !in setOf("Filter", "DecodeParms", "Length", "Width", "Height", "ColorSpace", "BitsPerComponent", "DL")) out[key] = value
    out["Width"] = PdfInt.of(scaled.width)
    out["Height"] = PdfInt.of(scaled.height)
    out["ColorSpace"] = PdfName("DeviceRGB")
    out["BitsPerComponent"] = PdfInt.of(8)
    out["Filter"] = PdfName("DCTDecode")
    return PdfStream(out, jpeg)
}
