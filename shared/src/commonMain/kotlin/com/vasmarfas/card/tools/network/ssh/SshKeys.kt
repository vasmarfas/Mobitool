package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.HashAlgorithm
import com.vasmarfas.card.tools.developer.Md5
import com.vasmarfas.card.tools.developer.hexToBytes
import com.vasmarfas.card.tools.documents.pdf.Aes
import com.vasmarfas.card.tools.network.Rsa
import com.vasmarfas.card.tools.network.transfer.pbkdf2
import kotlin.io.encoding.Base64

private val SHA1_INFO = hexToBytes("3021300906052b0e03021a05000414")!!
private val SHA256_INFO = hexToBytes("3031300d060960864801650304020105000420")!!
private val SHA512_INFO = hexToBytes("3051300d060960864801650304020305000440")!!

private val RSA_SIGNATURES = mapOf(
    "rsa-sha2-512" to (HashAlgorithm.SHA512 to SHA512_INFO),
    "rsa-sha2-256" to (HashAlgorithm.SHA256 to SHA256_INFO),
    "ssh-rsa" to (HashAlgorithm.SHA1 to SHA1_INFO),
)

private val CURVE_OIDS = mapOf(
    "1.2.840.10045.3.1.7" to NistCurve.P256,
    "1.3.132.0.34" to NistCurve.P384,
    "1.3.132.0.35" to NistCurve.P521,
)

// EMSA-PKCS1-v1_5 of RFC 8017 section 9.2
private fun pkcs1(algorithm: String, message: ByteArray, size: Int): ByteArray {
    val (hash, info) = RSA_SIGNATURES.getValue(algorithm)
    val digest = info + hash.digest(message)
    return byteArrayOf(0, 1) + ByteArray(size - digest.size - 3) { -1 } + byteArrayOf(0) + digest
}

private fun leftPad(bytes: ByteArray, size: Int): ByteArray = if (bytes.size >= size) bytes else ByteArray(size - bytes.size) + bytes

// checks the signature of the exchange hash against the host key the server sent
internal fun verifyHostSignature(algorithm: String, keyBlob: ByteArray, data: ByteArray, signatureBlob: ByteArray): Boolean = runCatching {
    val key = SshReader(keyBlob)
    val type = key.text()
    val signature = SshReader(signatureBlob)
    if (signature.text() != algorithm) return false
    val sig = signature.string()
    when {
        type == "ssh-ed25519" && algorithm == type -> Ed25519.verify(key.string(), data, sig)
        type == "ssh-rsa" && algorithm in RSA_SIGNATURES -> {
            val e = key.mpint()
            val n = key.mpint()
            if (sig.size > n.size) return false
            val padded = leftPad(sig, n.size)
            compareMagnitude(padded, n) < 0 && Rsa.modPow(padded, e, n).contentEquals(pkcs1(algorithm, data, n.size))
        }
        type.startsWith("ecdsa-sha2-") && algorithm == type -> {
            val curve = NistCurve.byName(key.text()) ?: return false
            if ("ecdsa-sha2-${curve.name}" != type) return false
            val point = key.string()
            val rs = SshReader(sig)
            curve.verify(point, data, rs.mpint(), rs.mpint())
        }
        else -> false
    }
}.getOrDefault(false)

internal sealed class SshPrivateKey {
    abstract val publicBlob: ByteArray

    // signature algorithms in the order they are tried, null when the server did not list its own
    abstract fun algorithms(serverAlgorithms: List<String>?): List<String>

    abstract fun sign(algorithm: String, data: ByteArray): ByteArray

    class Ed25519Key(private val seed: ByteArray, private val point: ByteArray = Ed25519.publicKey(seed)) : SshPrivateKey() {
        override val publicBlob = SshWriter().string("ssh-ed25519").string(point).bytes()

        override fun algorithms(serverAlgorithms: List<String>?) = listOf("ssh-ed25519")

        override fun sign(algorithm: String, data: ByteArray): ByteArray =
            SshWriter().string(algorithm).string(Ed25519.sign(seed, point, data)).bytes()
    }

    class RsaKey(private val n: ByteArray, e: ByteArray, private val d: ByteArray) : SshPrivateKey() {
        override val publicBlob = SshWriter().string("ssh-rsa").mpint(e).mpint(n).bytes()

        // servers that never heard of SHA-2 signatures get SHA-1 last
        override fun algorithms(serverAlgorithms: List<String>?): List<String> {
            val modern = listOf("rsa-sha2-512", "rsa-sha2-256")
            if (serverAlgorithms == null) return modern + "ssh-rsa"
            return modern.filter { it in serverAlgorithms }.ifEmpty { listOf("ssh-rsa") }
        }

        override fun sign(algorithm: String, data: ByteArray): ByteArray =
            SshWriter().string(algorithm).string(Rsa.modPow(pkcs1(algorithm, data, n.size), d, n)).bytes()
    }

    class EcdsaKey(private val curve: NistCurve, private val d: ByteArray, point: ByteArray = curve.publicKey(d)) : SshPrivateKey() {
        private val type = "ecdsa-sha2-${curve.name}"
        override val publicBlob = SshWriter().string(type).string(curve.name).string(point).bytes()

        override fun algorithms(serverAlgorithms: List<String>?) = listOf(type)

        override fun sign(algorithm: String, data: ByteArray): ByteArray {
            val (r, s) = curve.sign(d, data)
            return SshWriter().string(type).string(SshWriter().mpint(r).mpint(s).bytes()).bytes()
        }
    }
}

private class Pem(val label: String, val headers: Map<String, String>, val body: ByteArray)

private fun readPem(text: String): Pem {
    val lines = text.lines().map { it.trim() }
    val begin = lines.indexOfFirst { it.startsWith("-----BEGIN ") && it.endsWith("-----") }
    require(begin >= 0)
    val label = lines[begin].removePrefix("-----BEGIN ").removeSuffix("-----")
    val end = lines.indexOfFirst { it == "-----END $label-----" }
    require(end > begin)
    val headers = mutableMapOf<String, String>()
    val body = StringBuilder()
    for (line in lines.subList(begin + 1, end)) {
        val colon = line.indexOf(':')
        if (colon > 0 && body.isEmpty()) headers[line.substring(0, colon)] = line.substring(colon + 1).trim() else body.append(line)
    }
    return Pem(label, headers, Base64.Default.decode(body.toString()))
}

private fun unpad(data: ByteArray): ByteArray {
    val pad = data.lastOrNull()?.toInt()?.and(0xFF) ?: 0
    require(pad in 1..16 && pad <= data.size && (data.size - pad until data.size).all { data[it].toInt() and 0xFF == pad })
    return data.copyOf(data.size - pad)
}

private fun cbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(data.isNotEmpty() && data.size % 16 == 0)
    return unpad(AesCbc(key, iv, encrypt = false).process(data))
}

// "Proc-Type: 4,ENCRYPTED" keys from OpenSSL: the AES key is EVP_BytesToKey with MD5 over the passphrase and the IV
private fun legacyDecrypt(pem: Pem, passphrase: ByteArray): ByteArray {
    val info = pem.headers["DEK-Info"] ?: return pem.body
    val (cipher, ivHex) = info.split(',').map { it.trim() }
    val keySize = when (cipher) {
        "AES-128-CBC" -> 16
        "AES-192-CBC" -> 24
        "AES-256-CBC" -> 32
        else -> throw IllegalArgumentException(cipher)
    }
    val iv = hexToBytes(ivHex)!!
    var block = ByteArray(0)
    var key = ByteArray(0)
    while (key.size < keySize) {
        block = Md5.digest(block + passphrase + iv.copyOf(8))
        key += block
    }
    return cbcDecrypt(key.copyOf(keySize), iv, pem.body)
}

private fun rsaFromPkcs1(der: ByteArray): SshPrivateKey {
    val sequence = DerReader(der).sequence()
    sequence.integer()
    val n = sequence.integer()
    val e = sequence.integer()
    return SshPrivateKey.RsaKey(n, e, sequence.integer())
}

private fun ecFromSec1(der: ByteArray, known: NistCurve?): SshPrivateKey {
    val sequence = DerReader(der).sequence()
    sequence.integer()
    val d = sequence.octets()
    val curve = sequence.context(0)?.let { CURVE_OIDS[it.oid()] } ?: known ?: throw IllegalArgumentException("curve")
    val point = sequence.context(1)?.bits()
    return if (point != null) SshPrivateKey.EcdsaKey(curve, d, point) else SshPrivateKey.EcdsaKey(curve, d)
}

private fun fromPkcs8(der: ByteArray): SshPrivateKey {
    val sequence = DerReader(der).sequence()
    sequence.integer()
    val algorithm = sequence.sequence()
    val oid = algorithm.oid()
    val key = sequence.octets()
    return when (oid) {
        "1.2.840.113549.1.1.1" -> rsaFromPkcs1(key)
        "1.2.840.10045.2.1" -> ecFromSec1(key, CURVE_OIDS[algorithm.oid()])
        "1.3.101.112" -> SshPrivateKey.Ed25519Key(DerReader(key).octets())
        else -> throw IllegalArgumentException(oid)
    }
}

// PBES2 with PBKDF2 and AES-CBC, what OpenSSL and ssh-keygen -m PKCS8 write
private fun pbes2Decrypt(der: ByteArray, passphrase: ByteArray): ByteArray {
    val sequence = DerReader(der).sequence()
    val algorithm = sequence.sequence()
    require(algorithm.oid() == "1.2.840.113549.1.5.13")
    val parameters = algorithm.sequence()
    val kdf = parameters.sequence()
    require(kdf.oid() == "1.2.840.113549.1.5.12")
    val kdfParameters = kdf.sequence()
    val salt = kdfParameters.octets()
    val iterations = stripZeros(kdfParameters.integer()).fold(0) { acc, b -> acc shl 8 or (b.toInt() and 0xFF) }
    if (kdfParameters.nextTag == 0x02) kdfParameters.integer()
    val prf = if (kdfParameters.nextTag == 0x30) kdfParameters.sequence().oid() else "1.2.840.113549.2.7"
    val hash = when (prf) {
        "1.2.840.113549.2.7" -> HashAlgorithm.SHA1
        "1.2.840.113549.2.9" -> HashAlgorithm.SHA256
        "1.2.840.113549.2.11" -> HashAlgorithm.SHA512
        else -> throw IllegalArgumentException(prf)
    }
    val scheme = parameters.sequence()
    val keySize = when (scheme.oid()) {
        "2.16.840.1.101.3.4.1.2" -> 16
        "2.16.840.1.101.3.4.1.22" -> 24
        "2.16.840.1.101.3.4.1.42" -> 32
        else -> throw IllegalArgumentException("cipher")
    }
    val iv = scheme.octets()
    return cbcDecrypt(pbkdf2(hash, passphrase, salt, iterations, keySize), iv, sequence.octets())
}

private val OPENSSH_MAGIC = "openssh-key-v1".encodeToByteArray() + byteArrayOf(0)

private fun fromOpenSsh(body: ByteArray, passphrase: ByteArray): SshPrivateKey {
    require(body.copyOf(OPENSSH_MAGIC.size).contentEquals(OPENSSH_MAGIC))
    val reader = SshReader(body.copyOfRange(OPENSSH_MAGIC.size, body.size))
    val cipher = reader.text()
    val kdf = reader.text()
    val kdfOptions = SshReader(reader.string())
    require(reader.uint32() == 1)
    reader.string()
    var private = reader.string()
    if (cipher != "none") {
        require(kdf == "bcrypt")
        val spec = CipherSpec.all.first { it.name == cipher && !it.name.startsWith("chacha20") }
        val salt = kdfOptions.string()
        val rounds = kdfOptions.uint32()
        val derived = BcryptPbkdf.derive(passphrase, salt, rounds, spec.keySize + spec.ivSize)
        val key = derived.copyOf(spec.keySize)
        val iv = derived.copyOfRange(spec.keySize, derived.size)
        private = when {
            spec.aead -> AesGcm(key, iv).open(ByteArray(0), private, reader.raw(16)) ?: throw IllegalArgumentException("passphrase")
            cipher.endsWith("ctr") -> AesCtr(key, iv).process(private)
            else -> AesCbc(key, iv, encrypt = false).process(private)
        }
    }
    val section = SshReader(private)
    require(section.uint32() == section.uint32())
    return when (val type = section.text()) {
        "ssh-ed25519" -> {
            val point = section.string()
            SshPrivateKey.Ed25519Key(section.string().copyOf(32), point)
        }
        "ssh-rsa" -> {
            val n = section.mpint()
            val e = section.mpint()
            SshPrivateKey.RsaKey(n, e, section.mpint())
        }
        else -> {
            val curve = NistCurve.byName(section.text())
            require(curve != null && type == "ecdsa-sha2-${curve.name}")
            val point = section.string()
            SshPrivateKey.EcdsaKey(curve, section.mpint(), point)
        }
    }
}

// OpenSSH, PKCS#1, SEC1 and PKCS#8 files with RSA, ECDSA or Ed25519 keys
internal fun parsePrivateKey(text: String, passphrase: String): SshPrivateKey {
    val secret = passphrase.encodeToByteArray()
    return try {
        val pem = readPem(text)
        when (pem.label) {
            "OPENSSH PRIVATE KEY" -> fromOpenSsh(pem.body, secret)
            "RSA PRIVATE KEY" -> rsaFromPkcs1(legacyDecrypt(pem, secret))
            "EC PRIVATE KEY" -> ecFromSec1(legacyDecrypt(pem, secret), null)
            "PRIVATE KEY" -> fromPkcs8(pem.body)
            "ENCRYPTED PRIVATE KEY" -> fromPkcs8(pbes2Decrypt(pem.body, secret))
            else -> throw IllegalArgumentException(pem.label)
        }
    } catch (e: Exception) {
        throw NetException(Res.string.ssh_error_key)
    }
}
