package com.vasmarfas.card.tools.developer

data class ParsedUrl(
    val scheme: String?,
    val userInfo: String?,
    val host: String?,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }.map { UrlCodec.decode(it) ?: it }
    val params: List<Pair<String, String>> get() = UrlCodec.parseQuery(query ?: "")
    val defaultPort: Int? get() = when (scheme) {
        "http", "ws" -> 80
        "https", "wss" -> 443
        "ftp" -> 21
        "ssh", "sftp" -> 22
        "smtp" -> 25
        "dns" -> 53
        "pop3" -> 110
        "imap" -> 143
        "ldap" -> 389
        "rtsp" -> 554
        "mysql" -> 3306
        "postgres", "postgresql" -> 5432
        "redis" -> 6379
        "mongodb" -> 27017
        else -> null
    }
}

object UrlCodec {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    private const val RESERVED = ":/?#[]@!$&'()*+,;=%"
    private const val HEX = "0123456789ABCDEF"
    private val urlRegex = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$")

    private val hostWithPort = Regex("^(localhost|[\\w-]+(\\.[\\w-]+)+):\\d{1,5}([/?#].*)?$")

    fun encodeComponent(text: String, spaceAsPlus: Boolean = false): String = encode(text, UNRESERVED, spaceAsPlus)

    fun encodeFull(text: String): String = encode(text, UNRESERVED + RESERVED, false)

    private fun encode(text: String, keep: String, spaceAsPlus: Boolean): String {
        val bytes = text.encodeToByteArray()
        val sb = StringBuilder(bytes.size * 3)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            when {
                v == '%'.code && !(i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) -> sb.append("%25")
                v < 0x80 && v.toChar() in keep -> sb.append(v.toChar())
                spaceAsPlus && v == 0x20 -> sb.append('+')
                else -> sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
        }
        return sb.toString()
    }

    private fun isHex(b: Byte): Boolean = b.toInt().toChar().uppercaseChar() in HEX

    fun decode(text: String, plusAsSpace: Boolean = false): String? {
        val out = ByteArray(text.length * 3)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' -> {
                    if (i + 2 >= text.length) return null
                    val hi = text[i + 1].digitToIntOrNull(16) ?: return null
                    val lo = text[i + 2].digitToIntOrNull(16) ?: return null
                    out[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                }
                c == '+' && plusAsSpace -> {
                    out[n++] = ' '.code.toByte()
                    i++
                }
                else -> {
                    val end = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i + 2 else i + 1
                    for (b in text.substring(i, end).encodeToByteArray()) out[n++] = b
                    i = end
                }
            }
        }
        return out.copyOf(n).decodeToString()
    }

    fun parseQuery(query: String): List<Pair<String, String>> =
        query.removePrefix("?").split('&').filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            (decode(key, true) ?: key) to (decode(value, true) ?: value)
        }

    fun buildQuery(params: List<Pair<String, String>>): String =
        params.filter { it.first.isNotBlank() }.joinToString("&") { (k, v) -> encodeComponent(k.trim()) + "=" + encodeComponent(v) }

    fun parse(text: String): ParsedUrl? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (hostWithPort.matches(t)) return parse("http://$t")
        val m = urlRegex.find(t) ?: return null
        val scheme = m.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase()
        val authority = if (m.groupValues[3].isNotEmpty()) m.groupValues[4] else null
        if (scheme == null && authority == null) {
            return if (t.startsWith("//") || !t.contains('.')) null else parse("http://$t")
        }
        var userInfo: String? = null
        var host: String? = null
        var port: Int? = null
        if (authority != null) {
            var rest = authority
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                userInfo = decode(rest.substring(0, at)) ?: rest.substring(0, at)
                rest = rest.substring(at + 1)
            }
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) return null
                host = rest.substring(0, close + 1)
                val after = rest.substring(close + 1)
                when {
                    after.isEmpty() || after == ":" -> {}
                    after.startsWith(":") -> port = after.substring(1).toIntOrNull() ?: return null
                    else -> return null
                }
            } else {
                val colon = rest.lastIndexOf(':')
                if (colon >= 0) {
                    host = rest.substring(0, colon)
                    val p = rest.substring(colon + 1)
                    if (p.isNotEmpty()) port = p.toIntOrNull() ?: return null
                } else {
                    host = rest
                }
            }
            if (port != null && port !in 0..65535) return null
        }
        return ParsedUrl(
            scheme = scheme,
            userInfo = userInfo,
            host = host?.lowercase(),
            port = port,
            path = m.groupValues[5],
            query = if (m.groupValues[6].isNotEmpty()) m.groupValues[7] else null,
            fragment = if (m.groupValues[8].isNotEmpty()) m.groupValues[9] else null,
        )
    }
}
