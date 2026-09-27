package com.vasmarfas.card.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun decodeRawImage(bytes: ByteArray, maxSide: Int): RawImage? = withContext(Dispatchers.Default) {
    skiaDecode(bytes)?.let { return@withContext RawImage(it, oriented = true) }
    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        ?: return@withContext MediaEngine.heifImage(bytes)?.let { RawImage(it, oriented = false) }
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    RawImage(imageBitmapOf(pixels, image.width, image.height), oriented = false)
}
