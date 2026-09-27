package com.vasmarfas.card.tools.security

import com.vasmarfas.card.tools.developer.HashAlgorithm
import com.vasmarfas.card.tools.developer.UrlCodec
import com.vasmarfas.card.tools.developer.hmac

data class OtpAuth(
    val secret: String,
    val issuer: String?,
    val account: String?,
    val digits: Int,
    val period: Int,
    val algorithm: HashAlgorithm,
)

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(text: String): ByteArray? {
        val clean = text.uppercase().filterNot { it.isWhitespace() || it == '-' || it == '=' }
        if (clean.isEmpty()) return ByteArray(0)
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in clean) {
            val index = ALPHABET.indexOf(c)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}

object Totp {
    fun counter(epochMillis: Long, period: Int): Long = epochMillis / 1000 / period

    fun secondsRemaining(epochMillis: Long, period: Int): Int = period - ((epochMillis / 1000) % period).toInt()

    fun code(secret: ByteArray, counter: Long, digits: Int, algorithm: HashAlgorithm): String {
        val message = ByteArray(8)
        for (i in 0 until 8) message[7 - i] = (counter ushr (8 * i)).toByte()
        val mac = hmac(algorithm, secret, message)
        val offset = mac[mac.size - 1].toInt() and 0x0F
        val binary = ((mac[offset].toInt() and 0x7F) shl 24) or
            ((mac[offset + 1].toInt() and 0xFF) shl 16) or
            ((mac[offset + 2].toInt() and 0xFF) shl 8) or
            (mac[offset + 3].toInt() and 0xFF)
        var modulo = 1L
        repeat(digits) { modulo *= 10 }
        return (binary % modulo).toString().padStart(digits, '0')
    }

    fun parseUri(uri: String): OtpAuth? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("otpauth://totp/", ignoreCase = true)) return null
        val parsed = UrlCodec.parse(trimmed) ?: return null
        val params = parsed.params.associate { it.first.lowercase() to it.second }
        val secret = params["secret"]?.takeIf { it.isNotBlank() } ?: return null
        val label = (UrlCodec.decode(parsed.path.removePrefix("/")) ?: "").trim()
        val labelIssuer = label.substringBefore(':', "").trim().takeIf { it.isNotEmpty() }
        val account = label.substringAfter(':', label).trim().takeIf { it.isNotEmpty() }
        val algorithm = when (params["algorithm"]?.uppercase()) {
            "SHA256" -> HashAlgorithm.SHA256
            "SHA512" -> HashAlgorithm.SHA512
            else -> HashAlgorithm.SHA1
        }
        return OtpAuth(
            secret = secret,
            issuer = params["issuer"] ?: labelIssuer,
            account = account,
            digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..10 } ?: 6,
            period = params["period"]?.toIntOrNull()?.takeIf { it in 1..300 } ?: 30,
            algorithm = algorithm,
        )
    }

    fun buildUri(auth: OtpAuth): String {
        val label = listOfNotNull(auth.issuer, auth.account).joinToString(":")
        val query = buildList {
            add("secret" to auth.secret)
            auth.issuer?.let { add("issuer" to it) }
            add("algorithm" to auth.algorithm.title.replace("-", ""))
            add("digits" to auth.digits.toString())
            add("period" to auth.period.toString())
        }
        return "otpauth://totp/" + UrlCodec.encodeComponent(label.ifEmpty { "account" }) + "?" + UrlCodec.buildQuery(query)
    }
}
