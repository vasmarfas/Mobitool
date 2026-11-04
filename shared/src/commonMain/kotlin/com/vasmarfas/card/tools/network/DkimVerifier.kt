package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.hexToBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DkimVerdict(val verified: Boolean, val issues: List<MailIssue>)

@OptIn(ExperimentalEncodingApi::class)
object DkimVerifier {
    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val lineBreak = Regex("\r?\n")
    private val blanks = Regex("[ \t]+")
    private val signatureValue = Regex("(^|;)([ \t\r\n]*b[ \t\r\n]*=)[^;]*")
    private val sha256DigestInfo = hexToBytes("3031300d060960864801650304020105000420")!!
    private val canonicalizations = setOf("simple", "relaxed")

    // pasted text and mbox exports lose the CR, signatures were made over CRLF
    suspend fun verify(
        raw: ByteArray,
        now: Long = currentEpochMillis() / 1000,
        keys: suspend (String, String) -> List<DkimKey> = Dkim::lookup,
    ): List<DkimVerdict> {
        val text = TextDecoding.decode(raw, "latin1").replace(lineBreak, "\r\n")
        val end = text.indexOf("\r\n\r\n")
        val fields = fields(if (end < 0) text else text.substring(0, end))
        val body = if (end < 0) "" else text.substring(end + 4)
        return fields.filter { name(it) == "dkim-signature" }.map { field ->
            val tags = EmailHeaders.tags(field.substringAfter(':'))
            val domain = tags["d"].orEmpty()
            val selector = tags["s"].orEmpty()
            val key = if (domain.isEmpty() || selector.isEmpty()) null else keys(domain, selector).firstOrNull()
            check(field, tags, fields, body, key, now)
        }
    }

    fun canonicalHeader(field: String, relaxed: Boolean): String {
        if (!relaxed) return "$field\r\n"
        val value = field.substringAfter(':').replace("\r\n", "").replace(blanks, " ").trim(' ')
        return "${name(field)}:$value\r\n"
    }

    fun canonicalBody(body: String, relaxed: Boolean): String {
        val lines = body.split("\r\n").map { if (relaxed) it.replace(blanks, " ").trimEnd(' ') else it }
        val text = lines.dropLastWhile { it.isEmpty() }.joinToString("") { "$it\r\n" }
        return if (text.isEmpty() && !relaxed) "\r\n" else text
    }

    private fun fields(head: String): List<String> {
        val out = mutableListOf<String>()
        for (line in head.split("\r\n")) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (out.isNotEmpty()) out[out.lastIndex] += "\r\n$line"
            } else if (line.isNotEmpty()) {
                out += line
            }
        }
        return out
    }

    private fun name(field: String): String = field.substringBefore(':').trim().lowercase()

    private fun signedHeaders(names: List<String>, fields: List<String>, signature: String, relaxed: Boolean): ByteArray {
        val unused = fields.toMutableList()
        val text = buildString {
            names.forEach { wanted ->
                val at = unused.indexOfLast { name(it) == wanted.lowercase() }
                if (at >= 0) append(canonicalHeader(unused.removeAt(at), relaxed))
            }
            val empty = signature.substringBefore(':') + ":" + signature.substringAfter(':').replace(signatureValue) { it.groupValues[1] + it.groupValues[2] }
            append(canonicalHeader(empty, relaxed).removeSuffix("\r\n"))
        }
        return EmailHeaders.latin1Bytes(text)
    }

    private fun check(field: String, tags: Map<String, String>, fields: List<String>, body: String, key: DkimKey?, now: Long): DkimVerdict {
        val algorithm = tags["a"].orEmpty().lowercase()
        val canonicalization = tags["c"].orEmpty().lowercase().ifEmpty { "simple/simple" }
        val headerMode = canonicalization.substringBefore('/')
        val bodyMode = canonicalization.substringAfter('/', "simple")
        val wrong = listOf("a", "b", "bh", "d", "h", "s").filter { tags[it].isNullOrEmpty() } +
            listOfNotNull(
                "v".takeIf { tags["v"] != "1" },
                "a".takeIf { algorithm !in setOf("", "rsa-sha256", "rsa-sha1", "ed25519-sha256") },
                "c".takeIf { headerMode !in canonicalizations || bodyMode !in canonicalizations },
            )
        val signature = runCatching { base64.decode(tags["b"].orEmpty()) }.getOrNull()
        val bodyHash = runCatching { base64.decode(tags["bh"].orEmpty()) }.getOrNull()
        if (wrong.isNotEmpty() || signature == null || bodyHash == null) {
            return DkimVerdict(false, listOf(MailIssue(true, Res.string.dkim_verify_malformed, wrong.ifEmpty { listOf("b", "bh") }.distinct().joinToString(", "))))
        }
        if (algorithm == "rsa-sha1") return DkimVerdict(false, listOf(MailIssue(true, Res.string.dkim_verify_sha1)))

        val domain = tags.getValue("d").lowercase()
        val names = tags.getValue("h").split(':')
        val issues = mutableListOf<MailIssue>()
        tags["i"]?.substringAfterLast('@')?.lowercase()?.takeIf { it != domain && !it.endsWith(".$domain") }?.let {
            issues += MailIssue(true, Res.string.dkim_verify_identity, it, domain)
        }
        if (names.none { it.equals("from", ignoreCase = true) }) issues += MailIssue(true, Res.string.dkim_verify_from_unsigned)
        if (tags["x"]?.toLongOrNull()?.let { it < now } == true) issues += MailIssue(true, Res.string.dkim_verify_expired)
        val length = tags["l"]?.toIntOrNull()
        if (length != null) issues += MailIssue(false, Res.string.dkim_verify_partial_body)
        val canonical = EmailHeaders.latin1Bytes(canonicalBody(body, bodyMode == "relaxed"))
        if (!Sha256.digest(length?.let { canonical.copyOf(minOf(it, canonical.size)) } ?: canonical).contentEquals(bodyHash)) {
            issues += MailIssue(true, Res.string.dkim_verify_body_differs)
        }
        if (key == null) {
            issues += MailIssue(true, Res.string.dkim_not_found, "${tags.getValue("s")}._domainkey.$domain")
            return DkimVerdict(false, issues)
        }
        val keyIssues = key.issues
        issues += keyIssues
        val keyType = algorithm.substringBefore('-')
        when {
            key.type != keyType -> issues += MailIssue(true, Res.string.dkim_verify_key_type, key.type, keyType)
            keyType == "ed25519" -> {
                issues += MailIssue(false, Res.string.dkim_verify_ed25519)
                return DkimVerdict(false, issues)
            }
            keyIssues.any { it.error } -> {}
            !rsaMatches(key, signature, signedHeaders(names, fields, field, headerMode == "relaxed")) -> issues += MailIssue(true, Res.string.dkim_verify_bad_signature)
            issues.any { it.error } -> issues += MailIssue(false, Res.string.dkim_verify_headers_ok)
        }
        return DkimVerdict(issues.none { it.error }, issues)
    }

    // EMSA-PKCS1-v1_5, RFC 8017 section 9.2, compared after the public key operation
    private fun rsaMatches(key: DkimKey, signature: ByteArray, data: ByteArray): Boolean = runCatching {
        val (modulus, exponent) = Dkim.rsaKey(key.tags.getValue("p"))
        if (signature.size != modulus.size || modulus.size * 8 > Dkim.MAX_RSA_BITS) return false
        val digest = sha256DigestInfo + Sha256.digest(data)
        val encoded = byteArrayOf(0, 1) + ByteArray(modulus.size - digest.size - 3) { -1 } + byteArrayOf(0) + digest
        Rsa.modPow(signature, exponent, modulus).contentEquals(encoded)
    }.getOrDefault(false)
}
