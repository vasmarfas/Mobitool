package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE
import org.bytedeco.tesseract.global.tesseract.TessDeleteText
import org.bytedeco.tesseract.global.tesseract.TessResultIteratorDelete
import java.io.File

internal expect fun ocrDataDir(): File

actual object OcrEngine {
    private val lock = Mutex()
    private var api: TessBaseAPI? = null
    private var loaded = ""

    actual suspend fun recognize(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage = withContext(Dispatchers.Default) {
        val gray = Ocr.gray(image.pixels())
        lock.withLock {
            val tess = engine(languages)
            tess.SetImage(gray, image.width, image.height, 1, image.width)
            tess.SetSourceResolution(300)
            if (tess.Recognize(null) != 0) throw OcrException("Tesseract could not read the image")
            val lines = mutableListOf<OcrLine>()
            val iterator = tess.GetIterator()
            if (iterator != null) {
                val left = IntArray(1)
                val top = IntArray(1)
                val right = IntArray(1)
                val bottom = IntArray(1)
                do {
                    if (iterator.Empty(RIL_TEXTLINE)) continue
                    val pointer = iterator.GetUTF8Text(RIL_TEXTLINE) ?: continue
                    val text = pointer.getString(Charsets.UTF_8)
                    TessDeleteText(pointer)
                    iterator.BoundingBox(RIL_TEXTLINE, left, top, right, bottom)
                    lines += OcrLine(text.trimEnd(), iterator.Confidence(RIL_TEXTLINE), left[0], top[0], right[0], bottom[0])
                } while (iterator.Next(RIL_TEXTLINE))
                TessResultIteratorDelete(iterator)
            }
            tess.Clear()
            OcrPage(lines, image.width, image.height)
        }
    }

    private suspend fun engine(languages: Set<OcrLanguage>): TessBaseAPI {
        val chosen = OcrLanguage.entries.filter { it in languages }
        val key = chosen.joinToString("+") { it.tesseract }
        api?.let { if (loaded == key) return it }
        api?.End()
        api = null
        val dir = File(ocrDataDir(), "tessdata").apply { mkdirs() }
        chosen.forEach { language ->
            val name = "${language.tesseract}.traineddata"
            val bytes = Res.readBytes("files/tessdata/$name")
            val file = File(dir, name)
            if (file.length() != bytes.size.toLong()) {
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
            }
        }
        val tess = try {
            TessBaseAPI()
        } catch (e: Throwable) {
            throw OcrException(e.toString(), Res.string.ocr_unavailable)
        }
        if (tess.Init(dir.absolutePath, key, OEM_LSTM_ONLY) != 0) {
            tess.End()
            throw OcrException("Tesseract did not load $key from $dir")
        }
        tess.SetPageSegMode(PSM_AUTO)
        api = tess
        loaded = key
        return tess
    }
}
