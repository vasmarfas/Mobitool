package com.vasmarfas.card.core

import com.vasmarfas.card.tools.media.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SCAN_SIDE = 2000

internal expect fun scanPixels(pixels: IntArray, width: Int, height: Int): List<ScannedCode>

actual suspend fun scanCodes(image: ByteArray): List<ScannedCode> = withContext(Dispatchers.Default) {
    val bitmap = decodeImage(image, SCAN_SIDE) ?: return@withContext emptyList()
    scanPixels(bitmap.pixels(), bitmap.width, bitmap.height)
}
