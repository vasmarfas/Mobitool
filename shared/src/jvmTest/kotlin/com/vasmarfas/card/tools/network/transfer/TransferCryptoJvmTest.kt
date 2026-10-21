package com.vasmarfas.card.tools.network.transfer

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class TransferCryptoJvmTest {
    @Test
    fun matchesJdkChaCha20Poly1305() {
        val random = Random(7)
        for (size in listOf(0, 1, 15, 16, 17, 63, 64, 65, 1000, 65_536)) {
            val key = random.nextBytes(32)
            val nonce = random.nextBytes(12)
            val aad = random.nextBytes(size % 20)
            val plain = random.nextBytes(size)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.updateAAD(aad)
            assertContentEquals(cipher.doFinal(plain), ChaCha20Poly1305.seal(key, nonce, plain, aad), "size $size")
        }
    }

    @Test
    fun matchesJdkPbkdf2() {
        val password = "482913557204"
        val spec = PBEKeySpec(password.toCharArray(), "mobitool".encodeToByteArray(), 1000, 48 * 8)
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertContentEquals(expected, pbkdf2Sha256(password.encodeToByteArray(), "mobitool".encodeToByteArray(), 1000, 48))
    }
}
