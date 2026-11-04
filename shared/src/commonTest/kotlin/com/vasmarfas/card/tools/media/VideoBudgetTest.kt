package com.vasmarfas.card.tools.media

import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.videoBitrate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoBudgetTest {
    private fun spec(bytes: Long, audioKbps: Int = 192, keepAudio: Boolean = true) =
        MediaSpec(MediaFormat.MP4, targetBytes = bytes, audioBitrateKbps = audioKbps, keepAudio = keepAudio)

    // 25 MB over a minute: 3 333 333 bit/s, 95 % of it after the container, minus 192 kbit/s of sound
    @Test
    fun aMinuteInto25Mb() {
        assertEquals(192, spec(25_000_000).fittedAudioKbps(60_000))
        assertEquals(2_974_666L, spec(25_000_000).videoBitrate(0, 0, 0, 60_000))
    }

    // 10 MB over five minutes is 253 332 bit/s, a quarter of it is 63 kbit/s
    @Test
    fun longClipGivesUpSoundQuality() {
        assertEquals(48, spec(10_000_000).fittedAudioKbps(300_000))
    }

    @Test
    fun theEstimateStaysInsideAReachableBudget() {
        val fitted = spec(25_000_000)
        val bytes = fitted.expectedBytes(fitted.videoBitrate(0, 0, 0, 60_000), 60_000)
        assertEquals(23_749_995L, bytes)
        assertTrue(bytes <= fitted.targetBytes)
    }

    // an hour in 1 MB: the 80 kbit/s floor plus 48 kbit/s of sound is 57.6 MB
    @Test
    fun theFloorShowsAnUnreachableBudget() {
        val hour = spec(1_000_000, audioKbps = 48)
        assertEquals(57_600_000L, hour.expectedBytes(hour.videoBitrate(0, 0, 0, 3_600_000), 3_600_000))
    }

    @Test
    fun withoutSoundTheEstimateIsTheVideoAlone() {
        assertEquals(7_500_000L, spec(0, keepAudio = false).expectedBytes(1_000_000, 60_000))
    }

    // 1920 x 1080 at 30 fps needs 1 244 160 bit/s to reach 0.02 bits per pixel
    @Test
    fun blockyThreshold() {
        assertTrue(blockyBitrate(1_000_000, 1920, 1080, 30))
        assertFalse(blockyBitrate(1_300_000, 1920, 1080, 30))
    }
}
