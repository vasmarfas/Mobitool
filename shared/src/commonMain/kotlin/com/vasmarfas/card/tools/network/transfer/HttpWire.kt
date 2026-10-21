package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.TcpStream

// Android forbids cleartext HTTP through its own stacks, and LAN peers talk plain http
class WireReader(private val stream: TcpStream) {
    private val buffer = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0

    private suspend fun fill(): Boolean {
        if (start < end) return true
        start = 0
        end = stream.read(buffer, 0, buffer.size).coerceAtLeast(0)
        return end > 0
    }

    suspend fun line(limit: Int = 16 * 1024): String? {
        val out = StringBuilder()
        while (true) {
            if (!fill()) return if (out.isEmpty()) null else out.toString()
            while (start < end) {
                val c = buffer[start++].toInt() and 0xFF
                if (c == '\n'.code) return out.toString().removeSuffix("\r")
                out.append(c.toChar())
                if (out.length > limit) throw WireException("line too long")
            }
        }
    }

    suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (!fill()) return -1
        val count = minOf(length, end - start)
        buffer.copyInto(into, offset, start, start + count)
        start += count
        return count
    }

    suspend fun headers(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = line() ?: throw WireException("headers cut short")
            if (line.isEmpty()) return headers
            if (headers.size > 100) throw WireException("too many headers")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
}

class WireException(message: String) : Exception(message)

class WireBody(private val reader: WireReader, headers: Map<String, String>, untilClose: Boolean) {
    private val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    val length: Long? = if (chunked) null else headers["content-length"]?.toLongOrNull() ?: if (untilClose) null else 0L
    private var left = if (chunked) 0L else length ?: Long.MAX_VALUE
    private var done = !chunked && left == 0L

    suspend fun read(into: ByteArray, offset: Int = 0, count: Int = into.size - offset): Int {
        if (done) return -1
        if (chunked && left == 0L) {
            val size = reader.line()?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw WireException("bad chunk")
            if (size == 0L) {
                while (reader.line()?.isNotEmpty() == true) Unit
                done = true
                return -1
            }
            left = size
        }
        val read = reader.read(into, offset, minOf(count.toLong(), left).toInt())
        if (read < 0) {
            if (length == null && !chunked) done = true else throw WireException("body cut short")
            return -1
        }
        left -= read
        if (left == 0L) {
            if (chunked) reader.line() else done = true
        }
        return read
    }

    suspend fun bytes(limit: Int): ByteArray {
        val out = ArrayList<Byte>()
        val chunk = ByteArray(8192)
        while (true) {
            val read = read(chunk)
            if (read < 0) return out.toByteArray()
            if (out.size + read > limit) throw WireException("body over $limit bytes")
            for (i in 0 until read) out.add(chunk[i])
        }
    }

    suspend fun drain() {
        val chunk = ByteArray(8192)
        while (read(chunk) >= 0) Unit
    }
}

class WireRequest(val method: String, val target: String, val headers: Map<String, String>, val body: WireBody) {
    val path: String get() = target.substringBefore('?')
    val query: Map<String, String> by lazy {
        target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { percentDecode(it.substringBefore('=')) to percentDecode(it.substringAfter('=')) }
    }
}

class WireResponse(val status: Int, val headers: Map<String, String>, val body: WireBody)

suspend fun WireReader.request(): WireRequest? {
    val line = line() ?: return null
    val parts = line.split(' ')
    if (parts.size < 3) throw WireException("bad request line")
    val headers = headers()
    return WireRequest(parts[0].uppercase(), parts[1], headers, WireBody(this, headers, untilClose = false))
}

suspend fun WireReader.response(): WireResponse {
    val line = line() ?: throw WireException("no response")
    val status = line.split(' ').getOrNull(1)?.toIntOrNull() ?: throw WireException("bad status line")
    val headers = headers()
    return WireResponse(status, headers, WireBody(this, headers, untilClose = true))
}

private val reasons = mapOf(
    200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
    409 to "Conflict", 413 to "Payload Too Large", 429 to "Too Many Requests", 500 to "Internal Server Error",
)

suspend fun TcpStream.writeHead(status: Int, headers: Map<String, String>) {
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reasons[status] ?: "Status").append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }
    write(head.encodeToByteArray())
}

suspend fun TcpStream.reply(status: Int, body: ByteArray = ByteArray(0), type: String = "application/json") {
    writeHead(status, mapOf("Content-Type" to type, "Content-Length" to body.size.toString()))
    if (body.isNotEmpty()) write(body)
}

suspend fun TcpStream.writeRequest(method: String, host: String, target: String, headers: Map<String, String>) {
    val head = buildString {
        append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }
    write(head.encodeToByteArray())
}

fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) append(c.toChar()) else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}

fun percentDecode(text: String): String {
    val bytes = text.replace('+', ' ').encodeToByteArray()
    val out = ByteArray(bytes.size)
    var size = 0
    var i = 0
    while (i < bytes.size) {
        val high = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) bytes[i + 1].toInt().toChar().digitToIntOrNull(16) else null
        val low = high?.let { bytes[i + 2].toInt().toChar().digitToIntOrNull(16) }
        if (high != null && low != null) {
            out[size++] = (high * 16 + low).toByte()
            i += 3
        } else {
            out[size++] = bytes[i++]
        }
    }
    return out.decodeToString(0, size)
}
