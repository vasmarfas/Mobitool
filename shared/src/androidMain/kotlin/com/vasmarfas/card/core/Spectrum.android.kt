package com.vasmarfas.card.core

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
actual fun microphoneSpectrumFlow(fftSize: Int): Flow<SpectrumFrame> = flow {
    val sampleRate = 44_100
    val minBuffer = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    val record = try {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, fftSize * 2),
        )
    } catch (e: SecurityException) {
        throw IllegalStateException("NotAllowedError")
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("NotFoundError")
    }
    val hop = minOf(fftSize, SpectrumHop)
    val pcm = ShortArray(hop)
    val samples = FloatArray(fftSize)
    var collected = 0
    try {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("NotReadableError")
        while (currentCoroutineContext().isActive) {
            var filled = 0
            while (filled < hop) {
                val read = record.read(pcm, filled, hop - filled)
                if (read <= 0) throw IllegalStateException("NotReadableError")
                filled += read
            }
            samples.copyInto(samples, 0, hop, fftSize)
            for (i in 0 until hop) samples[fftSize - hop + i] = pcm[i] / 32768f
            collected = minOf(fftSize, collected + hop)
            if (collected == fftSize) emit(SpectrumFrame(Fft.magnitudesDb(samples), sampleRate))
        }
    } finally {
        record.stop()
        record.release()
    }
}.flowOn(Dispatchers.IO)
