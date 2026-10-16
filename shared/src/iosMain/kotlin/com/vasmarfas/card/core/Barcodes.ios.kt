@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeAztecCode
import platform.AVFoundation.AVMetadataObjectTypeCodabarCode
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Mod43Code
import platform.AVFoundation.AVMetadataObjectTypeCode93Code
import platform.AVFoundation.AVMetadataObjectTypeDataMatrixCode
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeGS1DataBarCode
import platform.AVFoundation.AVMetadataObjectTypeGS1DataBarExpandedCode
import platform.AVFoundation.AVMetadataObjectTypeITF14Code
import platform.AVFoundation.AVMetadataObjectTypeInterleaved2of5Code
import platform.AVFoundation.AVMetadataObjectTypeMicroQRCode
import platform.AVFoundation.AVMetadataObjectTypePDF417Code
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

actual val cameraScanning: Boolean get() = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null

private val cameraFormats = mapOf(
    AVMetadataObjectTypeQRCode to "QR",
    AVMetadataObjectTypeMicroQRCode to "Micro QR",
    AVMetadataObjectTypeEAN13Code to "EAN-13",
    AVMetadataObjectTypeEAN8Code to "EAN-8",
    AVMetadataObjectTypeUPCECode to "UPC-E",
    AVMetadataObjectTypeCode128Code to "Code 128",
    AVMetadataObjectTypeCode39Code to "Code 39",
    AVMetadataObjectTypeCode39Mod43Code to "Code 39",
    AVMetadataObjectTypeCode93Code to "Code 93",
    AVMetadataObjectTypeCodabarCode to "Codabar",
    AVMetadataObjectTypeITF14Code to "ITF",
    AVMetadataObjectTypeInterleaved2of5Code to "ITF",
    AVMetadataObjectTypeDataMatrixCode to "Data Matrix",
    AVMetadataObjectTypePDF417Code to "PDF417",
    AVMetadataObjectTypeAztecCode to "Aztec",
    AVMetadataObjectTypeGS1DataBarCode to "GS1 DataBar",
    AVMetadataObjectTypeGS1DataBarExpandedCode to "GS1 DataBar Expanded",
)

private val sessionQueue = dispatch_queue_create("com.vasmarfas.mobitool.scanner", null)

@Composable
actual fun CameraScanner(onFound: (ScannedCode) -> Unit, modifier: Modifier) {
    val found by rememberUpdatedState(onFound)
    val session = remember { AVCaptureSession() }
    val reader = remember { CodeReader { found(it) } }
    UIKitView(
        factory = { CameraView(session) },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = null),
    )
    DisposableEffect(session) {
        AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
            ?.takeIf { session.canAddInput(it) }
            ?.let { session.addInput(it) }
        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(reader, dispatch_get_main_queue())
            // the list of types fills in only once the output sits in a session with a camera
            output.metadataObjectTypes = cameraFormats.keys.filter { it in output.availableMetadataObjectTypes }
        }
        // without it an iPad in Split View or Stage Manager shows a black preview
        session.multitaskingCameraAccessEnabled = session.multitaskingCameraAccessSupported
        dispatch_async(sessionQueue) { session.startRunning() }
        onDispose { dispatch_async(sessionQueue) { session.stopRunning() } }
    }
}

private fun ScannedCode.upcA(): ScannedCode =
    if (format == "EAN-13" && text.length == 13 && text[0] == '0') ScannedCode(text.drop(1), "UPC-A") else this

private class CodeReader(private val onCode: (ScannedCode) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        val code = didOutputMetadataObjects.firstNotNullOfOrNull { it as? AVMetadataMachineReadableCodeObject } ?: return
        val text = code.stringValue ?: return
        onCode(ScannedCode(text, cameraFormats[code.type] ?: code.type.orEmpty()).upcA())
    }
}

private class CameraView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        preview.frame = bounds
        CATransaction.commit()
    }
}

internal actual fun scanPixels(pixels: IntArray, width: Int, height: Int): List<ScannedCode> {
    val image = cgImageOf(pixels, width, height) ?: return emptyList()
    val request = VNDetectBarcodesRequest()
    VNImageRequestHandler(cGImage = image, options = emptyMap<Any?, Any?>()).performRequests(listOf(request), null)
    CGImageRelease(image)
    return request.results.orEmpty()
        .mapNotNull { it as? VNBarcodeObservation }
        .mapNotNull { observation -> observation.payloadStringValue?.let { ScannedCode(it, label(observation.symbology)).upcA() } }
        .distinctBy { it.text }
}

private fun label(symbology: String?): String {
    val name = symbology.orEmpty().removePrefix("VNBarcodeSymbology")
    return when (name) {
        "QR" -> "QR"
        "EAN13" -> "EAN-13"
        "EAN8" -> "EAN-8"
        "UPCE" -> "UPC-E"
        "Code128" -> "Code 128"
        "Code39", "Code39Checksum", "Code39FullASCII", "Code39FullASCIIChecksum" -> "Code 39"
        "Code93", "Code93i" -> "Code 93"
        "DataMatrix" -> "Data Matrix"
        "PDF417" -> "PDF417"
        "Aztec" -> "Aztec"
        "ITF14", "I2of5", "I2of5Checksum" -> "ITF"
        "GS1DataBar" -> "GS1 DataBar"
        "GS1DataBarExpanded" -> "GS1 DataBar Expanded"
        "MicroQR" -> "Micro QR"
        else -> name
    }
}
