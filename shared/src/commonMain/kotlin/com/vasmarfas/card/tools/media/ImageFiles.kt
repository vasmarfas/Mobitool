package com.vasmarfas.card.tools.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.vasmarfas.card.core.EncodedFormat
import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.core.decodeRawImage
import com.vasmarfas.card.core.encode
import com.vasmarfas.card.core.flattened
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.oriented
import com.vasmarfas.card.core.pixels
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.scaled
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ResultCard
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

suspend fun decodeImage(bytes: ByteArray, maxSide: Int = 0): ImageBitmap? {
    decodeRawImage(bytes, maxSide)?.let {
        val bitmap = it.bitmap.limitedTo(maxSide)
        return if (it.oriented) bitmap else bitmap.oriented(Exif.orientation(bytes))
    }
    if (ImageMetadata.detect(bytes) != ImageContainer.TIFF) return null
    val tiff = runCatching { Tiff.decode(bytes) }.getOrNull() ?: return null
    return imageBitmapOf(tiff.pixels, tiff.width, tiff.height).limitedTo(maxSide)
}

enum class ImageTarget(val title: String, val extension: String, val lossy: Boolean, val transparent: Boolean) {
    JPEG("JPEG", "jpg", true, false),
    PNG("PNG", "png", false, true),
    WEBP("WebP", "webp", true, true),
    GIF("GIF", "gif", false, true),
    BMP("BMP", "bmp", false, false),
    ICO("ICO", "ico", false, true),
}

val iconSides = listOf(16, 24, 32, 48, 64, 128, 256)

private const val ARRAY_ENCODED_SIDE = 3072

fun ImageBitmap.limitedFor(target: ImageTarget, colors: Int): ImageBitmap =
    if (target == ImageTarget.GIF || target == ImageTarget.BMP || target == ImageTarget.PNG && colors > 0) limitedTo(ARRAY_ENCODED_SIDE) else this

fun encodeImage(
    image: ImageBitmap,
    target: ImageTarget,
    quality: Int = 90,
    colors: Int = 0,
    background: Color = Color.White,
    icons: List<Int> = iconSides,
): ByteArray {
    val source = if (target.transparent) image else image.flattened(background)
    return when (target) {
        ImageTarget.JPEG -> source.encode(EncodedFormat.JPEG, quality)
        ImageTarget.WEBP -> source.encode(EncodedFormat.WEBP, quality)
        ImageTarget.PNG -> if (colors > 0) Png.encode(source.pixels(), source.width, source.height, colors) else source.encode(EncodedFormat.PNG, 100)
        ImageTarget.GIF -> GifWriter(source.width, source.height, loopCount = -1).apply { addFrame(source.pixels(), 0) }.finish()
        ImageTarget.BMP -> Bmp.encode(source.pixels(), source.width, source.height)
        ImageTarget.ICO -> Ico.encode(icons.sorted().map { IcoImage(it, it, squared(source, it).encode(EncodedFormat.PNG, 100)) })
    }
}

class Compressed(val bytes: ByteArray, val width: Int, val height: Int)

// when even the lowest step does not fit, the picture shrinks by the square root of the missing ratio
// and the search runs again
fun compressImage(image: ImageBitmap, target: ImageTarget, limit: Long): Compressed {
    val steps = if (target == ImageTarget.PNG) listOf(256, 128, 64, 32, 16) else (95 downTo 10 step 5).toList()
    var current = if (target.transparent) image else image.flattened(Color.White)
    repeat(8) {
        var low = 0
        var high = steps.lastIndex
        var best: ByteArray? = null
        while (low <= high) {
            val middle = (low + high) / 2
            val bytes = encodeStep(current, target, steps[middle])
            if (bytes.size <= limit) {
                best = bytes
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        best?.let { return Compressed(it, current.width, current.height) }
        val smallest = encodeStep(current, target, steps.last()).size
        val factor = sqrt(limit.toDouble() / smallest).coerceIn(0.3, 0.9)
        current = current.scaled((current.width * factor).roundToInt(), (current.height * factor).roundToInt())
    }
    return Compressed(encodeStep(current, target, steps.last()), current.width, current.height)
}

private fun encodeStep(image: ImageBitmap, target: ImageTarget, step: Int): ByteArray = when (target) {
    ImageTarget.PNG -> Png.encode(image.pixels(), image.width, image.height, step)
    ImageTarget.WEBP -> image.encode(EncodedFormat.WEBP, step)
    else -> image.encode(EncodedFormat.JPEG, step)
}

private fun squared(image: ImageBitmap, side: Int): ImageBitmap {
    val scale = side.toDouble() / max(image.width, image.height)
    val w = (image.width * scale).roundToInt().coerceIn(1, side)
    val h = (image.height * scale).roundToInt().coerceIn(1, side)
    val out = ImageBitmap(side, side)
    Canvas(out).drawImage(image.scaled(w, h), Offset(((side - w) / 2).toFloat(), ((side - h) / 2).toFloat()), Paint())
    return out
}

class ImageOutput(val name: String, val bytes: ByteArray, val sourceSize: Long, val width: Int, val height: Int)

fun uniqueName(name: String, taken: MutableSet<String>): String {
    var candidate = name
    var n = 2
    val dot = name.lastIndexOf('.')
    while (!taken.add(candidate.lowercase())) {
        candidate = if (dot > 0) name.substring(0, dot) + " ($n)" + name.substring(dot) else "$name ($n)"
        n++
    }
    return candidate
}

@Composable
fun ImageOutputs(outputs: List<ImageOutput>, zipName: String) {
    if (outputs.isEmpty()) return
    ResultCard(title = Res.string.result.str()) {
        val before = outputs.sumOf { it.sourceSize }
        val after = outputs.sumOf { it.bytes.size.toLong() }
        Text(pluralStringResource(Res.plurals.image_count, outputs.size, outputs.size), style = MaterialTheme.typography.bodyMedium)
        if (before > 0) SizeChange(before, after) else Text(formatBytes(after, binary = false), style = MaterialTheme.typography.bodyLarge)
        outputs.forEach { output ->
            val sizes = (if (output.sourceSize > 0) formatBytes(output.sourceSize, binary = false) + " → " else "") + formatBytes(output.bytes.size.toLong(), binary = false)
            FileLine(
                name = output.name,
                detail = "${output.width} × ${output.height} · $sizes",
                trailing = if (outputs.size > 1) ({ SaveIcon { saveBytes(output.bytes, output.name) } }) else null,
            )
        }
        if (outputs.size > 1) {
            SaveButton(Res.string.save_zip.str()) {
                val zip = ZipWriter()
                outputs.forEach { zip.add(it.name, it.bytes, compress = false) }
                saveBytes(zip.toByteArray(), zipName)
            }
        } else {
            SaveButton { saveBytes(outputs[0].bytes, outputs[0].name) }
        }
    }
}

@Composable
private fun SaveIcon(save: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    IconButton(onClick = { scope.launch { saved = runCatching { save() }.getOrDefault(false) } }) {
        Icon(if (saved) Icons.Filled.Check else Icons.Filled.Save, contentDescription = Res.string.save_file.str())
    }
}
