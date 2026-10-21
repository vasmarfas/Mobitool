package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.tools.developer.MimeTypes
import com.vasmarfas.card.tools.documents.HtmlReader
import com.vasmarfas.card.tools.documents.TxtWriter
import com.vasmarfas.card.tools.text.TextCleaner
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlinx.datetime.format.DateTimeComponents

class MailHeader(val name: String, val value: String)

class MailHop(val from: String, val ip: String, val by: String, val with: String, val time: Long?, val delay: Long?)

class MailAuth(val method: String, val result: String, val domain: String, val checkedBy: String)

class MailSignature(val domain: String, val selector: String)

class MailAttachment(val name: String, val type: String, val bytes: ByteArray)

class MailMessage(
    val headers: List<MailHeader>,
    val hops: List<MailHop>,
    val auth: List<MailAuth>,
    val signatures: List<MailSignature>,
    val text: String?,
    val attachments: List<MailAttachment>,
) {
    fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    val totalDelay: Long?
        get() {
            val start = header("Date")?.let(EmailHeaders::parseDate) ?: hops.firstNotNullOfOrNull { it.time } ?: return null
            return hops.lastOrNull { it.time != null }?.time?.minus(start)
        }

    fun foreignDomains(): List<Pair<String, String>> {
        fun organization(domain: String) = domain.split('.').takeLast(2)
        val from = header("From")?.let(EmailHeaders::addressDomain) ?: return emptyList()
        return listOf("Return-Path", "Reply-To").mapNotNull { name ->
            header(name)?.let(EmailHeaders::addressDomain)?.takeIf { organization(it) != organization(from) }?.let { name to it }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
object EmailHeaders {
    const val MAX_BYTES = 64 * 1024 * 1024

    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val mimeBase64 = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val blankLine = Regex("\r?\n\r?\n")
    private val headerName = Regex("[!-9;-~]+")
    private val encodedWord = Regex("""=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?=""")
    private val parameter = Regex(""";\s*([^\s=;]+)\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)""")
    private val escaped = Regex("""\\(.)""")
    private val whitespace = Regex("\\s+")
    private val receivedFrom = Regex("""(?i)\bfrom\s+([^\s;()]+)""")
    private val receivedBy = Regex("""(?i)\bby\s+([^\s;()]+)""")
    private val receivedWith = Regex("""(?i)\bwith\s+([^\s;()]+)""")
    private val bracketedIp = Regex("""[\[(](?:IPv6:)?([0-9A-Fa-f:.]{3,})[\])]""")
    private val angleAddress = Regex("<([^>]*)>")

    fun parse(bytes: ByteArray): MailMessage {
        val raw = TextDecoding.decode(bytes, "latin1")
        val (head, body) = splitHead(raw)
        val headers = headers(head)
        val texts = mutableListOf<Pair<String, String>>()
        val attachments = mutableListOf<MailAttachment>()
        walk(headers, body, texts, attachments, 0)
        val text = texts.firstOrNull { it.first == "text/plain" }?.second
            ?: texts.firstOrNull { it.first == "text/html" }?.second?.let(::htmlToText)
        return MailMessage(
            headers = headers,
            hops = hops(headers),
            auth = headers.filter { it.name.equals("Authentication-Results", true) }.flatMap { authResults(it.value, arc = false) } +
                headers.filter { it.name.equals("ARC-Authentication-Results", true) }.flatMap { authResults(it.value, arc = true) },
            signatures = headers.filter { it.name.equals("DKIM-Signature", true) }.map { tags(it.value) }
                .map { MailSignature(it["d"].orEmpty(), it["s"].orEmpty()) },
            text = text?.trim()?.takeIf { it.isNotEmpty() },
            attachments = attachments,
        )
    }

    fun tags(value: String): Map<String, String> = value.split(';').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).filterNot { it.isWhitespace() }
    }.toMap()

    fun addressDomain(value: String): String? {
        val address = angleAddress.find(value)?.groupValues?.get(1) ?: value
        return address.substringAfterLast('@', "").trim().trimEnd('.').lowercase().ifEmpty { null }
    }

    fun parseDate(text: String): Long? = runCatching {
        DateTimeComponents.Formats.RFC_1123.parse(stripComments(text).substringAfter(',').trim().replace(whitespace, " "))
            .toInstantUsingOffset().epochSeconds
    }.getOrNull()

    fun decodeWords(text: String): String {
        if ("=?" !in text) return text
        val out = StringBuilder()
        var pending = ByteArray(0)
        var charset = ""
        var last = 0
        var afterWord = false
        fun flush() {
            if (pending.isNotEmpty()) out.append(decodeText(pending, charset))
            pending = ByteArray(0)
        }
        for (match in encodedWord.findAll(text)) {
            val gap = text.substring(last, match.range.first)
            val wordCharset = match.groupValues[1].substringBefore('*')
            val joined = afterWord && gap.isBlank()
            if (!joined || !wordCharset.equals(charset, ignoreCase = true)) flush()
            if (!joined) out.append(gap)
            val data = match.groupValues[3]
            pending += if (match.groupValues[2].equals("B", ignoreCase = true)) {
                runCatching { base64.decode(data) }.getOrElse { data.encodeToByteArray() }
            } else {
                hexEscapes(data.replace('_', ' '), '=')
            }
            charset = wordCharset
            last = match.range.last + 1
            afterWord = true
        }
        flush()
        return out.append(text.substring(last)).toString()
    }

    private fun splitHead(raw: String): Pair<String, String> {
        val gap = blankLine.find(raw) ?: return raw to ""
        return raw.substring(0, gap.range.first) to raw.substring(gap.range.last + 1)
    }

    private fun headers(block: String): List<MailHeader> {
        val fields = mutableListOf<Pair<String, StringBuilder>>()
        for (line in block.lines()) {
            if (line.isBlank()) continue
            if (line[0] == ' ' || line[0] == '\t') {
                fields.lastOrNull()?.second?.append(line)
                continue
            }
            val name = line.substringBefore(':', "").trimEnd()
            if (headerName.matches(name)) fields += name to StringBuilder(line.substringAfter(':'))
        }
        return fields.map { (name, value) -> MailHeader(name, decodeWords(latin1ToText(value.toString().trim()))) }
    }

    private fun latin1ToText(text: String): String =
        if (text.all { it.code < 0x80 }) text else TextDecoding.decode(latin1Bytes(text))

    private fun latin1Bytes(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

    private fun decodeText(bytes: ByteArray, charset: String): String =
        if (charset.isBlank()) TextDecoding.decode(bytes) else runCatching { TextDecoding.decode(bytes, charset) }.getOrElse { TextDecoding.decode(bytes) }

    // quoted-printable with '=', RFC 2231 values with '%'
    private fun hexEscapes(text: String, marker: Char): ByteArray {
        val out = ByteArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == marker) {
                when {
                    text.startsWith("\r\n", i + 1) -> { i += 3; continue }
                    text.startsWith("\n", i + 1) -> { i += 2; continue }
                }
                val high = text.getOrNull(i + 1)?.digitToIntOrNull(16)
                val low = text.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (high != null && low != null) {
                    out[n++] = (high * 16 + low).toByte()
                    i += 3
                    continue
                }
            }
            out[n++] = c.code.toByte()
            i++
        }
        return out.copyOf(n)
    }

    private fun stripComments(text: String): String = buildString {
        var depth = 0
        for (c in text) {
            when {
                c == '(' -> depth++
                c == ')' && depth > 0 -> depth--
                depth == 0 -> append(c)
            }
        }
    }

    private fun hops(headers: List<MailHeader>): List<MailHop> {
        var previous = headers.firstOrNull { it.name.equals("Date", true) }?.value?.let(::parseDate)
        return headers.filter { it.name.equals("Received", true) }.reversed().map { header ->
            val clauses = header.value.substringBeforeLast(';')
            val plain = stripComments(clauses)
            val from = receivedFrom.find(plain)?.groupValues?.get(1).orEmpty()
            val fromAt = receivedFrom.find(clauses)?.range?.last?.plus(1) ?: 0
            val byAt = receivedBy.find(clauses, fromAt)?.range?.first ?: clauses.length
            val ip = if (from.isEmpty()) null else bracketedIp.findAll(clauses.substring(fromAt, byAt)).map { it.groupValues[1] }
                .firstOrNull { Ipv4.parse(it) != null || Ipv6Address.parse(it) != null }
            val time = if (';' in header.value) parseDate(header.value.substringAfterLast(';')) else null
            val delay = time?.let { t -> previous?.let { t - it } }
            if (time != null) previous = time
            MailHop(
                from = from,
                ip = ip.orEmpty(),
                by = receivedBy.find(plain)?.groupValues?.get(1).orEmpty(),
                with = receivedWith.find(plain)?.groupValues?.get(1).orEmpty(),
                time = time,
                delay = delay,
            )
        }
    }

    private fun authResults(value: String, arc: Boolean): List<MailAuth> {
        var clauses = stripComments(value).split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val instance = clauses.firstOrNull()?.takeIf { arc && it.startsWith("i=") }?.substringAfter('=')
        if (instance != null) clauses = clauses.drop(1)
        val server = clauses.firstOrNull()?.split(whitespace)?.first()?.takeIf { '=' !in it }
        if (server != null) clauses = clauses.drop(1)
        val checkedBy = listOfNotNull(instance?.let { "ARC i=$it" }, server).joinToString(", ")
        return clauses.mapNotNull { clause ->
            val words = clause.split(whitespace)
            val method = words.first().substringBefore('=', "").lowercase().ifEmpty { return@mapNotNull null }
            val props = words.drop(1).filter { '=' in it }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val domain = when (method) {
                "spf" -> props["smtp.mailfrom"]?.substringAfterLast('@') ?: props["smtp.helo"]
                "dmarc" -> props["header.from"]
                else -> props["header.d"] ?: props["header.i"]?.substringAfterLast('@') ?: props["header.from"]
            }
            MailAuth(method, words.first().substringAfter('=').lowercase(), domain.orEmpty().lowercase(), checkedBy)
        }
    }

    private fun walk(headers: List<MailHeader>, body: String, texts: MutableList<Pair<String, String>>, files: MutableList<MailAttachment>, depth: Int) {
        fun header(name: String) = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
        val contentType = header("Content-Type") ?: "text/plain"
        val type = contentType.substringBefore(';').trim().lowercase()
        val params = parameters(contentType)
        if (type.startsWith("multipart/")) {
            val boundary = params["boundary"] ?: return
            if (depth < 20) parts(body, boundary).forEach { part ->
                val (head, content) = splitHead(part)
                walk(headers(head), content, texts, files, depth + 1)
            }
            return
        }
        val disposition = header("Content-Disposition").orEmpty()
        val name = parameters(disposition)["filename"] ?: params["name"]
        val data = when (header("Content-Transfer-Encoding")?.trim()?.lowercase()) {
            "base64" -> runCatching { mimeBase64.decode(body) }.getOrElse { latin1Bytes(body) }
            "quoted-printable" -> hexEscapes(body, '=')
            else -> latin1Bytes(body)
        }
        if (disposition.trim().startsWith("attachment", ignoreCase = true) || name != null || !type.startsWith("text/")) {
            val extension = MimeTypes.search(type).firstOrNull { it.type == type }?.extensions?.firstOrNull()
            files += MailAttachment(name ?: ("attachment-${files.size + 1}" + extension?.let { ".$it" }.orEmpty()), type, data)
        } else {
            texts += type to decodeText(data, params["charset"].orEmpty())
        }
    }

    private fun parameters(value: String): Map<String, String> {
        val found = parameter.findAll(value).map { match ->
            val raw = match.groupValues[2].trim()
            match.groupValues[1].lowercase() to if (raw.startsWith('"')) raw.substring(1, raw.length - 1).replace(escaped, "$1") else raw
        }.toList()
        val result = found.filter { '*' !in it.first }.toMap().toMutableMap()
        // RFC 2231: name*=utf-8''%D0%B0 and name*0*=, name*1*= continuations
        found.filter { '*' in it.first }.groupBy { it.first.substringBefore('*') }.forEach { (name, pieces) ->
            val sorted = pieces.sortedBy { it.first.substringAfter('*').trimEnd('*').toIntOrNull() ?: 0 }
            val joined = sorted.joinToString("") { it.second }
            result[name] = if (sorted.first().first.endsWith('*')) {
                decodeText(hexEscapes(joined.substringAfter('\'').substringAfter('\''), '%'), joined.substringBefore('\''))
            } else {
                joined
            }
        }
        return result
    }

    private fun parts(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val result = mutableListOf<String>()
        var at = if (body.startsWith(delimiter)) 0 else body.indexOf("\n$delimiter").takeIf { it >= 0 }?.plus(1) ?: return result
        while (!body.startsWith("--", at + delimiter.length)) {
            val start = body.indexOf('\n', at).takeIf { it >= 0 }?.plus(1) ?: break
            val next = body.indexOf("\n$delimiter", start)
            if (next < 0) {
                result += body.substring(start)
                break
            }
            result += body.substring(start, if (next > start && body[next - 1] == '\r') next - 1 else next)
            at = next + 1
        }
        return result
    }

    private fun htmlToText(html: String): String =
        runCatching { TxtWriter.write(HtmlReader(resource = null).read(html)) }.getOrElse { TextCleaner.stripHtml(html) }
}
