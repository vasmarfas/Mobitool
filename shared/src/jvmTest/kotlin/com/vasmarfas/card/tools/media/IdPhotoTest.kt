package com.vasmarfas.card.tools.media

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.pixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(InternalComposeUiApi::class)
class IdPhotoTest {
    init {
        registerSkikoComposeImplementation()
    }

    @Test
    fun aSheetHoldsWhatFitsWithMarginsAndGaps() {
        assertEquals(2 to 3, sheetGrid(IdFormat.PASSPORT))
        assertEquals(3 to 3, sheetGrid(IdFormat.SMALL))
        assertEquals(1 to 2, sheetGrid(IdFormat.US))
        assertEquals(2 to 3, sheetGrid(IdFormat.CHINA))
    }

    @Test
    fun thePhotoAndTheSheetHaveTheirPrintSizes() {
        val source = imageBitmapOf(IntArray(400 * 600) { 0xFF4080C0.toInt() }, 400, 600)
        val photo = idPhoto(source, Rect(0.1f, 0.1f, 0.9f, 0.9f), IdFormat.PASSPORT)
        assertEquals(827 to 1063, photo.width to photo.height)
        val sheet = printSheet(photo, IdFormat.PASSPORT)
        assertEquals(1200 to 1800, sheet.width to sheet.height)
        val corner = sheet.pixels()[0]
        assertEquals(0xFFFFFFFF.toInt(), corner)
    }

    @Test
    fun densityGoesIntoTheJfifHeader() {
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1,
        )
        val patched = header.withDpi(600)
        assertContentEquals(byteArrayOf(1, 0x02, 0x58.toByte(), 0x02, 0x58.toByte()), patched.copyOfRange(13, 18))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(20)
        assertContentEquals(png, png.withDpi(600))
    }
}
