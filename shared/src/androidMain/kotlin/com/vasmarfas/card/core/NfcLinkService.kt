package com.vasmarfas.card.core

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class NfcLinkService : HostApduService() {
    private var selected: ByteArray? = null

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val message = ndef ?: return FAILED
        val file = ByteArray(message.size + 2).also {
            it[0] = (message.size shr 8).toByte()
            it[1] = message.size.toByte()
            message.copyInto(it, 2)
        }
        if (apdu.size < 4) return FAILED
        val instruction = apdu[1].toInt() and 0xFF
        return when {
            instruction == 0xA4 && apdu.startsWith(SELECT_APPLICATION) -> OK
            instruction == 0xA4 && apdu.size >= 7 && apdu[2].toInt() == 0 -> {
                val id = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
                selected = when (id) {
                    0xE103 -> capability(file.size)
                    0xE104 -> file
                    else -> return NOT_FOUND
                }
                OK
            }
            instruction == 0xB0 && apdu.size >= 5 -> {
                val data = selected ?: return FAILED
                val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
                val length = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
                if (offset > data.size) return FAILED
                data.copyOfRange(offset, minOf(data.size, offset + length)) + OK
            }
            else -> FAILED
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = null
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    // mapping version 2.0, reads of up to 59 bytes, one read-only NDEF file E104
    private fun capability(fileSize: Int) = byteArrayOf(
        0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06, 0xE1.toByte(), 0x04,
        (fileSize shr 8).toByte(), fileSize.toByte(), 0x00, 0xFF.toByte(),
    )

    companion object {
        @Volatile
        var ndef: ByteArray? = null

        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val FAILED = byteArrayOf(0x6F, 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SELECT_APPLICATION = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
    }
}
