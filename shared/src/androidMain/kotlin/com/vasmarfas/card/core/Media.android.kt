package com.vasmarfas.card.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat as CodecFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.vasmarfas.card.resources.*
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

private val PlatformFile.uri: Uri
    get() = when (val file = androidFile) {
        is AndroidFile.UriWrapper -> file.uri
        is AndroidFile.FileWrapper -> Uri.fromFile(file.file)
    }

private fun codecName(mime: String): String = when (mime) {
    MimeTypes.VIDEO_H264 -> "h264"
    MimeTypes.VIDEO_H265 -> "hevc"
    MimeTypes.VIDEO_VP8 -> "vp8"
    MimeTypes.VIDEO_VP9 -> "vp9"
    MimeTypes.VIDEO_AV1 -> "av1"
    MimeTypes.VIDEO_MP4V -> "mpeg4"
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_MPEG -> "mp3"
    MimeTypes.AUDIO_OPUS -> "opus"
    MimeTypes.AUDIO_VORBIS -> "vorbis"
    MimeTypes.AUDIO_FLAC -> "flac"
    MimeTypes.AUDIO_RAW -> "pcm"
    else -> mime.substringAfter('/')
}

actual object MediaEngine {
    actual val formats: Set<MediaFormat> = buildSet {
        add(MediaFormat.MP4)
        add(MediaFormat.M4A)
        add(MediaFormat.MP3)
        add(MediaFormat.WAV)
        add(MediaFormat.FLAC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaFormat.OGG)
    }

    actual val canEdit: Boolean = true

    actual suspend fun probe(file: PlatformFile): MediaInfo = withContext(Dispatchers.IO) {
        val context = AppContextHolder.context
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            fun key(k: Int) = retriever.extractMetadata(k)
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            var videoCodec: String? = null
            var audioCodec: String? = null
            var frameRate = 0.0
            var sampleRate = 0
            var channels = 0
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, file.uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(CodecFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoCodec == null) {
                        videoCodec = codecName(mime)
                        if (format.containsKey(CodecFormat.KEY_FRAME_RATE)) frameRate = format.getNumber(CodecFormat.KEY_FRAME_RATE)?.toDouble() ?: 0.0
                    } else if (mime.startsWith("audio/") && audioCodec == null) {
                        audioCodec = codecName(mime)
                        sampleRate = format.getInteger(CodecFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(CodecFormat.KEY_CHANNEL_COUNT)
                    }
                }
            } finally {
                extractor.release()
            }
            MediaInfo(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                width = w,
                height = h,
                frameRate = frameRate,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                sampleRate = sampleRate,
                channels = channels,
                bitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0,
            )
        } catch (e: RuntimeException) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) throw MediaException(e.message ?: "unreadable file")
            MediaInfo(0, bounds.outWidth, bounds.outHeight, 0.0, "image", null, 0, 0, 0)
        } finally {
            retriever.release()
        }
    }

    actual suspend fun frame(file: PlatformFile, timeMs: Long, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(AppContextHolder.context, file.uri)
            retriever.scaledFrame(timeMs, maxSide)?.asImageBitmap()
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    actual suspend fun frames(file: PlatformFile, fromMs: Long, toMs: Long, fps: Double, maxSide: Int, onFrame: suspend (ImageBitmap) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            withContext(Dispatchers.IO) { retriever.setDataSource(AppContextHolder.context, file.uri) }
            val step = 1000.0 / fps
            var index = 0
            while (true) {
                val at = fromMs + (index * step).roundToInt()
                if (at >= toMs) break
                val bitmap = withContext(Dispatchers.IO) { retriever.scaledFrame(at, maxSide) } ?: break
                onFrame(bitmap.asImageBitmap())
                index++
            }
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.scaledFrame(timeMs: Long, maxSide: Int) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
        } else {
            getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        }

    actual suspend fun export(project: MediaProject, onProgress: (Float) -> Unit): MediaResult {
        val format = project.spec.format
        if (format !in formats) throw MediaException("${format.extension} is not supported on Android")
        val single = project.single
        if (project.spec.copyStreams && format == MediaFormat.MP4 && single?.kind == ClipKind.VIDEO) {
            remux(single, project.spec.keepAudio, onProgress)?.let { return it }
        }
        return if (format.video || format == MediaFormat.M4A) transform(project, onProgress) else transcodeAudio(project, onProgress)
    }

    private suspend fun remux(clip: MediaClip, keepAudio: Boolean, onProgress: (Float) -> Unit): MediaResult? = withContext(Dispatchers.IO) {
        val output = tempPath(MediaFormat.MP4.extension)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(AppContextHolder.context, clip.file.uri, null)
            val target = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val tracks = HashMap<Int, Int>()
            var hasVideo = false
            var bufferSize = 8 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(CodecFormat.KEY_MIME) ?: continue
                val video = mime.startsWith("video/")
                if (!video && !(keepAudio && mime.startsWith("audio/"))) continue
                tracks[i] = target.addTrack(format)
                extractor.selectTrack(i)
                if (format.containsKey(CodecFormat.KEY_MAX_INPUT_SIZE)) bufferSize = maxOf(bufferSize, format.getInteger(CodecFormat.KEY_MAX_INPUT_SIZE))
                if (video) {
                    hasVideo = true
                    if (format.containsKey(CodecFormat.KEY_ROTATION)) target.setOrientationHint(format.getInteger(CodecFormat.KEY_ROTATION))
                }
            }
            if (!hasVideo) return@withContext null
            val startUs = clip.startMs * 1000
            val endUs = clip.endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            target.start()
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var origin = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                if (origin < 0) origin = time
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, time - origin, flags)
                target.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                onProgress(((time - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                extractor.advance()
            }
            target.stop()
            MediaResult(PlatformFile(output))
        } catch (e: IllegalStateException) {
            File(output).delete()
            null
        } catch (e: IllegalArgumentException) {
            File(output).delete()
            null
        } finally {
            extractor.release()
            runCatching { muxer?.release() }
        }
    }

    internal suspend fun probeAll(project: MediaProject): Map<PlatformFile, MediaInfo> =
        project.tracks.flatMap { it.clips }.map { it.file }.distinct().associateWith { probe(it) }

    private suspend fun transform(project: MediaProject, onProgress: (Float) -> Unit): MediaResult {
        val context = AppContextHolder.context
        val spec = project.spec
        val built = composition(project, probeAll(project), video = spec.format.video, audio = spec.keepAudio)
        val output = tempPath(spec.format.extension)
        val bitrate = spec.videoBitrate(built.width, built.height, built.fps, project.durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrateKbps * 1000).build())
                        .setEnableFallback(true)
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(MediaException(exportException.message ?: exportException.errorCodeName))
                    }
                })
                .build()
            transformer.start(built.composition, output)
            val holder = ProgressHolder()
            try {
                while (!done.isCompleted) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    withTimeoutOrNull(200) { done.await() }
                }
                done.await()
            } catch (e: Throwable) {
                transformer.cancel()
                File(output).delete()
                throw e
            }
        }
        return MediaResult(PlatformFile(output))
    }

    internal class Built(val composition: Composition, val width: Int, val height: Int, val fps: Int, val layers: LayerHolder)

    // sequence 0 is drawn on top and sets the output timestamps, so it is a transparent clock at the project rate
    internal fun composition(project: MediaProject, infos: Map<PlatformFile, MediaInfo>, video: Boolean, audio: Boolean, maxSide: Int = 0, clock: Boolean = true): Built {
        val spec = project.spec
        val first = project.firstPicture?.let { infos[it.file] }
        var (width, height) = spec.frameSize(first)
        if (maxSide > 0 && maxOf(width, height) > maxSide) {
            val scale = maxSide.toDouble() / maxOf(width, height)
            width = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            height = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        }
        val fps = spec.frameRate(first)
        val total = project.durationMs.coerceAtLeast(1)
        val layers = if (video) project.pictures.asReversed() else emptyList()
        val sequences = mutableListOf<EditedMediaItemSequence>()
        if (video) {
            if (clock) sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(still(transparent(), total, fps, width, height)).build()
            layers.forEach { sequences += pictureSequence(it, infos, total, fps, width, height, gaps = !clock) }
            if (layers.isEmpty() && !clock) sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(still(transparent(), total, fps, width, height)).build()
        }
        if (audio) {
            project.tracks.filter { !it.muted }.forEach { track ->
                val sounds = track.clips.filter { it.kind != ClipKind.IMAGE && it.volume > 0f && (it.kind == ClipKind.AUDIO || infos[it.file]?.hasAudio == true) }
                if (sounds.isNotEmpty()) sequences += soundSequence(sounds, infos, total, spec.channels)
            }
        }
        if (sequences.isEmpty()) throw MediaException("nothing to render")
        val builder = Composition.Builder(sequences)
        val holder = LayerHolder(layers)
        if (video) builder.setVideoCompositorSettings(Layers(holder, width, height, if (clock) 1 else 0))
        if (audio && spec.sampleRate > 0) builder.setEffects(Effects(listOf(SonicAudioProcessor().apply { setOutputSampleRateHz(spec.sampleRate) }), emptyList()))
        return Built(builder.build(), width, height, fps, holder)
    }

    private fun pictureSequence(track: MediaTrack, infos: Map<PlatformFile, MediaInfo>, total: Long, fps: Int, width: Int, height: Int, gaps: Boolean): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        val frameMs = 1000L / fps
        var cursor = 0L
        track.clips.sortedBy { it.atMs }.forEach { clip ->
            if (clip.atMs - cursor >= frameMs) {
                if (gaps) builder.addGap((clip.atMs - cursor) * 1000) else builder.addItem(still(transparent(), clip.atMs - cursor, fps, width, height))
            }
            val item = MediaItem.Builder().setUri(clip.file.uri)
            if (clip.kind == ClipKind.IMAGE) {
                item.setImageDurationMs(clip.durationMs)
                AppContextHolder.context.contentResolver.getType(clip.file.uri)?.let { item.setMimeType(it) }
            } else {
                item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build())
            }
            val layout = if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            val sourceMs = if (clip.kind == ClipKind.IMAGE) clip.durationMs else infos[clip.file]?.durationMs ?: clip.endMs
            builder.addItem(
                EditedMediaItem.Builder(item.build())
                    .setRemoveAudio(true)
                    .setDurationUs(sourceMs * 1000)
                    .apply { if (clip.kind == ClipKind.IMAGE) setFrameRate(fps) }
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, layout))))
                    .build(),
            )
            cursor = clip.endAtMs
        }
        if (total - cursor >= frameMs) {
            if (gaps) builder.addGap((total - cursor) * 1000) else builder.addItem(still(transparent(), total - cursor, fps, width, height))
        }
        return builder.build()
    }

    private fun still(file: File, durationMs: Long, fps: Int, width: Int, height: Int): EditedMediaItem {
        val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(durationMs).setMimeType(MimeTypes.IMAGE_PNG).build()
        return EditedMediaItem.Builder(item)
            .setDurationUs(durationMs * 1000)
            .setFrameRate(fps)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_STRETCH_TO_FIT))))
            .build()
    }

    private fun soundSequence(clips: List<MediaClip>, infos: Map<PlatformFile, MediaInfo>, total: Long, channels: Int): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursor = 0L
        clips.sortedBy { it.atMs }.forEach { clip ->
            if (clip.atMs > cursor) builder.addGap((clip.atMs - cursor) * 1000)
            val item = MediaItem.Builder().setUri(clip.file.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build())
                .build()
            val gain = GainProcessor(clip.volume, clip.fadeInMs, clip.fadeOutMs, clip.durationMs)
            builder.addItem(
                EditedMediaItem.Builder(item)
                    .setRemoveVideo(true)
                    .setDurationUs((infos[clip.file]?.durationMs ?: clip.endMs) * 1000)
                    .setEffects(Effects(listOf(gain, channelMixer(channels)), emptyList()))
                    .build(),
            )
            cursor = clip.endAtMs
        }
        if (total > cursor) builder.addGap((total - cursor) * 1000)
        return builder.build()
    }

    private var transparentStill: File? = null

    private fun transparent(): File = transparentStill?.takeIf { it.exists() } ?: File(AppContextHolder.context.cacheDir, "transparent-16.png").also { file ->
        file.outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        transparentStill = file
    }

    private suspend fun transcodeAudio(project: MediaProject, onProgress: (Float) -> Unit): MediaResult = withContext(Dispatchers.IO) {
        val context = AppContextHolder.context
        val spec = project.spec
        val output = tempPath(spec.format.extension)
        var sink: PcmSink? = null
        var targetRate = 0
        var targetChannels = 0
        val clips = project.sounds.sortedBy { it.atMs }
        val total = clips.sumOf { it.durationMs }.coerceAtLeast(1)
        var doneMs = 0L
        try {
            clips.forEach { clip ->
                var resampler: Resampler? = null
                var sourceChannels = 0
                var frames = 0L
                var rate = 0
                decodePcm(
                    context, clip.file.uri, clip.startMs * 1000, clip.endMs * 1000,
                    onFormat = { r, c ->
                        if (sink == null) {
                            targetRate = when {
                                spec.format == MediaFormat.OGG -> 48_000
                                spec.format == MediaFormat.MP3 -> Mp3Encoder.nearestRate(if (spec.sampleRate > 0) spec.sampleRate else r)
                                spec.sampleRate > 0 -> spec.sampleRate
                                else -> r
                            }
                            targetChannels = if (spec.channels > 0) spec.channels else c
                            sink = openSink(spec.format, output, targetRate, targetChannels, spec.audioBitrateKbps)
                        }
                        rate = r
                        sourceChannels = c
                        resampler = Resampler(r, targetRate, targetChannels)
                    },
                    onChunk = { samples, count ->
                        val mapped = remapChannels(samples, count, sourceChannels, targetChannels)
                        applyGain(mapped, targetChannels, frames, rate, clip)
                        frames += mapped.size / targetChannels
                        val out = resampler!!.process(mapped, mapped.size)
                        sink!!.write(out, out.size)
                        onProgress(((doneMs + frames * 1000 / rate) / total.toFloat()).coerceIn(0f, 1f))
                        true
                    },
                )
                doneMs += clip.durationMs
            }
            sink?.finish() ?: throw MediaException("no audio stream", Res.string.no_audio_track)
        } catch (e: Throwable) {
            runCatching { sink?.finish() }
            File(output).delete()
            throw e
        }
        MediaResult(PlatformFile(output))
    }

    actual suspend fun peaks(file: PlatformFile, perSecond: Int, maxDurationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val peaks = ArrayList<Float>()
        var bucket = 1
        var channels = 1
        var filled = 0
        var peak = 0
        decodePcm(
            AppContextHolder.context, file.uri, 0, maxDurationMs * 1000,
            onFormat = { rate, c ->
                bucket = (rate / perSecond).coerceAtLeast(1)
                channels = c
            },
            onChunk = { chunk, count ->
                for (i in 0 until count step channels) {
                    peak = maxOf(peak, abs(chunk[i].toInt()))
                    if (++filled == bucket) {
                        peaks += peak / 32768f
                        filled = 0
                        peak = 0
                    }
                }
                true
            },
        )
        if (filled > 0) peaks += peak / 32768f
        peaks.toFloatArray()
    }

    actual suspend fun decodeAudio(file: PlatformFile, maxDurationMs: Long): PcmAudio = withContext(Dispatchers.IO) {
        var rate = 0
        var channels = 0
        var samples = ShortArray(1 shl 16)
        var size = 0
        decodePcm(
            AppContextHolder.context, file.uri, 0, maxDurationMs * 1000,
            onFormat = { r, c ->
                rate = r
                channels = c
            },
            onChunk = { chunk, count ->
                if (size + count > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + count))
                chunk.copyInto(samples, size, 0, count)
                size += count
                true
            },
        )
        if (rate == 0) throw MediaException("no audio stream", Res.string.no_audio_track)
        PcmAudio(rate, channels, samples.copyOf(size))
    }

    actual suspend fun encodeAudio(source: PcmSource, format: MediaFormat, bitrateKbps: Int, onProgress: (Float) -> Unit): MediaResult =
        withContext(Dispatchers.IO) {
            val output = tempPath(format.extension)
            val rate = when (format) {
                MediaFormat.OGG -> 48_000
                MediaFormat.MP3 -> Mp3Encoder.nearestRate(source.sampleRate)
                else -> source.sampleRate
            }
            val sink = openSink(format, output, rate, source.channels, bitrateKbps)
            val resampler = Resampler(source.sampleRate, rate, source.channels)
            val chunk = ShortArray(4096 * source.channels)
            var frames = 0L
            var completed = false
            try {
                while (true) {
                    ensureActive()
                    val n = source.read(chunk)
                    if (n <= 0) break
                    val out = resampler.process(chunk, n)
                    sink.write(out, out.size)
                    frames += n / source.channels
                    onProgress((frames.toFloat() / source.frames.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
                completed = true
            } finally {
                sink.finish()
                if (!completed) File(output).delete()
            }
            MediaResult(PlatformFile(output))
        }
}

private fun remapChannels(samples: ShortArray, count: Int, from: Int, to: Int): ShortArray {
    if (from == to) return samples.copyOf(count)
    val frames = count / from
    val out = ShortArray(frames * to)
    for (f in 0 until frames) {
        if (to == 1) {
            out[f] = ((samples[f * 2].toInt() + samples[f * 2 + 1].toInt()) / 2).toShort()
        } else {
            out[f * 2] = samples[f]
            out[f * 2 + 1] = samples[f]
        }
    }
    return out
}

private fun applyGain(samples: ShortArray, channels: Int, startFrame: Long, rate: Int, clip: MediaClip) {
    if (clip.volume == 1f && clip.fadeInMs <= 0 && clip.fadeOutMs <= 0) return
    val frames = samples.size / channels
    for (f in 0 until frames) {
        val gain = fadeGain((startFrame + f) * 1000.0 / rate, clip.volume, clip.fadeInMs, clip.fadeOutMs, clip.durationMs)
        for (c in 0 until channels) {
            val i = f * channels + c
            samples[i] = (samples[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

private fun fadeGain(ms: Double, volume: Float, fadeInMs: Long, fadeOutMs: Long, durationMs: Long): Float {
    var gain = volume
    if (fadeInMs > 0 && ms < fadeInMs) gain *= (ms / fadeInMs).toFloat()
    if (fadeOutMs > 0 && ms > durationMs - fadeOutMs) gain *= ((durationMs - ms) / fadeOutMs).coerceAtLeast(0.0).toFloat()
    return gain
}

// the mixer takes its channel count from the first clip, a mono clip at the start would make the whole
// export mono
private fun channelMixer(channels: Int): AudioProcessor = ChannelMixingAudioProcessor().apply {
    val output = if (channels == 1) 1 else 2
    for (input in 1..6) {
        putChannelMixingMatrix(if (input <= 2) ChannelMixingMatrix.createForConstantGain(input, output) else ChannelMixingMatrix.createForConstantPower(input, output))
    }
}

// the position restarts at zero after every flush, so it runs on the clip's own timeline
private class GainProcessor(
    private val volume: Float,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val durationMs: Long,
) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val output = replaceOutputBuffer(remaining)
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = format.channelCount * if (float) 4 else 2
        while (inputBuffer.remaining() >= frameBytes) {
            val gain = fadeGain(frames * 1000.0 / format.sampleRate, volume, fadeInMs, fadeOutMs, durationMs)
            repeat(format.channelCount) {
                if (float) {
                    output.putFloat(inputBuffer.float * gain)
                } else {
                    output.putShort((inputBuffer.short * gain).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            frames++
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        frames = 0
    }
}

private val hiddenLayer = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

internal class LayerHolder(@Volatile var layers: List<MediaTrack>)

private class Layers(private val holder: LayerHolder, private val width: Int, private val height: Int, private val first: Int) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val clip = holder.layers.getOrNull(inputId - first)?.clips?.firstOrNull { ms >= it.atMs && ms < it.endAtMs } ?: return hiddenLayer
        return StaticOverlaySettings.Builder()
            .setAlphaScale((clip.opacity * clip.fadeAt(ms)).coerceIn(0f, 1f))
            .setScale(clip.box.scale, clip.box.scale)
            .setBackgroundFrameAnchor(clip.box.x * 2 - 1, 1 - clip.box.y * 2)
            .build()
    }
}
