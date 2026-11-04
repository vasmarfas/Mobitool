package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.await
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.toInt8Array
import kotlin.js.Promise

private fun jsDecodeImage(bytes: Int8Array, maxSide: Int): Promise<JsAny?> = js(
    """(async () => {
        try {
            let bitmap = await createImageBitmap(new Blob([bytes]));
            const scale = maxSide / Math.max(bitmap.width, bitmap.height);
            if (maxSide > 0 && scale < 1) {
                const full = bitmap;
                bitmap = await createImageBitmap(full, {
                    resizeWidth: Math.max(1, Math.round(full.width * scale)),
                    resizeHeight: Math.max(1, Math.round(full.height * scale)),
                    resizeQuality: 'high',
                });
                full.close();
            }
            const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
            const context = canvas.getContext('2d');
            context.drawImage(bitmap, 0, 0);
            const data = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
            return { width: bitmap.width, height: bitmap.height, pixels: new Int32Array(data.buffer) };
        } catch (e) {
            return null;
        }
    })()""",
)

private fun jsImageWidth(image: JsAny): Int = js("image.width")

private fun jsImageHeight(image: JsAny): Int = js("image.height")

private fun jsImagePixels(image: JsAny): Int32Array = js("image.pixels")

private suspend fun browserDecode(bytes: ByteArray, maxSide: Int): RawImage? {
    val decoded = jsDecodeImage(bytes.toInt8Array(), maxSide).await<JsAny?>() ?: return null
    return RawImage(rgbaBitmap(jsImagePixels(decoded), jsImageWidth(decoded), jsImageHeight(decoded)), oriented = true)
}

private fun skiaRaw(bytes: ByteArray): RawImage? = skiaDecode(bytes)?.let { RawImage(it, oriented = true) }

// Skia in wasm knows the common formats, the browser's decoder adds AVIF, and HEIC in Safari. It also scales
// while decoding, Skia would hold the whole frame in the wasm heap first
actual suspend fun decodeRawImage(bytes: ByteArray, maxSide: Int): RawImage? =
    if (maxSide > 0) browserDecode(bytes, maxSide) ?: skiaRaw(bytes) else skiaRaw(bytes) ?: browserDecode(bytes, 0)

// canvas image data comes as RGBA bytes, Compose wants ARGB ints
fun rgbaBitmap(rgba: Int32Array, width: Int, height: Int): ImageBitmap {
    val pixels = IntArray(width * height) {
        val v = rgba[it]
        (v and 0xFF00FF00.toInt()) or ((v and 0xFF) shl 16) or ((v ushr 16) and 0xFF)
    }
    return imageBitmapOf(pixels, width, height)
}
