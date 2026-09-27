package com.vasmarfas.card.core

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat as CodecFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.vasmarfas.card.resources.*
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TIMEOUT_US = 10_000L

internal suspend fun decodePcm(
    context: Context,
    uri: Uri,
    startUs: Long,
    endUs: Long,
    onFormat: (sampleRate: Int, channels: Int) -> Unit,
    onChunk: (ShortArray, Int) -> Boolean,
) {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(CodecFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: run {
        extractor.release()
        throw MediaException("no audio stream", Res.string.no_audio_track)
    }
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    format.setInteger(CodecFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    val codec = MediaCodec.createDecoderByType(format.getString(CodecFormat.KEY_MIME)!!)
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(CodecFormat.KEY_SAMPLE_RATE)
        var sourceChannels = format.getInteger(CodecFormat.KEY_CHANNEL_COUNT)
        var float = false
        onFormat(rate, sourceChannels.coerceAtMost(2))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var chunk = ShortArray(0)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > endUs) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val changed = codec.outputFormat
                rate = changed.getInteger(CodecFormat.KEY_SAMPLE_RATE)
                sourceChannels = changed.getInteger(CodecFormat.KEY_CHANNEL_COUNT)
                float = changed.containsKey(CodecFormat.KEY_PCM_ENCODING) &&
                    changed.getInteger(CodecFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                onFormat(rate, sourceChannels.coerceAtMost(2))
            } else if (out >= 0) {
                val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val frameCount = info.size / ((if (float) 4 else 2) * sourceChannels)
                var skip = 0
                if (info.presentationTimeUs < startUs) skip = ((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt().coerceAtMost(frameCount)
                var keep = frameCount - skip
                val end = info.presentationTimeUs + frameCount * 1_000_000L / rate
                if (end > endUs) keep = (keep - ((end - endUs) * rate / 1_000_000).toInt()).coerceAtLeast(0)
                val channels = sourceChannels.coerceAtMost(2)
                if (chunk.size < keep * channels) chunk = ShortArray(keep * channels)
                val raw = if (float) FloatArray(frameCount * sourceChannels).also { buffer.asFloatBuffer().get(it) } else null
                val shorts = if (float) null else ShortArray(frameCount * sourceChannels).also { buffer.asShortBuffer().get(it) }
                for (f in 0 until keep) {
                    val src = (f + skip) * sourceChannels
                    for (c in 0 until channels) {
                        chunk[f * channels + c] = when {
                            sourceChannels == 6 -> downmix(raw, shorts, src, c)
                            raw != null -> (raw[src + c].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                            else -> shorts!![src + c]
                        }
                    }
                }
                codec.releaseOutputBuffer(out, false)
                if (keep > 0 && !onChunk(chunk, keep * channels)) break
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    } finally {
        codec.release()
        extractor.release()
    }
}

// ITU downmix: front + 0.707 * (centre + surround), LFE dropped
private fun downmix(float: FloatArray?, shorts: ShortArray?, src: Int, channel: Int): Short {
    fun at(i: Int): Float = float?.get(src + i) ?: (shorts!![src + i] / 32768f)
    val mixed = (at(channel) + 0.707f * at(2) + 0.707f * at(4 + channel)) / 2.414f
    return (mixed.coerceIn(-1f, 1f) * 32767).toInt().toShort()
}

internal interface PcmSink {
    fun write(samples: ShortArray, count: Int)
    fun finish()
}

internal fun openSink(format: MediaFormat, path: String, sampleRate: Int, channels: Int, bitrateKbps: Int): PcmSink = when (format) {
    MediaFormat.WAV -> WavSink(path, sampleRate, channels)
    MediaFormat.MP3 -> Mp3Sink(path, sampleRate, channels, bitrateKbps)
    MediaFormat.FLAC -> EncoderSink(path, CodecFormat.MIMETYPE_AUDIO_FLAC, null, sampleRate, channels, 0)
    MediaFormat.OGG -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        EncoderSink(path, CodecFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, sampleRate, channels, bitrateKbps)
    } else {
        throw MediaException("Ogg Opus needs Android 10")
    }
    else -> EncoderSink(path, CodecFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, sampleRate, channels, bitrateKbps)
}

// MediaCodec has no MP3 encoder. The Info frame goes over the placeholder at the start once the totals are known
private class Mp3Sink(private val path: String, sampleRate: Int, channels: Int, bitrateKbps: Int) : PcmSink {
    private val encoder = Mp3Encoder(sampleRate, channels, Mp3Encoder.nearestBitrate(sampleRate, bitrateKbps))
    private val out = FileOutputStream(path)

    override fun write(samples: ShortArray, count: Int) {
        out.write(encoder.encode(samples, count))
    }

    override fun finish() {
        out.write(encoder.finish())
        out.close()
        val info = encoder.infoFrame()
        if (info.isNotEmpty()) RandomAccessFile(path, "rw").use { it.write(info) }
    }
}

private class WavSink(path: String, private val sampleRate: Int, private val channels: Int) : PcmSink {
    private val file = RandomAccessFile(path, "rw").apply {
        setLength(0)
        write(Wav.header(sampleRate, channels, 0))
    }
    private var bytes = 0L
    private var buffer = ByteArray(0)

    override fun write(samples: ShortArray, count: Int) {
        if (buffer.size < count * 2) buffer = ByteArray(count * 2)
        for (i in 0 until count) {
            buffer[i * 2] = samples[i].toByte()
            buffer[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        file.write(buffer, 0, count * 2)
        bytes += count * 2
    }

    override fun finish() {
        file.seek(0)
        file.write(Wav.header(sampleRate, channels, bytes))
        file.close()
    }
}

// FLAC has no muxer, but the encoder's codec-config buffer is the fLaC marker plus STREAMINFO,
// so the buffers go to the file as they come
private class EncoderSink(
    path: String,
    mime: String,
    muxerFormat: Int?,
    private val sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
) : PcmSink {
    private val codec = MediaCodec.createEncoderByType(mime)
    private val muxer = muxerFormat?.let { MediaMuxer(path, it) }
    private val raw = if (muxerFormat == null) FileOutputStream(path) else null
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var framesIn = 0L

    init {
        val format = CodecFormat.createAudioFormat(mime, sampleRate, channels)
        if (mime == CodecFormat.MIMETYPE_AUDIO_AAC) format.setInteger(CodecFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        if (mime == CodecFormat.MIMETYPE_AUDIO_FLAC) format.setInteger(CodecFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        if (bitrateKbps > 0) format.setInteger(CodecFormat.KEY_BIT_RATE, bitrateKbps * 1000)
        format.setInteger(CodecFormat.KEY_MAX_INPUT_SIZE, 16_384)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(count - offset, buffer.remaining() / 2 / channels * channels)
            buffer.asShortBuffer().put(samples, offset, n)
            val timeUs = framesIn * 1_000_000 / sampleRate
            codec.queueInputBuffer(index, 0, n * 2, timeUs, 0)
            framesIn += n / channels
            offset += n
            drain(false)
        }
    }

    override fun finish() {
        var queued = false
        while (!queued) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, framesIn * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(false)
            }
        }
        drain(true)
        codec.stop()
        codec.release()
        if (muxer != null) {
            if (track >= 0) muxer.stop()
            muxer.release()
        }
        raw?.close()
    }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (muxer != null) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0) {
                        if (muxer != null && !config && track >= 0) {
                            muxer.writeSampleData(track, buffer, info)
                        } else if (raw != null) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            raw.write(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
