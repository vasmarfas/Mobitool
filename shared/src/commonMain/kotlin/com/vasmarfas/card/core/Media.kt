package com.vasmarfas.card.core

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.resources.*
import io.github.vinceglb.filekit.PlatformFile
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

enum class MediaFormat(val extension: String, val mimeType: String, val video: Boolean) {
    MP4("mp4", "video/mp4", true),
    WEBM("webm", "video/webm", true),
    MKV("mkv", "video/x-matroska", true),
    MOV("mov", "video/quicktime", true),
    MP3("mp3", "audio/mpeg", false),
    M4A("m4a", "audio/mp4", false),
    WAV("wav", "audio/wav", false),
    FLAC("flac", "audio/flac", false),
    OGG("ogg", "audio/ogg", false),
}

class MediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val videoCodec: String?,
    val audioCodec: String?,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Long,
) {
    val hasVideo: Boolean get() = videoCodec != null
    val hasAudio: Boolean get() = audioCodec != null
}

enum class ClipKind { VIDEO, IMAGE, AUDIO }

// x and y are the centre, scale the size, all in fractions of the frame. With fill the picture is
// cropped to the frame's proportions first
data class ClipBox(val x: Float = 0.5f, val y: Float = 0.5f, val scale: Float = 1f) {
    fun place(width: Int, height: Int, frameWidth: Int, frameHeight: Int, fill: Boolean): Rect {
        val (w, h) = if (fill || width <= 0 || height <= 0) {
            frameWidth * scale to frameHeight * scale
        } else {
            val fit = minOf(frameWidth.toFloat() / width, frameHeight.toFloat() / height)
            width * fit * scale to height * fit * scale
        }
        val cx = x * frameWidth
        val cy = y * frameHeight
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}

fun coverCrop(width: Int, height: Int, frameWidth: Int, frameHeight: Int): Rect {
    val frame = frameWidth.toFloat() / frameHeight
    return if (width.toFloat() / height > frame) {
        val w = height * frame
        Rect((width - w) / 2, 0f, (width + w) / 2, height.toFloat())
    } else {
        val h = width / frame
        Rect(0f, (height - h) / 2, width.toFloat(), (height + h) / 2)
    }
}

// images have no source time, endMs is their duration
data class MediaClip(
    val file: PlatformFile,
    val kind: ClipKind,
    val startMs: Long,
    val endMs: Long,
    val atMs: Long = 0,
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val opacity: Float = 1f,
    val box: ClipBox = ClipBox(),
    val fill: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs

    val endAtMs: Long get() = atMs + durationMs

    fun fadeAt(ms: Long): Float {
        val into = ms - atMs
        var factor = 1f
        if (fadeInMs > 0 && into < fadeInMs) factor = (into.toFloat() / fadeInMs).coerceIn(0f, 1f)
        if (fadeOutMs > 0 && into > durationMs - fadeOutMs) factor = minOf(factor, ((durationMs - into).toFloat() / fadeOutMs).coerceIn(0f, 1f))
        return factor
    }
}

enum class TrackKind { VIDEO, AUDIO }

// clips on a track never overlap. Video tracks stack in list order, the first one at the bottom
data class MediaTrack(val kind: TrackKind, val clips: List<MediaClip>, val muted: Boolean = false, val hidden: Boolean = false)

enum class VideoQuality { HIGH, MEDIUM, LOW }

// 0 in a size or rate field means take it from the first clip
data class MediaSpec(
    val format: MediaFormat,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 0,
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val targetBytes: Long = 0,
    val audioBitrateKbps: Int = 192,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val keepAudio: Boolean = true,
    val copyStreams: Boolean = false,
) {
    // H.264 wants even sides
    fun frameSize(source: MediaInfo?): Pair<Int, Int> {
        val sourceW = source?.width?.takeIf { it > 0 } ?: 1280
        val sourceH = source?.height?.takeIf { it > 0 } ?: 720
        val (w, h) = when {
            width > 0 && height > 0 -> width to height
            height > 0 -> (sourceW.toLong() * height / sourceH).toInt() to height
            width > 0 -> width to (sourceH.toLong() * width / sourceW).toInt()
            else -> sourceW to sourceH
        }
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }

    fun frameRate(source: MediaInfo?): Int = when {
        frameRate > 0 -> frameRate
        source != null && source.frameRate > 0 -> source.frameRate.roundToInt().coerceIn(1, 60)
        else -> 30
    }
}

data class MediaProject(val tracks: List<MediaTrack>, val spec: MediaSpec) {
    constructor(clip: MediaClip, spec: MediaSpec) : this(listOf(MediaTrack(if (clip.kind == ClipKind.AUDIO) TrackKind.AUDIO else TrackKind.VIDEO, listOf(clip))), spec)

    val durationMs: Long get() = tracks.maxOfOrNull { track -> track.clips.maxOfOrNull { it.endAtMs } ?: 0 } ?: 0

    val pictures: List<MediaTrack> get() = tracks.filter { it.kind == TrackKind.VIDEO && !it.hidden }

    val sounds: List<MediaClip> get() = tracks.filter { !it.muted }.flatMap { track -> track.clips.filter { it.kind != ClipKind.IMAGE && it.volume > 0f } }

    val firstPicture: MediaClip? get() = pictures.flatMap { it.clips }.minByOrNull { it.atMs }

    val single: MediaClip? get() = tracks.singleOrNull()?.clips?.singleOrNull()
}

// a stereo hour at 48 kHz is 330 MB, callers cap the length
class PcmAudio(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames: Int get() = samples.size / channels
    val durationMs: Long get() = frames * 1000L / sampleRate
}

// read fills whole frames and returns the number of samples written, 0 at the end
class PcmSource(val sampleRate: Int, val channels: Int, val frames: Long, val read: (ShortArray) -> Int)

fun PcmAudio.asSource(): PcmSource {
    var offset = 0
    return PcmSource(sampleRate, channels, frames.toLong()) { buffer ->
        val n = minOf(buffer.size / channels * channels, samples.size - offset)
        samples.copyInto(buffer, 0, offset, offset + n)
        offset += n
        n
    }
}

class MediaException(message: String, val reason: StringResource = Res.string.media_failed) : Exception(message)

expect class MediaResult {
    val size: Long
}

expect suspend fun MediaResult.save(fileName: String): Boolean

expect suspend fun MediaResult.readBytes(): ByteArray

expect fun MediaResult.discard()

expect fun MediaResult.asFile(name: String): PlatformFile

expect object MediaEngine {
    val formats: Set<MediaFormat>

    val canEdit: Boolean

    suspend fun probe(file: PlatformFile): MediaInfo

    suspend fun frame(file: PlatformFile, timeMs: Long, maxSide: Int): ImageBitmap?

    suspend fun frames(file: PlatformFile, fromMs: Long, toMs: Long, fps: Double, maxSide: Int, onFrame: suspend (ImageBitmap) -> Unit)

    suspend fun export(project: MediaProject, onProgress: (Float) -> Unit): MediaResult

    suspend fun decodeAudio(file: PlatformFile, maxDurationMs: Long): PcmAudio

    suspend fun peaks(file: PlatformFile, perSecond: Int, maxDurationMs: Long): FloatArray

    suspend fun encodeAudio(source: PcmSource, format: MediaFormat, bitrateKbps: Int, onProgress: (Float) -> Unit): MediaResult
}

// bits per pixel per frame for H.264, VP9 gets by with about 60 % of that. A size budget wins over
// the quality step, 5 % of it goes to the container
fun MediaSpec.videoBitrate(width: Int, height: Int, fps: Int, durationMs: Long): Long {
    if (targetBytes > 0 && durationMs > 0) {
        val total = targetBytes * 8 * 1000 / durationMs * 95 / 100
        val audio = if (keepAudio) audioBitrateKbps * 1000L else 0L
        return (total - audio).coerceAtLeast(80_000)
    }
    val bpp = when (quality) {
        VideoQuality.HIGH -> 0.12
        VideoQuality.MEDIUM -> 0.07
        VideoQuality.LOW -> 0.04
    } * if (format == MediaFormat.WEBM) 0.6 else 1.0
    return (width.toDouble() * height * fps * bpp).toLong().coerceAtLeast(150_000)
}
