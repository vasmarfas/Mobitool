@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import cnames.structs.__CFURL
import com.vasmarfas.card.resources.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset1920x1080
import platform.AVFoundation.AVAssetExportPreset3840x2160
import platform.AVFoundation.AVAssetExportPreset640x480
import platform.AVFoundation.AVAssetExportPreset960x540
import platform.AVFoundation.AVAssetExportPresetPassthrough
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setAudioMix
import platform.AVFoundation.setFileLengthLimit
import platform.AVFoundation.setVideoComposition
import platform.AVFoundation.tracksWithMediaType
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AudioToolbox.AudioConverterRefVar
import platform.AudioToolbox.AudioConverterSetProperty
import platform.AudioToolbox.ExtAudioFileCreateWithURL
import platform.AudioToolbox.ExtAudioFileDispose
import platform.AudioToolbox.ExtAudioFileGetProperty
import platform.AudioToolbox.ExtAudioFileRefVar
import platform.AudioToolbox.ExtAudioFileSetProperty
import platform.AudioToolbox.ExtAudioFileWrite
import platform.AudioToolbox.kAudioConverterEncodeBitRate
import platform.AudioToolbox.kAudioFileFLACType
import platform.AudioToolbox.kAudioFileFlags_EraseFile
import platform.AudioToolbox.kAudioFileM4AType
import platform.AudioToolbox.kAudioFileWAVEType
import platform.AudioToolbox.kExtAudioFileProperty_AudioConverter
import platform.AudioToolbox.kExtAudioFileProperty_ClientDataFormat
import platform.AudioToolbox.kExtAudioFileProperty_ConverterConfig
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFLAC
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsPacked
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformInvert
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectApplyAffineTransform
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

private val PlatformFile.url: NSURL get() = NSURL.fileURLWithPath(absolutePath())

internal fun cmTime(ms: Long): CValue<CMTime> = CMTimeMake(ms, 1000)

@Suppress("UNCHECKED_CAST")
private val AVAssetTrack.format: CMFormatDescriptionRef? get() = formatDescriptions.firstOrNull() as CMFormatDescriptionRef?

private fun fourCc(code: UInt): String = when (code.toInt()) {
    0x61766331, 0x61766333 -> "h264"
    0x68766331, 0x68657631 -> "hevc"
    0x61763031 -> "av1"
    0x61616320 -> "aac"
    0x2E6D7033 -> "mp3"
    0x666C6163 -> "flac"
    0x616C6163 -> "alac"
    0x6C70636D -> "pcm_s16le"
    0x6F707573 -> "opus"
    else -> "unknown"
}

private fun cgImageToBitmap(image: CGImageRef): ImageBitmap {
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    val rgba = ByteArray(width * height * 4)
    val space = CGColorSpaceCreateDeviceRGB()
    rgba.usePinned { pinned ->
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), width.convert(), height.convert(), 8u, (width * 4).convert(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        CGContextRelease(context)
    }
    CGColorSpaceRelease(space)
    val pixels = IntArray(width * height) { i ->
        val a = rgba[i * 4 + 3].toInt() and 0xFF
        val r = rgba[i * 4].toInt() and 0xFF
        val g = rgba[i * 4 + 1].toInt() and 0xFF
        val b = rgba[i * 4 + 2].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return imageBitmapOf(pixels, width, height)
}

actual object MediaEngine {
    actual val formats: Set<MediaFormat> = setOf(MediaFormat.MP4, MediaFormat.MOV, MediaFormat.M4A, MediaFormat.MP3, MediaFormat.WAV, MediaFormat.FLAC)

    actual val canEdit: Boolean = true

    actual suspend fun probe(file: PlatformFile): MediaInfo = withContext(Dispatchers.Default) {
        val asset = AVURLAsset.URLAssetWithURL(file.url, null)
        val video = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as AVAssetTrack?
        val audio = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as AVAssetTrack?
        if (video == null && audio == null) {
            val image = UIImage.imageWithContentsOfFile(file.absolutePath()) ?: throw MediaException("unreadable file")
            val (w, h) = image.size.useContents { (width * image.scale).roundToInt() to (height * image.scale).roundToInt() }
            return@withContext MediaInfo(0, w, h, 0.0, "image", null, 0, 0, 0)
        }
        var displayW = 0
        var displayH = 0
        var videoCodec: String? = null
        if (video != null) {
            val box = CGRectApplyAffineTransform(video.naturalSize.useContents { CGRectMake(0.0, 0.0, width, height) }, video.preferredTransform)
            box.useContents {
                displayW = abs(size.width).roundToInt()
                displayH = abs(size.height).roundToInt()
            }
            videoCodec = video.format?.let { fourCc(CMFormatDescriptionGetMediaSubType(it)) }
        }
        var sampleRate = 0
        var channels = 0
        val audioCodec = audio?.let { track ->
            val description = track.format
            description?.let { CMAudioFormatDescriptionGetStreamBasicDescription(it) }?.pointed?.let {
                sampleRate = it.mSampleRate.roundToInt()
                channels = it.mChannelsPerFrame.toInt()
            }
            description?.let { fourCc(CMFormatDescriptionGetMediaSubType(it)) } ?: "unknown"
        }
        MediaInfo(
            durationMs = (CMTimeGetSeconds(asset.duration) * 1000).roundToInt().toLong(),
            width = displayW,
            height = displayH,
            frameRate = video?.nominalFrameRate?.toDouble() ?: 0.0,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            sampleRate = sampleRate,
            channels = channels,
            bitrate = 0,
        )
    }

    private fun generator(file: PlatformFile, maxSide: Int): AVAssetImageGenerator =
        AVAssetImageGenerator(asset = AVURLAsset.URLAssetWithURL(file.url, null)).apply {
            appliesPreferredTrackTransform = true
            maximumSize = CGSizeMake(maxSide.toDouble(), maxSide.toDouble())
            requestedTimeToleranceBefore = kCMTimeZero.readValue()
            requestedTimeToleranceAfter = kCMTimeZero.readValue()
        }

    actual suspend fun frame(file: PlatformFile, timeMs: Long, maxSide: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        val image = generator(file, maxSide).copyCGImageAtTime(cmTime(timeMs), null, null) ?: return@withContext null
        cgImageToBitmap(image).also { CGImageRelease(image) }
    }

    actual suspend fun frames(file: PlatformFile, fromMs: Long, toMs: Long, fps: Double, maxSide: Int, onFrame: suspend (ImageBitmap) -> Unit) {
        val generator = generator(file, maxSide)
        var index = 0
        while (true) {
            val at = fromMs + (index * 1000 / fps).roundToInt()
            if (at >= toMs) break
            val bitmap = withContext(Dispatchers.Default) {
                generator.copyCGImageAtTime(cmTime(at), null, null)?.let { image -> cgImageToBitmap(image).also { CGImageRelease(image) } }
            } ?: break
            onFrame(bitmap)
            index++
        }
    }

    actual suspend fun export(project: MediaProject, onProgress: (Float) -> Unit): MediaResult {
        val spec = project.spec
        if (spec.format !in formats) throw MediaException("${spec.format.extension} is not supported on iOS")
        // the M4A export preset takes no bitrate, sample rate or channel count, the audio writer takes all three
        return if (spec.format.video) compose(project, onProgress) else transcodeAudio(project, onProgress)
    }

    private suspend fun compose(project: MediaProject, onProgress: (Float) -> Unit): MediaResult {
        val spec = project.spec
        val passthrough = spec.copyStreams && project.single?.kind == ClipKind.VIDEO
        val built = build(project, video = spec.format.video, audio = spec.keepAudio)
        val side = maxOf(built.width, built.height)
        val preset = when {
            passthrough -> AVAssetExportPresetPassthrough
            side > 1920 -> AVAssetExportPreset3840x2160
            side > 1280 -> AVAssetExportPreset1920x1080
            side > 960 -> AVAssetExportPreset1280x720
            side > 640 -> AVAssetExportPreset960x540
            else -> AVAssetExportPreset640x480
        }
        val session = AVAssetExportSession.exportSessionWithAsset(built.composition, preset) ?: throw MediaException("export is not available")
        val output = tempPath(spec.format.extension)
        session.outputURL = NSURL.fileURLWithPath(output)
        session.outputFileType = if (spec.format == MediaFormat.MOV) AVFileTypeQuickTimeMovie else AVFileTypeMPEG4
        session.shouldOptimizeForNetworkUse = true
        if (spec.targetBytes > 0) session.setFileLengthLimit(spec.targetBytes)
        if (passthrough) {
            built.layers.singleOrNull()?.let { layer -> layer.sources.singleOrNull()?.let { layer.track.preferredTransform = it.preferredTransform } }
        } else {
            built.videoComposition(project)?.let { session.setVideoComposition(it) }
            built.audioMix?.let { session.setAudioMix(it) }
        }
        try {
            run(session, onProgress)
        } catch (e: Throwable) {
            NSFileManager.defaultManager.removeItemAtPath(output, null)
            throw e
        } finally {
            built.discardStills()
        }
        // the export session picks no bitrate for a size, it stops writing at the limit and calls that a success
        if (spec.targetBytes > 0) {
            val writtenMs = (CMTimeGetSeconds(AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(output), null).duration) * 1000).toLong()
            if (writtenMs + 500 < project.durationMs) {
                NSFileManager.defaultManager.removeItemAtPath(output, null)
                throw MediaException("the size limit cut the video at $writtenMs ms", Res.string.video_size_limit_cut)
            }
        }
        return MediaResult(PlatformFile(output))
    }

    internal class Layer(val index: Int, val track: AVMutableCompositionTrack, val sources: List<AVAssetTrack?>)

    internal class Built(
        val composition: AVMutableComposition,
        val audioMix: AVMutableAudioMix?,
        val width: Int,
        val height: Int,
        val fps: Int,
        val layers: List<Layer>,
        val structure: MediaProject,
        private val stills: List<NSURL>,
    ) {
        fun discardStills() = stills.forEach { NSFileManager.defaultManager.removeItemAtURL(it, null) }

        fun videoComposition(project: MediaProject): AVMutableVideoComposition? {
            if (layers.isEmpty()) return null
            val instructions = layers.map { layer ->
                AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(layer.track).apply {
                    project.pictures[layer.index].clips.sortedBy { it.atMs }.zip(layer.sources).forEach { (clip, source) ->
                        if (source != null) place(this, source, clip, width, height)
                    }
                }
            }
            val instruction = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
            instruction.setTimeRange(CMTimeRangeMake(kCMTimeZero.readValue(), composition.duration))
            instruction.setLayerInstructions(instructions.asReversed())
            return AVMutableVideoComposition.videoComposition().apply {
                setRenderSize(CGSizeMake(width.toDouble(), height.toDouble()))
                setFrameDuration(CMTimeMake(1, fps))
                setInstructions(listOf(instruction))
            }
        }
    }

    internal suspend fun build(project: MediaProject, video: Boolean, audio: Boolean, maxSide: Int = 0): Built {
        val spec = project.spec
        val infos = HashMap<PlatformFile, MediaInfo>()
        project.tracks.flatMap { it.clips }.map { it.file }.distinct().forEach { infos[it] = probe(it) }
        val first = project.firstPicture?.let { infos[it.file] }
        var (width, height) = spec.frameSize(first)
        if (maxSide > 0 && maxOf(width, height) > maxSide) {
            val scale = maxSide.toDouble() / maxOf(width, height)
            width = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            height = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        }
        val total = project.durationMs
        val composition = AVMutableComposition()
        val layers = mutableListOf<Layer>()
        val stills = mutableListOf<NSURL>()
        if (video) {
            project.pictures.forEachIndexed { index, track ->
                if (track.clips.isEmpty()) return@forEachIndexed
                val target = composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid) ?: throw MediaException("no room for a video track")
                var cursor = 0L
                val sources = track.clips.sortedBy { it.atMs }.map { clip ->
                    val url = if (clip.kind == ClipKind.IMAGE) stillVideo(clip.file, clip.durationMs, maxOf(width, height)).also { stills += it } else clip.file.url
                    val source = AVURLAsset.URLAssetWithURL(url, null).tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as AVAssetTrack?
                    if (source != null) {
                        if (clip.atMs > cursor) target.insertEmptyTimeRange(CMTimeRangeMake(cmTime(cursor), cmTime(clip.atMs - cursor)))
                        val from = if (clip.kind == ClipKind.IMAGE) 0 else clip.startMs
                        target.insertTimeRange(CMTimeRangeMake(cmTime(from), cmTime(clip.durationMs)), source, cmTime(clip.atMs), null)
                        cursor = clip.endAtMs
                    }
                    source
                }
                if (total > cursor) target.insertEmptyTimeRange(CMTimeRangeMake(cmTime(cursor), cmTime(total - cursor)))
                layers += Layer(index, target, sources)
            }
        }
        val mix = mutableListOf<AVMutableAudioMixInputParameters>()
        if (audio) {
            project.tracks.filter { !it.muted }.forEach { track ->
                val sounds = track.clips.filter { it.kind != ClipKind.IMAGE && it.volume > 0f && infos[it.file]?.hasAudio == true }.sortedBy { it.atMs }
                if (sounds.isEmpty()) return@forEach
                val target = composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid) ?: throw MediaException("no room for an audio track")
                val parameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(target)
                var cursor = 0L
                sounds.forEach { clip ->
                    val source = AVURLAsset.URLAssetWithURL(clip.file.url, null).tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as AVAssetTrack? ?: return@forEach
                    if (clip.atMs > cursor) target.insertEmptyTimeRange(CMTimeRangeMake(cmTime(cursor), cmTime(clip.atMs - cursor)))
                    target.insertTimeRange(CMTimeRangeMake(cmTime(clip.startMs), cmTime(clip.durationMs)), source, cmTime(clip.atMs), null)
                    clip.fadePieces { fromMs, toMs, from, to ->
                        if (from == to) {
                            parameters.setVolume(clip.volume * from, cmTime(fromMs))
                        } else {
                            parameters.setVolumeRampFromStartVolume(clip.volume * from, clip.volume * to, CMTimeRangeMake(cmTime(fromMs), cmTime(toMs - fromMs)))
                        }
                    }
                    cursor = clip.endAtMs
                }
                mix += parameters
            }
        }
        val audioMix = if (mix.isEmpty()) null else AVMutableAudioMix.audioMix().apply { setInputParameters(mix) }
        return Built(composition, audioMix, width, height, spec.frameRate(first), layers, project.structure(), stills)
    }

    private suspend fun run(session: AVAssetExportSession, onProgress: (Float) -> Unit) = coroutineScope {
        val poll = launch {
            while (true) {
                onProgress(session.progress)
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine { continuation ->
                session.exportAsynchronouslyWithCompletionHandler {
                    if (session.status == AVAssetExportSessionStatusCompleted) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(MediaException(session.error?.localizedDescription ?: "export failed"))
                    }
                }
                continuation.invokeOnCancellation { session.cancelExport() }
            }
        } finally {
            poll.cancel()
        }
    }

    // compositions take only tracks, so a photo becomes a two-frame H.264 clip at its own proportions,
    // upright the way UIKit draws it
    private suspend fun stillVideo(file: PlatformFile, durationMs: Long, maxSide: Int): NSURL {
        val image = UIImage.imageWithContentsOfFile(file.absolutePath()) ?: throw MediaException("unreadable image")
        val (imageW, imageH) = image.size.useContents { width * image.scale to height * image.scale }
        val fit = minOf(1.0, maxSide / maxOf(imageW, imageH))
        val width = ((imageW * fit).roundToInt() / 2 * 2).coerceAtLeast(2)
        val height = ((imageH * fit).roundToInt() / 2 * 2).coerceAtLeast(2)
        val url = NSURL.fileURLWithPath(tempPath("mov"))
        val writer = AVAssetWriter(url, AVFileTypeQuickTimeMovie, null)
        val input = AVAssetWriterInput(AVMediaTypeVideo, mapOf<Any?, Any?>(AVVideoCodecKey to AVVideoCodecTypeH264, AVVideoWidthKey to width, AVVideoHeightKey to height))
        val formatKey = CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey))
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(input, mapOf<Any?, Any?>(formatKey to kCVPixelFormatType_32BGRA))
        writer.addInput(input)
        writer.startWriting()
        writer.startSessionAtSourceTime(kCMTimeZero.readValue())
        val pool = adaptor.pixelBufferPool ?: throw MediaException("no pixel buffer pool")
        memScoped {
            val holder = alloc<CVPixelBufferRefVar>()
            CVPixelBufferPoolCreatePixelBuffer(null, pool, holder.ptr)
            val buffer = holder.value ?: throw MediaException("no pixel buffer")
            CVPixelBufferLockBaseAddress(buffer, 0u)
            val space = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(
                CVPixelBufferGetBaseAddress(buffer), width.convert(), height.convert(), 8u, CVPixelBufferGetBytesPerRow(buffer), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
            )
            val box = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
            CGContextClearRect(context, box)
            CGContextTranslateCTM(context, 0.0, height.toDouble())
            CGContextScaleCTM(context, 1.0, -1.0)
            UIGraphicsPushContext(context)
            image.drawInRect(box)
            UIGraphicsPopContext()
            CGContextRelease(context)
            CGColorSpaceRelease(space)
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
            while (!input.readyForMoreMediaData) delay(10)
            adaptor.appendPixelBuffer(buffer, kCMTimeZero.readValue())
            while (!input.readyForMoreMediaData) delay(10)
            adaptor.appendPixelBuffer(buffer, cmTime((durationMs - 40).coerceAtLeast(1)))
            CVPixelBufferRelease(buffer)
        }
        input.markAsFinished()
        writer.endSessionAtSourceTime(cmTime(durationMs))
        suspendCancellableCoroutine { continuation -> writer.finishWritingWithCompletionHandler { continuation.resume(Unit) } }
        return url
    }

    private suspend fun transcodeAudio(project: MediaProject, onProgress: (Float) -> Unit): MediaResult = withContext(Dispatchers.Default) {
        val output = tempPath(project.spec.format.extension)
        var writer: AudioSink? = null
        val total = project.durationMs.coerceAtLeast(1)
        var done = 0L
        var completed = false
        try {
            project.sounds.sortedBy { it.atMs }.forEach { clip ->
                readPcm(clip.file.url, clip.startMs, clip.endMs, project.spec.sampleRate, project.spec.channels) { rate, channels, samples ->
                    val target = writer ?: openAudioSink(output, project.spec.format, rate, channels, project.spec.audioBitrateKbps).also { writer = it }
                    target.write(samples)
                    done += samples.size / channels * 1000L / rate
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            completed = true
        } finally {
            writer?.close()
            if (!completed) NSFileManager.defaultManager.removeItemAtPath(output, null)
        }
        if (writer == null) throw MediaException("no audio stream", Res.string.no_audio_track)
        MediaResult(PlatformFile(output))
    }

    actual suspend fun decodeAudio(file: PlatformFile, maxDurationMs: Long): PcmAudio = withContext(Dispatchers.Default) {
        var rate = 0
        var channels = 0
        var samples = ShortArray(1 shl 16)
        var size = 0
        readPcm(file.url, 0, maxDurationMs) { r, c, chunk ->
            rate = r
            channels = c
            if (size + chunk.size > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + chunk.size))
            chunk.copyInto(samples, size)
            size += chunk.size
        }
        if (rate == 0) throw MediaException("no audio stream", Res.string.no_audio_track)
        PcmAudio(rate, channels, samples.copyOf(size))
    }

    actual suspend fun peaks(file: PlatformFile, perSecond: Int, maxDurationMs: Long): FloatArray = withContext(Dispatchers.Default) {
        val peaks = ArrayList<Float>()
        var filled = 0
        var peak = 0
        readPcm(file.url, 0, maxDurationMs) { rate, channels, samples ->
            val bucket = (rate / perSecond).coerceAtLeast(1)
            for (i in samples.indices step channels) {
                peak = maxOf(peak, abs(samples[i].toInt()))
                if (++filled == bucket) {
                    peaks += peak / 32768f
                    filled = 0
                    peak = 0
                }
            }
        }
        if (filled > 0) peaks += peak / 32768f
        peaks.toFloatArray()
    }

    actual suspend fun encodeAudio(source: PcmSource, format: MediaFormat, bitrateKbps: Int, onProgress: (Float) -> Unit): MediaResult =
        withContext(Dispatchers.Default) {
            val output = tempPath(format.extension)
            val writer = openAudioSink(output, format, source.sampleRate, source.channels, bitrateKbps)
            val chunk = ShortArray(8192 * source.channels)
            var frames = 0L
            var completed = false
            try {
                while (true) {
                    ensureActive()
                    val n = source.read(chunk)
                    if (n <= 0) break
                    writer.write(chunk.copyOf(n))
                    frames += n / source.channels
                    onProgress((frames.toFloat() / source.frames.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
                completed = true
            } finally {
                writer.close()
                if (!completed) NSFileManager.defaultManager.removeItemAtPath(output, null)
            }
            MediaResult(PlatformFile(output))
        }

    // the reader converts to the rate and channel count asked for, 0 keeps the track's own
    private suspend fun readPcm(
        url: NSURL,
        startMs: Long,
        endMs: Long,
        toRate: Int = 0,
        toChannels: Int = 0,
        onChunk: (sampleRate: Int, channels: Int, samples: ShortArray) -> Unit,
    ) {
        val asset = AVURLAsset.URLAssetWithURL(url, null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as AVAssetTrack? ?: throw MediaException("no audio stream", Res.string.no_audio_track)
        val description = track.format
            ?.let { CMAudioFormatDescriptionGetStreamBasicDescription(it) }?.pointed
        val rate = toRate.takeIf { it > 0 } ?: description?.mSampleRate?.roundToInt() ?: 44_100
        val channels = toChannels.takeIf { it > 0 } ?: (description?.mChannelsPerFrame?.toInt() ?: 2).coerceIn(1, 2)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
            AVLinearPCMIsNonInterleaved to false,
            AVNumberOfChannelsKey to channels,
            AVSampleRateKey to rate.toDouble(),
        )
        val reader = AVAssetReader(asset, null)
        val output = AVAssetReaderTrackOutput(track, settings)
        reader.addOutput(output)
        val duration = (CMTimeGetSeconds(asset.duration) * 1000).roundToInt().toLong()
        reader.timeRange = CMTimeRangeMake(cmTime(startMs), cmTime(minOf(endMs, duration) - startMs))
        reader.startReading()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val sample = output.copyNextSampleBuffer() ?: break
                val block = CMSampleBufferGetDataBuffer(sample)
                if (block != null) {
                    val length = CMBlockBufferGetDataLength(block).toInt()
                    val shorts = ShortArray(length / 2)
                    if (shorts.isNotEmpty()) shorts.usePinned { CMBlockBufferCopyDataBytes(block, 0u, length.convert(), it.addressOf(0)) }
                    onChunk(rate, channels, shorts)
                }
                CFRelease(sample)
            }
        } finally {
            reader.cancelReading()
        }
    }
}

private inline fun MediaClip.fadePieces(piece: (fromMs: Long, toMs: Long, from: Float, to: Float) -> Unit) {
    val corners = mutableListOf(atMs, endAtMs)
    if (fadeInMs > 0) corners += atMs + fadeInMs
    if (fadeOutMs > 0) corners += endAtMs - fadeOutMs
    if (fadeInMs > 0 && fadeOutMs > 0) corners += atMs + durationMs * fadeInMs / (fadeInMs + fadeOutMs)
    corners.filter { it in atMs..endAtMs }.distinct().sorted().zipWithNext().forEach { (a, b) -> piece(a, b, fadeAt(a), fadeAt(b)) }
}

private fun place(layer: AVMutableVideoCompositionLayerInstruction, source: AVAssetTrack, clip: MediaClip, frameW: Int, frameH: Int) {
    val oriented = source.preferredTransform
    val natural = source.naturalSize.useContents { CGRectMake(0.0, 0.0, width, height) }
    val at = cmTime(clip.atMs)
    CGRectApplyAffineTransform(natural, oriented).useContents {
        val upright = CGAffineTransformConcat(oriented, CGAffineTransformMakeTranslation(-origin.x, -origin.y))
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val box = clip.box.place(w, h, frameW, frameH, clip.fill)
        val crop = if (clip.fill) coverCrop(w, h, frameW, frameH) else Rect(0f, 0f, w.toFloat(), h.toFloat())
        val scale = (box.width / crop.width).toDouble()
        val cropped = CGAffineTransformConcat(upright, CGAffineTransformMakeTranslation(-crop.left.toDouble(), -crop.top.toDouble()))
        val scaled = CGAffineTransformConcat(cropped, CGAffineTransformMakeScale(scale, scale))
        layer.setTransform(CGAffineTransformConcat(scaled, CGAffineTransformMakeTranslation(box.left.toDouble(), box.top.toDouble())), at)
        val shown = CGRectMake(crop.left.toDouble(), crop.top.toDouble(), crop.width.toDouble(), crop.height.toDouble())
        layer.setCropRectangle(CGRectApplyAffineTransform(shown, CGAffineTransformInvert(upright)), at)
    }
    clip.fadePieces { fromMs, toMs, from, to ->
        if (from == to) {
            layer.setOpacity(clip.opacity * from, cmTime(fromMs))
        } else {
            layer.setOpacityRampFromStartOpacity(clip.opacity * from, clip.opacity * to, CMTimeRangeMake(cmTime(fromMs), cmTime(toMs - fromMs)))
        }
    }
}

private interface AudioSink {
    fun write(samples: ShortArray)

    fun close()
}

private fun openAudioSink(path: String, format: MediaFormat, sampleRate: Int, channels: Int, bitrateKbps: Int): AudioSink =
    if (format == MediaFormat.MP3) Mp3FileWriter(path, sampleRate, channels, bitrateKbps) else AudioFileWriter(path, format, sampleRate, channels, bitrateKbps)

// Core Audio writes no MP3
private class Mp3FileWriter(private val path: String, sampleRate: Int, channels: Int, bitrateKbps: Int) : AudioSink {
    private val rate = Mp3Encoder.nearestRate(sampleRate)
    private val resampler = Resampler(sampleRate, rate, channels)
    private val encoder = Mp3Encoder(rate, channels, Mp3Encoder.nearestBitrate(rate, bitrateKbps))
    private val chunks = ArrayList<ByteArray>()

    override fun write(samples: ShortArray) {
        val resampled = resampler.process(samples, samples.size)
        chunks += encoder.encode(resampled, resampled.size)
    }

    override fun close() {
        chunks += encoder.finish()
        val bytes = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        encoder.infoFrame().copyInto(bytes)
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        data.writeToFile(path, atomically = true)
    }
}

// ExtAudioFile rather than AVAudioFile, it closes deterministically and the file is saved right after
private class AudioFileWriter(path: String, format: MediaFormat, private val sampleRate: Int, private val channels: Int, bitrateKbps: Int) : AudioSink {
    private val ref = memScoped {
        val fileFormat = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = sampleRate.toDouble()
            mChannelsPerFrame = channels.convert()
            when (format) {
                MediaFormat.WAV -> {
                    mFormatID = kAudioFormatLinearPCM
                    mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kLinearPCMFormatFlagIsPacked
                    mBitsPerChannel = 16u
                    mFramesPerPacket = 1u
                    mBytesPerFrame = (2 * channels).convert()
                    mBytesPerPacket = (2 * channels).convert()
                }
                MediaFormat.FLAC -> {
                    mFormatID = kAudioFormatFLAC
                    mFormatFlags = 1u
                }
                else -> mFormatID = kAudioFormatMPEG4AAC
            }
        }
        val type = when (format) {
            MediaFormat.WAV -> kAudioFileWAVEType
            MediaFormat.FLAC -> kAudioFileFLACType
            else -> kAudioFileM4AType
        }
        val holder = alloc<ExtAudioFileRefVar>()
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret<__CFURL>()
        val status = ExtAudioFileCreateWithURL(url, type, fileFormat.ptr, null, kAudioFileFlags_EraseFile, holder.ptr)
        url?.let { CFRelease(it) }
        if (status != 0) throw MediaException("cannot create audio file ($status)")
        val client = alloc<AudioStreamBasicDescription>().apply {
            mSampleRate = sampleRate.toDouble()
            mFormatID = kAudioFormatLinearPCM
            mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kLinearPCMFormatFlagIsPacked
            mChannelsPerFrame = channels.convert()
            mBitsPerChannel = 16u
            mFramesPerPacket = 1u
            mBytesPerFrame = (2 * channels).convert()
            mBytesPerPacket = (2 * channels).convert()
        }
        ExtAudioFileSetProperty(holder.value, kExtAudioFileProperty_ClientDataFormat, sizeOf<AudioStreamBasicDescription>().convert(), client.ptr)
        if (format == MediaFormat.M4A && bitrateKbps > 0) {
            val converter = alloc<AudioConverterRefVar>()
            val size = alloc<UIntVar> { value = sizeOf<AudioConverterRefVar>().convert() }
            ExtAudioFileGetProperty(holder.value, kExtAudioFileProperty_AudioConverter, size.ptr, converter.ptr)
            val bitrate = alloc<UIntVar> { value = (bitrateKbps * 1000).convert() }
            AudioConverterSetProperty(converter.value, kAudioConverterEncodeBitRate, sizeOf<UIntVar>().convert(), bitrate.ptr)
            // the file's converter takes the new bitrate only once its configuration is reset
            val config = alloc<COpaquePointerVar> { value = null }
            ExtAudioFileSetProperty(holder.value, kExtAudioFileProperty_ConverterConfig, sizeOf<COpaquePointerVar>().convert(), config.ptr)
        }
        holder.value
    }

    override fun write(samples: ShortArray) {
        if (samples.isEmpty()) return
        memScoped {
            samples.usePinned { pinned ->
                val list = alloc<AudioBufferList>()
                list.mNumberBuffers = 1u
                list.mBuffers[0].mNumberChannels = channels.convert()
                list.mBuffers[0].mDataByteSize = (samples.size * 2).convert()
                list.mBuffers[0].mData = pinned.addressOf(0)
                ExtAudioFileWrite(ref, (samples.size / channels).convert(), list.ptr)
            }
        }
    }

    override fun close() {
        ExtAudioFileDispose(ref)
    }
}
