package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DkimKey(val selector: String, val record: String) {
    val tags: Map<String, String> = EmailHeaders.tags(record)
    val type: String = tags["k"]?.lowercase() ?: "rsa"
    val bits: Int? = tags["p"]?.takeIf { it.isNotEmpty() }?.let { Dkim.keyBits(type, it) }

    val issues: List<MailIssue>
        get() = buildList {
            tags["v"]?.takeIf { it != "DKIM1" }?.let { add(MailIssue(true, Res.string.dkim_bad_version, it)) }
            val key = tags["p"]
            when {
                key == null -> add(MailIssue(true, Res.string.dkim_no_key))
                key.isEmpty() -> add(MailIssue(true, Res.string.dkim_revoked))
                type != "rsa" && type != "ed25519" -> add(MailIssue(true, Res.string.dkim_unknown_type, type))
                bits == null -> add(MailIssue(true, Res.string.dkim_bad_key))
                type == "rsa" && bits < 1024 -> add(MailIssue(true, Res.string.dkim_short_key, bits.toString()))
                type == "rsa" && bits < 2048 -> add(MailIssue(false, Res.string.dkim_weak_key, bits.toString()))
            }
            if ("y" in tags["t"].orEmpty().split(':')) add(MailIssue(false, Res.string.dkim_testing))
        }
}

private class Tlv(val tag: Int, val start: Int, val end: Int)

object Dkim {
    val commonSelectors = listOf("google", "selector1", "selector2", "default", "dkim", "mail", "k1", "s1", "s2", "mta", "smtp", "yandex", "mailru")

    private val cloudflare = DnsResolver.CLOUDFLARE.dohUrl!!
    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    suspend fun lookup(domain: String, selector: String): List<DkimKey> = coroutineScope {
        (if (selector.isEmpty()) commonSelectors else listOf(selector)).map { name ->
            async {
                DnsClient.queryDoh(cloudflare, "$name._domainkey.$domain", 16, DohFlavour.JSON).answers
                    .filter { it.type == 16 && ("p=" in it.data || it.data.startsWith("v=DKIM1")) }
                    .map { DkimKey(name, it.data) }
            }
        }.awaitAll().flatten()
    }

    // RFC 8463 publishes the bare 32-byte Ed25519 key, RSA comes as SubjectPublicKeyInfo or, from some hosts, a bare RSAPublicKey
    fun keyBits(type: String, key: String): Int? = runCatching {
        val der = base64.decode(key)
        if (type == "ed25519") return if (der.size == 32) 256 else null
        val outer = der.tlv(0)
        require(outer.tag == 0x30)
        val first = der.tlv(outer.start)
        val rsa = if (first.tag == 0x30) {
            val bitString = der.tlv(first.end)
            require(bitString.tag == 0x03)
            der.tlv(bitString.start + 1)
        } else {
            outer
        }
        require(rsa.tag == 0x30)
        val modulus = der.tlv(rsa.start)
        require(modulus.tag == 0x02)
        var at = modulus.start
        while (at < modulus.end - 1 && der[at] == 0.toByte()) at++
        (modulus.end - at - 1) * 8 + (32 - (der[at].toInt() and 0xFF).countLeadingZeroBits())
    }.getOrNull()

    private fun ByteArray.tlv(at: Int): Tlv {
        val tag = this[at].toInt() and 0xFF
        var length = this[at + 1].toInt() and 0xFF
        var start = at + 2
        if (length >= 0x80) {
            val count = length and 0x7F
            require(count in 1..4)
            length = 0
            repeat(count) { length = (length shl 8) or (this[start++].toInt() and 0xFF) }
        }
        require(length >= 0 && start + length <= size)
        return Tlv(tag, start, start + length)
    }
}
