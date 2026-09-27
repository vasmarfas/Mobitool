package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.media.ImageContainer
import com.vasmarfas.card.tools.media.ImageMetadata
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.ffprobe
import org.bytedeco.javacpp.Loader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PEAK_RATE = 8_000

actual object MediaEngine {
    private val programs by lazy {
        runCatching { program("ffmpeg") { Loader.load(ffmpeg::class.java) } to program("ffprobe") { Loader.load(ffprobe::class.java) } }
    }
    internal val ffmpegPath: String get() = programs.getOrElse { throw loadFailure(it) }.first
    private val ffprobePath: String get() = programs.getOrElse { throw loadFailure(it) }.second

    actual val formats: Set<MediaFormat> = MediaFormat.entries.toSet()

    actual val canEdit: Boolean = true

    actual suspend fun probe(file: PlatformFile): MediaInfo {
        val json = run(listOf(ffprobePath, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath()))
        return FfmpegArgs.parseProbe(Net.json.parseToJsonElement(json.decodeToString()).jsonObject)
    }

    actual suspend fun frame(file: PlatformFile, timeMs: Long, maxSide: Int): ImageBitmap? {
        val png = runCatching {
            run(
                listOf(
                    ffmpegPath, "-hide_banner", "-nostdin", "-ss", FfmpegArgs.seconds(timeMs), "-i", file.absolutePath(), "-frames:v", "1",
                    "-vf", "scale='min($maxSide,iw)':'min($maxSide,ih)':force_original_aspect_ratio=decrease", "-f", "image2pipe", "-c:v", "png", "pipe:1",
                ),
            )
        }.getOrNull()
        if (png == null || png.isEmpty()) return null
        return decodeRawImage(png)?.bitmap
    }

    actual suspend fun frames(file: PlatformFile, fromMs: Long, toMs: Long, fps: Double, maxSide: Int, onFrame: suspend (ImageBitmap) -> Unit) {
        val info = probe(file)
        if (!info.hasVideo) throw MediaException("no video stream")
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(info.width, info.height))
        val w = (info.width * scale).roundToInt() / 2 * 2
        val h = (info.height * scale).roundToInt() / 2 * 2
        val command = listOf(
            ffmpegPath, "-hide_banner", "-nostdin", "-ss", FfmpegArgs.seconds(fromMs), "-t", FfmpegArgs.seconds(toMs - fromMs),
            "-i", file.absolutePath(), "-vf", "fps=${fps.fmt(3)},scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1",
        )
        execute(command) { stdout ->
            val frame = ByteArray(w * h * 4)
            while (readFully(stdout, frame)) onFrame(rgbaBitmap(frame, w, h))
        }
    }

    actual suspend fun export(project: MediaProject, onProgress: (Float) -> Unit): MediaResult {
        val infos = probeAll(project)
        val output = tempPath(project.spec.format.extension)
        val args = FfmpegArgs.export(project, infos, output)
        val total = project.durationMs.coerceAtLeast(1)
        try {
            execute(listOf(ffmpegPath) + args) { stdout -> followProgress(stdout, total, onProgress) }
        } catch (e: Throwable) {
            File(output).delete()
            throw e
        }
        return MediaResult(PlatformFile(output))
    }

    internal suspend fun probeAll(project: MediaProject): Map<PlatformFile, MediaInfo> =
        project.tracks.flatMap { it.clips }.map { it.file }.distinct().associateWith { probe(it) }

    internal suspend fun heifImage(bytes: ByteArray): ImageBitmap? {
        if (ImageMetadata.detect(bytes) != ImageContainer.HEIF) return null
        val path = tempPath("heif")
        return try {
            File(path).writeBytes(bytes)
            val probe = Net.json.parseToJsonElement(run(listOf(ffprobePath, "-v", "error", "-show_stream_groups", "-show_streams", "-of", "json", path)).decodeToString()).jsonObject
            val grid = probe["stream_groups"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["type"]?.jsonPrimitive?.content == "Tile Grid" }
                ?.get("components")?.jsonArray?.firstOrNull()?.jsonObject
            val picture = grid ?: probe["streams"]?.jsonArray?.map { it.jsonObject }
                ?.filter { it["codec_type"]?.jsonPrimitive?.content == "video" }
                ?.maxByOrNull { (it["width"]?.jsonPrimitive?.intOrNull ?: 0) * (it["height"]?.jsonPrimitive?.intOrNull ?: 0) }
            val size = picture?.let { it["width"]?.jsonPrimitive?.intOrNull to it["height"]?.jsonPrimitive?.intOrNull }
            val png = run(
                listOf(
                    ffmpegPath, "-hide_banner", "-nostdin", "-noautorotate", "-i", path, "-frames:v", "1",
                    "-pix_fmt", "rgba", "-f", "image2pipe", "-c:v", "png", "pipe:1",
                ),
            )
            skiaDecode(png)?.takeIf { (it.width to it.height) == size }
        } catch (e: MediaException) {
            null
        } finally {
            File(path).delete()
        }
    }

    actual suspend fun peaks(file: PlatformFile, perSecond: Int, maxDurationMs: Long): FloatArray {
        val bytes = run(
            listOf(
                ffmpegPath, "-hide_banner", "-nostdin", "-i", file.absolutePath(), "-t", FfmpegArgs.seconds(maxDurationMs), "-vn",
                "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", PEAK_RATE.toString(), "pipe:1",
            ),
        )
        val bucket = (PEAK_RATE / perSecond).coerceAtLeast(1)
        val samples = bytes.size / 2
        return withContext(Dispatchers.Default) {
            FloatArray((samples + bucket - 1) / bucket) { b ->
                var peak = 0
                for (i in b * bucket until minOf(samples, (b + 1) * bucket)) {
                    val v = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
                    peak = maxOf(peak, abs(v))
                }
                peak / 32768f
            }
        }
    }

    actual suspend fun decodeAudio(file: PlatformFile, maxDurationMs: Long): PcmAudio {
        val info = probe(file)
        if (!info.hasAudio) throw MediaException("no audio stream", Res.string.no_audio_track)
        val channels = info.channels.coerceIn(1, 2)
        val rate = info.sampleRate.takeIf { it in 8_000..48_000 } ?: FfmpegArgs.SAMPLE_RATE
        val bytes = run(
            listOf(
                ffmpegPath, "-hide_banner", "-nostdin", "-i", file.absolutePath(), "-t", FfmpegArgs.seconds(maxDurationMs), "-vn",
                "-f", "s16le", "-acodec", "pcm_s16le", "-ac", channels.toString(), "-ar", rate.toString(), "pipe:1",
            ),
        )
        val samples = withContext(Dispatchers.Default) {
            ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
        }
        return PcmAudio(rate, channels, samples)
    }

    actual suspend fun encodeAudio(source: PcmSource, format: MediaFormat, bitrateKbps: Int, onProgress: (Float) -> Unit): MediaResult {
        val output = tempPath(format.extension)
        val spec = MediaSpec(format, audioBitrateKbps = bitrateKbps)
        val command = listOf(
            ffmpegPath, "-hide_banner", "-y", "-progress", "pipe:1", "-nostats",
            "-f", "s16le", "-ar", source.sampleRate.toString(), "-ac", source.channels.toString(), "-i", "pipe:0",
        ) + FfmpegArgs.audioCodec(spec) + FfmpegArgs.containerFlags(format) + output
        val totalMs = (source.frames * 1000 / source.sampleRate).coerceAtLeast(1)
        val feed: (OutputStream) -> Unit = { stream ->
            val samples = ShortArray(8192 * source.channels)
            val bytes = ByteArray(samples.size * 2)
            while (true) {
                val n = source.read(samples)
                if (n <= 0) break
                for (i in 0 until n) {
                    bytes[i * 2] = samples[i].toByte()
                    bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                }
                stream.write(bytes, 0, n * 2)
            }
        }
        try {
            execute(command, feed) { stdout -> followProgress(stdout, totalMs, onProgress) }
        } catch (e: Throwable) {
            File(output).delete()
            throw e
        }
        return MediaResult(PlatformFile(output))
    }

    private suspend fun run(command: List<String>): ByteArray {
        var out = ByteArray(0)
        execute(command) { out = it.readBytes() }
        return out
    }

    // stderr is drained in parallel or ffmpeg blocks once the pipe fills, only its tail is kept. Pipe reads
    // block, so cancellation kills the process from a separate coroutine
    internal suspend fun execute(command: List<String>, input: ((OutputStream) -> Unit)? = null, readStdout: suspend (InputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).start()
            val errors = StringBuilder()
            var finished = false
            try {
                coroutineScope {
                    val watchdog = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!finished) process.destroyForcibly()
                        }
                    }
                    launch {
                        process.errorStream.bufferedReader().forEachLine { line ->
                            errors.append(line).append('\n')
                            if (errors.length > 8_000) errors.deleteRange(0, errors.length - 4_000)
                        }
                    }
                    launch {
                        process.outputStream.use { stream -> input?.invoke(stream) }
                    }
                    readStdout(process.inputStream)
                    finished = true
                    watchdog.cancel()
                }
                val code = process.waitFor()
                if (code != 0) {
                    val reason = errors.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("[") } ?: "exit code $code"
                    throw MediaException(reason)
                }
            } finally {
                // waited out, a dying process still holds its output file open on Windows
                if (!finished && process.isAlive) process.destroyForcibly().waitFor()
            }
        }

    private fun followProgress(stdout: InputStream, totalMs: Long, onProgress: (Float) -> Unit) {
        stdout.bufferedReader().forEachLine { line ->
            if (line.startsWith("out_time_us=")) {
                val us = line.substringAfter('=').toLongOrNull() ?: return@forEachLine
                onProgress((us / 1000.0 / totalMs).toFloat().coerceIn(0f, 1f))
            }
        }
    }

    internal fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}

// the Mac App Store build ships signed programs next to their libraries, a sandbox cannot run ones unpacked from a jar
private fun program(name: String, unpacked: () -> String): String =
    System.getProperty("mobitool.ffmpeg.dir")?.let { File(it, name) }?.takeIf { it.canExecute() }?.path ?: unpacked()

private fun loadFailure(e: Throwable): Throwable {
    if (e !is UnsatisfiedLinkError) return e
    val library = Regex("""lib[\w.+-]+\.so[\d.]*(?=: cannot open)""").find(e.message.orEmpty())?.value ?: return MediaException(e.message ?: e.toString())
    // not a MediaException: this text names the library and is shown as it is
    return IllegalStateException(
        Tr(
            "FFmpeg cannot start without the system library $library. Install it with the package manager.",
            "FFmpeg не запускается без системной библиотеки $library. Установите её через менеджер пакетов.",
        )[appLang],
    )
}

internal fun rgbaBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val pixels = IntArray(width * height) { i ->
        val o = i * 4
        ((rgba[o + 3].toInt() and 0xFF) shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or
            ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
    }
    return imageBitmapOf(pixels, width, height)
}
