@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSError
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import kotlin.math.roundToInt

actual object OcrEngine {
    actual suspend fun recognize(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage = withContext(Dispatchers.Default) {
        val width = image.width
        val height = image.height
        val cgImage = cgImageOf(image.pixels(), width, height) ?: throw OcrException("No CGImage for a $width x $height picture")
        val request = VNRecognizeTextRequest()
        request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
        request.recognitionLanguages = OcrLanguage.entries.filter { it in languages }.map { it.apple }
        request.usesLanguageCorrection = true
        val failure = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val done = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any?>()).performRequests(listOf(request), error.ptr)
            if (done) null else error.value?.localizedDescription ?: "Vision failed"
        }
        CGImageRelease(cgImage)
        if (failure != null) throw OcrException(failure)
        val lines = request.results.orEmpty().mapNotNull { result ->
            val observation = result as? VNRecognizedTextObservation ?: return@mapNotNull null
            val candidate = observation.topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return@mapNotNull null
            observation.boundingBox.useContents {
                OcrLine(
                    candidate.string,
                    candidate.confidence * 100,
                    (origin.x * width).roundToInt(),
                    ((1 - origin.y - size.height) * height).roundToInt(),
                    ((origin.x + size.width) * width).roundToInt(),
                    ((1 - origin.y) * height).roundToInt(),
                )
            }
        }
        OcrPage(lines, width, height)
    }
}
