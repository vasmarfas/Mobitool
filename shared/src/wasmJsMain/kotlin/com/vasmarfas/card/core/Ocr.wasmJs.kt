package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.resources.*
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.js.Promise

private fun jsImportOcr(): Promise<JsAny> = js("window.vasmarfasImport('ocr.mjs')")

private fun jsRecognize(module: JsAny, bytes: Int8Array, languages: String, langPath: String): Promise<JsArray<JsAny>> =
    js("module.recognize(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length), languages, langPath)")

private fun jsText(line: JsAny): String = js("line.text")

private fun jsNumber(line: JsAny, key: String): Double = js("line[key]")

actual object OcrEngine {
    private val lock = Mutex()
    private var module: JsAny? = null

    actual suspend fun recognize(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage = lock.withLock {
        val png = image.encode(EncodedFormat.PNG, 100)
        val key = OcrLanguage.entries.filter { it in languages }.joinToString("+") { it.tesseract }
        val langPath = Res.getUri("files/tessdata/eng.traineddata").substringBeforeLast('/')
        val result = try {
            val ocr = module ?: jsImportOcr().await<JsAny>().also { module = it }
            jsRecognize(ocr, png.toInt8Array(), key, langPath).await<JsArray<JsAny>>()
        } catch (e: Throwable) {
            throw OcrException(e.message ?: "tesseract.js failed")
        }
        val lines = (0 until result.length).mapNotNull { result[it] }.map { line ->
            OcrLine(
                jsText(line),
                jsNumber(line, "confidence").toFloat(),
                jsNumber(line, "left").toInt(),
                jsNumber(line, "top").toInt(),
                jsNumber(line, "right").toInt(),
                jsNumber(line, "bottom").toInt(),
            )
        }
        OcrPage(lines, image.width, image.height)
    }
}
