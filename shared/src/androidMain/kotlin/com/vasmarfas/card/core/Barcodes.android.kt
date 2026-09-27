package com.vasmarfas.card.core

import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ErrorText
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.compose.resources.stringResource

actual val cameraScanning: Boolean
    get() = AppContextHolder.context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private val analysisSize = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

@Composable
actual fun CameraScanner(onFound: (ScannedCode) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onFound)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(owner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = MultiFormatReader()
        analysis.setAnalyzer(worker) { image ->
            val code = image.use { readFrame(reader, it) }
            if (code != null) main.execute { found(code) }
        }
        try {
            val camera = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            provider.bindToLifecycle(owner, camera, preview, analysis)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            provider.unbind(preview, analysis)
            analysis.clearAnalyzer()
            worker.shutdown()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            ErrorText(stringResource(Res.string.scan_camera_failed))
        } else {
            request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        }
    }
}

private fun readFrame(reader: MultiFormatReader, image: ImageProxy): ScannedCode? {
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val buffer = plane.buffer
    val luma = ByteArray(width * height)
    for (y in 0 until height) {
        buffer.position(y * plane.rowStride)
        buffer.get(luma, y * width, width)
    }
    val upright = image.imageInfo.rotationDegrees % 180 != 0
    val source = if (upright) {
        val turned = ByteArray(luma.size)
        for (y in 0 until height) for (x in 0 until width) turned[x * height + (height - 1 - y)] = luma[y * width + x]
        PlanarYUVLuminanceSource(turned, height, width, 0, 0, height, width, false)
    } else {
        PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    }
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).toScanned() }.getOrNull().also { reader.reset() }
}
