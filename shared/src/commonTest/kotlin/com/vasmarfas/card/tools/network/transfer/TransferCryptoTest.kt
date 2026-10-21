package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.tools.developer.hexToBytes
import com.vasmarfas.card.tools.developer.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferCryptoTest {
    private fun hex(text: String) = hexToBytes(text)!!

    @Test
    fun poly1305Rfc8439() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", ChaCha20Poly1305.poly1305(key, "Cryptographic Forum Research Group".encodeToByteArray()).toHex())
    }

    @Test
    fun aeadRfc8439() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".encodeToByteArray()
        val sealed = ChaCha20Poly1305.seal(key, nonce, plain, aad)
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOf(16).toHex())
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).toHex())
        assertContentEquals(plain, ChaCha20Poly1305.open(key, nonce, sealed, aad))
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(ChaCha20Poly1305.open(key, nonce, sealed, aad))
    }

    @Test
    fun pbkdf2Rfc7914() {
        val derived = pbkdf2Sha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783", derived.toHex())
    }
}
