package com.vasmarfas.card.tools.media

import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.videoBitrate

val videoSizePresets = listOf(10, 16, 25, 50, 100)

private val audioSteps = listOf(192, 128, 96, 64, 48)

// bits per pixel per frame, under it H.264 falls apart into blocks whatever the source
private const val BLOCKY_BPP = 0.02

// the sound takes at most a quarter of the budget, 192 kbit/s would leave a long clip no picture
fun MediaSpec.fittedAudioKbps(durationMs: Long): Int {
    val total = copy(keepAudio = false).videoBitrate(0, 0, 0, durationMs)
    return audioSteps.firstOrNull { it * 4000L <= total } ?: audioSteps.last()
}

fun MediaSpec.expectedBytes(videoBps: Long, durationMs: Long): Long =
    (videoBps + if (keepAudio) audioBitrateKbps * 1000L else 0L) * durationMs / 8000

fun blockyBitrate(videoBps: Long, width: Int, height: Int, fps: Int): Boolean = videoBps < width.toDouble() * height * fps * BLOCKY_BPP
