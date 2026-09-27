package com.vasmarfas.card.core

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.js.Promise

private fun jsImportBarcode(): Promise<JsAny> = js("window.vasmarfasImport('barcode.mjs')")

private fun jsScanBytes(module: JsAny, bytes: Int8Array): Promise<JsArray<JsAny>> =
    js("module.scanBytes(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length))")

private fun jsCameraAvailable(): Boolean = js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")

private fun jsOpenCamera(module: JsAny): Promise<JsAny> = js("module.openCamera()")

private fun jsFrame(camera: JsAny, maxSide: Int): JsAny? = js("camera.frame(maxSide)")

private fun jsScanFrame(camera: JsAny): Promise<JsArray<JsAny>> = js("camera.scan()")

private fun jsCloseCamera(camera: JsAny): Unit = js("camera.close()")

private fun jsText(result: JsAny): String = js("result.text")

private fun jsFormat(result: JsAny): String = js("result.format")

private fun jsWidth(frame: JsAny): Int = js("frame.width")

private fun jsHeight(frame: JsAny): Int = js("frame.height")

private fun jsPixels(frame: JsAny): Int32Array = js("frame.pixels")

private var barcodeModule: JsAny? = null

private suspend fun barcodes(): JsAny = barcodeModule ?: jsImportBarcode().await<JsAny>().also { barcodeModule = it }

private val labels = mapOf(
    "QRCode" to "QR",
    "QRCodeModel1" to "QR",
    "QRCodeModel2" to "QR",
    "MicroQRCode" to "Micro QR",
    "RMQRCode" to "rMQR",
    "EAN13" to "EAN-13",
    "EAN8" to "EAN-8",
    "UPCA" to "UPC-A",
    "UPCE" to "UPC-E",
    "Code128" to "Code 128",
    "Code39" to "Code 39",
    "Code39Std" to "Code 39",
    "Code39Ext" to "Code 39",
    "Code93" to "Code 93",
    "DataMatrix" to "Data Matrix",
    "DataBar" to "GS1 DataBar",
    "DataBarOmni" to "GS1 DataBar",
    "DataBarStk" to "GS1 DataBar",
    "DataBarStkOmni" to "GS1 DataBar",
    "DataBarExp" to "GS1 DataBar Expanded",
    "DataBarExpStk" to "GS1 DataBar Expanded",
    "DataBarLtd" to "GS1 DataBar Limited",
    "ITF14" to "ITF",
    "CompactPDF417" to "PDF417",
    "MicroPDF417" to "PDF417",
    "AztecCode" to "Aztec",
    "AztecRune" to "Aztec",
)

private fun codes(results: JsArray<JsAny>): List<ScannedCode> = (0 until results.length).mapNotNull { results[it] }.map {
    val format = jsFormat(it)
    ScannedCode(jsText(it), labels[format] ?: format)
}

actual suspend fun scanCodes(image: ByteArray): List<ScannedCode> = codes(jsScanBytes(barcodes(), image.toInt8Array()).await())

actual val cameraScanning: Boolean = jsCameraAvailable()

private const val PREVIEW_SIDE = 480
private const val FRAME_MS = 66L
private const val SCAN_EVERY = 4

@Composable
actual fun CameraScanner(onFound: (ScannedCode) -> Unit, modifier: Modifier) {
    val found by rememberUpdatedState(onFound)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val camera = runCatching { withContext(NonCancellable) { jsOpenCamera(barcodes()).await<JsAny>() } }.getOrNull()
        if (camera == null) {
            failed = true
            return@LaunchedEffect
        }
        try {
            var tick = 0
            while (isActive) {
                jsFrame(camera, PREVIEW_SIDE)?.let { frame = rgbaBitmap(jsPixels(it), jsWidth(it), jsHeight(it)) }
                if (tick++ % SCAN_EVERY == 0) codes(jsScanFrame(camera).await()).firstOrNull()?.let { found(it) }
                delay(FRAME_MS)
            }
        } finally {
            jsCloseCamera(camera)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = frame
        when {
            failed -> ErrorText(stringResource(Res.string.scan_camera_denied))
            shown == null -> LoadingRow()
            else -> Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
