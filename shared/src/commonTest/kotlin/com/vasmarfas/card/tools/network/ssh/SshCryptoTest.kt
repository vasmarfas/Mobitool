package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.developer.hexToBytes
import com.vasmarfas.card.tools.developer.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun hex(text: String) = hexToBytes(text.replace(" ", ""))!!

class SshCryptoTest {
    @Test
    fun x25519Rfc7748() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(alice).toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519.publicKey(bob).toHex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519.shared(alice, X25519.publicKey(bob)).toHex())
        assertEquals(shared, X25519.shared(bob, X25519.publicKey(alice)).toHex())
    }

    @Test
    fun ed25519Rfc8032() {
        val vectors = listOf(
            Triple(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Triple(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
        )
        val publics = listOf(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
        vectors.forEachIndexed { i, (seed, message, signature) ->
            val point = Ed25519.publicKey(hex(seed))
            assertEquals(publics[i], point.toHex())
            val bytes = hexToBytes(message)!!
            assertEquals(signature, Ed25519.sign(hex(seed), point, bytes).toHex())
            assertTrue(Ed25519.verify(point, bytes, hex(signature)))
            assertFalse(Ed25519.verify(point, bytes + byteArrayOf(1), hex(signature)))
        }
    }

    @Test
    fun p256Rfc6979() {
        val curve = NistCurve.P256
        val x = hex("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721")
        val point = hex(
            "04 60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6" +
                "7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299",
        )
        assertContentEquals(point, curve.publicKey(x))
        val r = hex("EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716")
        val s = hex("F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8")
        assertTrue(curve.verify(point, "sample".encodeToByteArray(), r, s))
        assertFalse(curve.verify(point, "sample!".encodeToByteArray(), r, s))
    }

    @Test
    fun p384AndP521KeysFromOpenSsl() {
        assertEquals(
            "04df86eab534b36465fe66f9264f17f7854fa5637fcac0c21c0ed3c4fe54f7c5083c9b5b90b19737ee753421e075020b3d" +
                "b099badc99c357af777eaba4ee8dc05312c52db07ad29428fd4a7f0d01920ade41c0b1da973c51eab6b6077c88cdf057",
            NistCurve.P384.publicKey(
                hex("aa91b3b22facd7774f98b23efc83256895790a95a7fd6c6117bf65149e4b92044502fc20c5729301c32fae99f716e9e6"),
            ).toHex(),
        )
        assertEquals(
            "0401c0752c056ccddd37bf07eef3266d596cb3c60e81822b679aa2af3da8d9dfee99e7529b5106e823ac69b1b83bf77ab6f68c9e84f0b9358cd1f8c1ba3fb4acbf3fcb" +
                "00f329692fe766b959e2cd0d79e08eaf13500f6d00127491e6400e3d5827c44ad5fac3d33d4664002cdf2e297e17a2df4cea4d700929b216f8c69991b2d177e4f0f9",
            NistCurve.P521.publicKey(
                hex("0078b4cadef9ab9b29fb597eb9653df765c7ed050df7c8a8f3f5ee43d6b337a74e4286430719425183ba9dade40f15b3f9da95a6fcaaf824fa86bb48fa6c68a0f969"),
            ).toHex(),
        )
    }

    @Test
    fun ecdhAgreesOnEveryCurve() {
        for (curve in listOf(NistCurve.P256, NistCurve.P384, NistCurve.P521)) {
            val (a, aPublic) = curve.keyPair()
            val (b, bPublic) = curve.keyPair()
            assertContentEquals(curve.shared(a, bPublic), curve.shared(b, aPublic), curve.name)
            assertNull(curve.shared(a, aPublic.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }), curve.name)
        }
    }

    @Test
    fun aesGcmSpecVectors() {
        val key = hex("feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val plain = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
        )
        assertEquals(
            "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091473f5985" +
                "4d5c2af327cd64a62cf35abd2ba6fab4",
            AesGcm(key, iv).seal(ByteArray(0), plain).toHex(),
        )
        assertEquals(
            "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091" +
                "5bc94fbc3221a5db94fae95ae7121a47",
            AesGcm(key, iv).seal(hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"), plain.copyOf(60)).toHex(),
        )
    }

    @Test
    fun piSeedsBlowfishAndTheModpGroups() {
        assertEquals(0x243F6A88, Pi.fraction[0])
        assertEquals(0x8979FB1B.toInt(), Pi.fraction[17])
        assertEquals(0xD1310BA6.toInt(), Pi.fraction[18])
        val group14 = Pi.modpPrime(2048, 124476).toHex()
        assertTrue(group14.startsWith("ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd1"))
        assertTrue(group14.endsWith("15728e5a8aacaa68ffffffffffffffff"))
        assertTrue(Pi.modpPrime(4096, 240904).toHex().endsWith("4df435c934063199ffffffffffffffff"))
        assertTrue(Pi.modpPrime(8192, 4743158).toHex().endsWith("60c980dd98edd3dfffffffffffffffff"))
    }

    private class Pipe(private val data: ByteArray) : TcpStream {
        private var position = 0
        override val remoteHost = ""

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == data.size) return -1
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {}

        override fun close() {}
    }

    @Test
    fun everyCipherAndMacRoundTrips() = runTest {
        val payloads = listOf(1, 15, 16, 17, 100, 5000).map { secureRandomBytes(it) }
        for (cipher in CipherSpec.all) {
            for (mac in if (cipher.aead) listOf(null) else MacSpec.all) {
                val key = secureRandomBytes(cipher.keySize)
                val iv = secureRandomBytes(cipher.ivSize)
                val macKey = mac?.let { secureRandomBytes(it.keySize) }
                val sealer = cipher.codec(key, iv, mac?.mac(macKey!!), encrypt = true)
                val opener = cipher.codec(key, iv, mac?.mac(macKey!!), encrypt = false)
                val wire = payloads.mapIndexed { i, payload -> sealer.seal(i, sealer.frame(payload)) }.reduce { a, b -> a + b }
                val input = SshInput(Pipe(wire))
                payloads.forEachIndexed { i, payload ->
                    val plain = opener.open(i, input)
                    val padding = plain[0].toInt()
                    assertContentEquals(payload, plain.copyOfRange(1, plain.size - padding), "${cipher.name} ${mac?.name}")
                }
            }
        }
    }
}
