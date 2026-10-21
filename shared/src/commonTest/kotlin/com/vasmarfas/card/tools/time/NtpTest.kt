package com.vasmarfas.card.tools.time

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NtpTest {
    private fun reply(stratum: Int, receivedAt: Long, sentAt: Long, reference: ByteArray): ByteArray {
        val request = Ntp.request(receivedAt)
        val transmit = Ntp.request(sentAt)
        return ByteArray(48).also {
            it[0] = 0x24
            it[1] = stratum.toByte()
            reference.copyInto(it, 12)
            request.copyInto(it, 32, 40, 48)
            transmit.copyInto(it, 40, 40, 48)
        }
    }

    @Test
    fun offsetAndDelay() {
        // the device sent at 1000 and got the answer at 1100; the server clock is 250 ms ahead and took 10 ms
        val response = reply(1, 1_300, 1_310, "GPS".encodeToByteArray() + 0)
        val parsed = Ntp.parse("time.example", response, 1_000, 1_100)!!
        assertTrue(abs(parsed.offsetMs - 255.0) < 0.01, "${parsed.offsetMs}")
        assertTrue(abs(parsed.delayMs - 90.0) < 0.01, "${parsed.delayMs}")
        assertEquals("GPS", parsed.reference)
        assertEquals(1, parsed.stratum)
        assertEquals("192.0.2.1", Ntp.parse("x", reply(2, 1_300, 1_310, byteArrayOf(192.toByte(), 0, 2, 1)), 1_000, 1_100)?.reference)
    }

    @Test
    fun rejectsKissOfDeathAndJunk() {
        assertNull(Ntp.parse("x", reply(0, 1, 2, "RATE".encodeToByteArray()), 0, 1))
        assertNull(Ntp.parse("x", ByteArray(10), 0, 1))
        assertNull(Ntp.parse("x", null, 0, 1))
    }

    @Test
    fun bestOffsetPrefersShortRoundTrips() {
        val replies = listOf(
            NtpReply("a", 10.0, 20.0, 1, ""),
            NtpReply("b", 12.0, 25.0, 1, ""),
            NtpReply("c", 300.0, 900.0, 2, ""),
            NtpReply("d", 14.0, 30.0, 2, ""),
        )
        assertEquals(12.0, bestOffset(replies))
        assertNull(bestOffset(emptyList()))
    }
}
