package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Base64Tools
import com.vasmarfas.card.tools.developer.UrlCodec
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.charset
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.discard
import io.ktor.utils.io.readBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.resources.StringResource

val httpMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

enum class HttpBodyMode(val label: StringResource) {
    NONE(Res.string.request_none),
    RAW(Res.string.raw),
    FORM(Res.string.form),
}

enum class RawBodyType(val label: StringResource, val contentType: String) {
    JSON(Res.string.json, "application/json"),
    XML(Res.string.xml, "application/xml"),
    TEXT(Res.string.text, "text/plain"),
    FORM(Res.string.form_urlencoded, "application/x-www-form-urlencoded"),
}

enum class HttpAuthKind(val label: StringResource) {
    NONE(Res.string.request_none),
    BEARER(Res.string.bearer),
    BASIC(Res.string.basic),
    HEADER(Res.string.custom_header),
}

@Serializable
data class HttpField(val key: String, val value: String = "", val enabled: Boolean = true)

@Serializable
data class HttpAuth(
    val kind: HttpAuthKind = HttpAuthKind.NONE,
    val token: String = "",
    val user: String = "",
    val password: String = "",
    val headerName: String = "X-API-Key",
)

@Serializable
data class HttpRequestSpec(
    val method: String = "GET",
    val url: String = "https://httpbin.org/get",
    val headers: List<HttpField> = listOf(HttpField("Accept", "application/json")),
    val query: List<HttpField> = emptyList(),
    val bodyMode: HttpBodyMode = HttpBodyMode.NONE,
    val rawType: RawBodyType = RawBodyType.JSON,
    val rawBody: String = "",
    val form: List<HttpField> = emptyList(),
    val auth: HttpAuth = HttpAuth(),
    val followRedirects: Boolean = true,
    val timeoutMs: Long = HttpRequests.DEFAULT_TIMEOUT_MS,
)

@Serializable
data class SavedHttpRequest(val name: String, val spec: HttpRequestSpec)

@Serializable
data class HttpHistoryEntry(val spec: HttpRequestSpec, val status: Int, val timeMs: Long)

data class HttpCookie(val name: String, val value: String, val attributes: String)

data class HttpExchange(
    val status: Int,
    val statusText: String,
    val timeMs: Long,
    val sizeBytes: Long,
    val contentType: String?,
    val headers: List<Pair<String, String>>,
    val cookies: List<HttpCookie>,
    val body: String,
    val truncated: Boolean,
)

object HttpQuery {
    fun base(url: String): String = url.substringBefore('#').substringBefore('?')

    fun parse(url: String): List<HttpField> =
        UrlCodec.parseQuery(url.substringBefore('#').substringAfter('?', "")).map { (key, value) -> HttpField(key, value) }

    fun apply(url: String, params: List<HttpField>): String {
        val query = UrlCodec.buildQuery(params.filter { it.enabled }.map { it.key to it.value })
        val fragment = url.substringAfter('#', "")
        return base(url) + (if (query.isEmpty()) "" else "?$query") + (if (fragment.isEmpty()) "" else "#$fragment")
    }

    fun sync(url: String, params: List<HttpField>): List<HttpField> {
        val fromUrl = parse(url)
        return fromUrl + params.filter { param -> !param.enabled && fromUrl.none { it.key == param.key } }
    }
}

object HttpRequests {
    const val DEFAULT_TIMEOUT_MS = 30_000L

    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    private val bodyless = setOf("GET", "HEAD")
    private val directClient by lazy { Net.client.config { followRedirects = false } }

    fun synced(spec: HttpRequestSpec): HttpRequestSpec {
        val query = HttpQuery.sync(spec.url, spec.query)
        return spec.copy(url = HttpQuery.apply(spec.url, query), query = query)
    }

    fun targetUrl(spec: HttpRequestSpec): String {
        val trimmed = spec.url.trim()
        val url = HttpQuery.apply(trimmed, HttpQuery.sync(trimmed, spec.query))
        return if (url.contains("://")) url else "https://$url"
    }

    fun contentType(bodyMode: HttpBodyMode, rawType: RawBodyType): String =
        if (bodyMode == HttpBodyMode.FORM) RawBodyType.FORM.contentType else rawType.contentType

    fun bodyText(spec: HttpRequestSpec): String? = when {
        spec.method in bodyless || spec.bodyMode == HttpBodyMode.NONE -> null
        spec.bodyMode == HttpBodyMode.FORM -> UrlCodec.buildQuery(spec.form.filter { it.enabled }.map { it.key to it.value }).ifEmpty { null }
        else -> spec.rawBody.ifEmpty { null }
    }

    fun requestHeaders(spec: HttpRequestSpec): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        authHeader(spec.auth)?.let { list += it }
        spec.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { list += it.key.trim() to it.value.trim() }
        if (bodyText(spec) != null && list.none { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
            list += HttpHeaders.ContentType to contentType(spec.bodyMode, spec.rawType)
        }
        return list
    }

    fun authHeader(auth: HttpAuth): Pair<String, String>? = when (auth.kind) {
        HttpAuthKind.NONE -> null
        HttpAuthKind.BEARER -> auth.token.trim().takeIf { it.isNotEmpty() }?.let { HttpHeaders.Authorization to "Bearer $it" }
        HttpAuthKind.BASIC -> HttpHeaders.Authorization to basicCredentials(auth.user, auth.password)
        HttpAuthKind.HEADER -> auth.headerName.trim().takeIf { it.isNotEmpty() }?.let { it to auth.token.trim() }
    }

    fun basicCredentials(user: String, password: String): String =
        "Basic " + Base64Tools.encode("$user:$password".encodeToByteArray(), urlSafe = false, padding = true)

    fun looksLikeJson(text: String): Boolean = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    fun cookies(headers: List<Pair<String, String>>): List<HttpCookie> =
        headers.filter { it.first.equals(HttpHeaders.SetCookie, ignoreCase = true) }.map { (_, raw) ->
            val pair = raw.substringBefore(';')
            HttpCookie(pair.substringBefore('=').trim(), pair.substringAfter('=', "").trim(), raw.substringAfter(';', "").trim())
        }

    suspend fun execute(spec: HttpRequestSpec): HttpExchange {
        val client = if (spec.followRedirects) Net.client else directClient
        val started = currentEpochMillis()
        return client.prepareRequest(targetUrl(spec)) {
            this.method = HttpMethod.parse(spec.method)
            timeout { requestTimeoutMillis = spec.timeoutMs }
            requestHeaders(spec).forEach { (name, value) -> headers.append(name, value) }
            bodyText(spec)?.let { setBody(it) }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val kept = channel.readBuffer(MAX_BODY_BYTES)
            val keptBytes = kept.size
            val size = keptBytes + channel.discard()
            val text = (response.charset() ?: Charsets.UTF_8).newDecoder().decode(kept)
            val elapsed = currentEpochMillis() - started
            val received = response.headers.entries().flatMap { (name, values) -> values.map { name to it } }.sortedBy { it.first.lowercase() }
            HttpExchange(
                status = response.status.value,
                statusText = response.status.description,
                timeMs = elapsed,
                sizeBytes = if (size == 0L) (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) else size,
                contentType = response.headers[HttpHeaders.ContentType],
                headers = received,
                cookies = cookies(received),
                body = text,
                truncated = size > keptBytes,
            )
        }
    }
}

object HttpRequestStore {
    const val HISTORY_LIMIT = 20

    private const val HISTORY_KEY = "http.history"
    private const val SAVED_KEY = "http.saved"
    private val historySerializer = ListSerializer(HttpHistoryEntry.serializer())
    private val savedSerializer = ListSerializer(SavedHttpRequest.serializer())

    fun history(): List<HttpHistoryEntry> =
        runCatching { Prefs.store.get(HISTORY_KEY)?.let { Net.json.decodeFromString(historySerializer, it) } }.getOrNull()?.map(::withoutSecrets) ?: emptyList()

    fun saveHistory(list: List<HttpHistoryEntry>) = Prefs.store.put(HISTORY_KEY, Net.json.encodeToString(historySerializer, list))

    fun saved(): List<SavedHttpRequest> =
        runCatching { Prefs.store.get(SAVED_KEY)?.let { Net.json.decodeFromString(savedSerializer, it) } }.getOrNull() ?: emptyList()

    fun saveAll(list: List<SavedHttpRequest>) = Prefs.store.put(SAVED_KEY, Net.json.encodeToString(savedSerializer, list))

    fun push(list: List<HttpHistoryEntry>, entry: HttpHistoryEntry): List<HttpHistoryEntry> {
        val stripped = withoutSecrets(entry)
        return (listOf(stripped) + list.filterNot { it.spec.method == stripped.spec.method && it.spec.url == stripped.spec.url })
            .take(HISTORY_LIMIT)
    }

    private val secretNames = listOf("auth", "cookie", "key", "token", "secret", "pass", "session", "signature")

    private val userInfo = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@")

    private fun HttpField.blankIfSecret(): HttpField =
        if (secretNames.any { key.contains(it, ignoreCase = true) }) copy(value = "") else this

    private fun withoutSecrets(entry: HttpHistoryEntry): HttpHistoryEntry {
        val spec = entry.spec
        val query = spec.query.map { it.blankIfSecret() }
        val url = if (query == spec.query) spec.url else HttpQuery.apply(spec.url, query)
        return entry.copy(
            spec = spec.copy(
                url = userInfo.replace(url, "\$1"),
                headers = spec.headers.map { it.blankIfSecret() },
                query = query,
                rawBody = "",
                form = spec.form.map { it.copy(value = "") },
                auth = spec.auth.copy(token = "", user = "", password = ""),
            ),
        )
    }
}

object Curl {
    private val dataFlags = setOf("-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode")
    private val ignoredWithValue = setOf(
        "-o", "--output", "-e", "--referer", "-x", "--proxy", "--connect-timeout", "-T", "--upload-file",
        "--cacert", "-F", "--form", "-w", "--write-out", "--resolve", "--retry", "--limit-rate", "--interface",
    )

    fun build(spec: HttpRequestSpec): String {
        val parts = mutableListOf("curl")
        if (spec.method != "GET") parts += "-X ${spec.method}"
        parts += quote(HttpRequests.targetUrl(spec))
        if (spec.followRedirects) parts += "-L"
        if (spec.timeoutMs != HttpRequests.DEFAULT_TIMEOUT_MS) parts += "--max-time ${spec.timeoutMs / 1000}"
        HttpRequests.requestHeaders(spec).forEach { (name, value) -> parts += "-H ${quote("$name: $value")}" }
        HttpRequests.bodyText(spec)?.let { parts += "--data-raw ${quote(it)}" }
        return parts.joinToString(" \\\n  ")
    }

    fun parse(text: String): HttpRequestSpec? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        var method: String? = null
        var url = ""
        var auth = HttpAuth()
        var follow = false
        var head = false
        var asQuery = false
        var timeoutMs = HttpRequests.DEFAULT_TIMEOUT_MS
        val headers = mutableListOf<HttpField>()
        val data = mutableListOf<String>()
        var i = if (isCurlCommand(tokens.first())) 1 else 0
        while (i < tokens.size) {
            val token = tokens[i]
            val value = tokens.getOrNull(i + 1)
            when {
                token == "-X" || token == "--request" -> {
                    method = value?.uppercase()
                    i += 2
                }
                token == "-H" || token == "--header" -> {
                    value?.let { headers += headerField(it) }
                    i += 2
                }
                token in dataFlags -> {
                    value?.let { data += it }
                    i += 2
                }
                token == "-u" || token == "--user" -> {
                    value?.let { auth = HttpAuth(HttpAuthKind.BASIC, user = it.substringBefore(':'), password = it.substringAfter(':', "")) }
                    i += 2
                }
                token == "-A" || token == "--user-agent" -> {
                    value?.let { headers += HttpField(HttpHeaders.UserAgent, it) }
                    i += 2
                }
                token == "-b" || token == "--cookie" -> {
                    value?.let { headers += HttpField(HttpHeaders.Cookie, it) }
                    i += 2
                }
                token == "--url" -> {
                    value?.let { url = it }
                    i += 2
                }
                token == "-m" || token == "--max-time" -> {
                    timeoutMs = value?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: timeoutMs
                    i += 2
                }
                token == "-L" || token == "--location" -> {
                    follow = true
                    i++
                }
                token == "-I" || token == "--head" -> {
                    head = true
                    i++
                }
                token == "-G" || token == "--get" -> {
                    asQuery = true
                    i++
                }
                token.startsWith("-X") && token.length > 2 -> {
                    method = token.drop(2).uppercase()
                    i++
                }
                token.startsWith("-") -> i += if (token in ignoredWithValue) 2 else 1
                else -> {
                    if (url.isEmpty()) url = token
                    i++
                }
            }
        }
        if (url.isEmpty()) return null

        val joined = data.joinToString("&")
        val target = if (asQuery && joined.isNotEmpty()) url + (if (url.contains('?')) "&" else "?") + joined else url
        val body = if (asQuery) "" else joined
        val declared = headers.firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }?.value?.trim()
        val bodyMode = when {
            body.isEmpty() -> HttpBodyMode.NONE
            declared?.contains("x-www-form-urlencoded", ignoreCase = true) == true -> HttpBodyMode.FORM
            else -> HttpBodyMode.RAW
        }
        val rawType = when {
            declared == null -> if (HttpRequests.looksLikeJson(body)) RawBodyType.JSON else RawBodyType.TEXT
            declared.contains("json", ignoreCase = true) -> RawBodyType.JSON
            declared.contains("xml", ignoreCase = true) -> RawBodyType.XML
            declared.contains("x-www-form-urlencoded", ignoreCase = true) -> RawBodyType.FORM
            else -> RawBodyType.TEXT
        }
        val authorization = headers.firstOrNull { it.key.equals(HttpHeaders.Authorization, ignoreCase = true) }?.value?.trim()
        if (auth.kind == HttpAuthKind.NONE && authorization != null) {
            auth = when {
                authorization.startsWith("Bearer ", ignoreCase = true) -> HttpAuth(HttpAuthKind.BEARER, token = authorization.substring(7).trim())
                authorization.startsWith("Basic ", ignoreCase = true) -> decodeBasic(authorization.substring(6).trim()) ?: auth
                else -> auth
            }
        }
        val generatedContentType = HttpRequests.contentType(bodyMode, rawType)
        val kept = headers.filterNot { field ->
            (auth.kind != HttpAuthKind.NONE && field.key.equals(HttpHeaders.Authorization, ignoreCase = true)) ||
                (
                    bodyMode != HttpBodyMode.NONE &&
                        field.key.equals(HttpHeaders.ContentType, ignoreCase = true) &&
                        field.value.trim().equals(generatedContentType, ignoreCase = true)
                    )
        }
        return HttpRequestSpec(
            method = method ?: when {
                head -> "HEAD"
                body.isNotEmpty() -> "POST"
                else -> "GET"
            },
            url = target,
            headers = kept,
            query = HttpQuery.parse(target),
            bodyMode = bodyMode,
            rawType = rawType,
            rawBody = if (bodyMode == HttpBodyMode.RAW) body else "",
            form = if (bodyMode == HttpBodyMode.FORM) UrlCodec.parseQuery(body).map { (key, value) -> HttpField(key, value) } else emptyList(),
            auth = auth,
            followRedirects = follow,
            timeoutMs = timeoutMs,
        )
    }

    private fun isCurlCommand(token: String): Boolean {
        val name = token.trim().lowercase().removeSuffix(".exe")
        return !name.contains("://") && (name == "curl" || name.endsWith("/curl"))
    }

    private fun headerField(raw: String): HttpField {
        val colon = raw.indexOf(':')
        return if (colon < 0) HttpField(raw.trim()) else HttpField(raw.substring(0, colon).trim(), raw.substring(colon + 1).trim())
    }

    private fun decodeBasic(encoded: String): HttpAuth? {
        val decoded = Base64Tools.decode(encoded)?.let { Base64Tools.utf8OrNull(it) } ?: return null
        if (!decoded.contains(':')) return null
        return HttpAuth(HttpAuthKind.BASIC, user = decoded.substringBefore(':'), password = decoded.substringAfter(':'))
    }

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote = ' '
        var started = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != ' ' && c == quote -> {
                    quote = ' '
                    i++
                }
                quote == ' ' && (c == '\'' || c == '"') -> {
                    quote = c
                    started = true
                    i++
                }
                quote != '\'' && c == '\\' && i + 1 < text.length -> {
                    val next = text[i + 1]
                    if (next == '\n' || next == '\r') {
                        i += 2
                    } else {
                        current.append(next)
                        started = true
                        i += 2
                    }
                }
                quote == ' ' && c.isWhitespace() -> {
                    if (started) {
                        tokens += current.toString()
                        current.clear()
                        started = false
                    }
                    i++
                }
                else -> {
                    current.append(c)
                    started = true
                    i++
                }
            }
        }
        if (started) tokens += current.toString()
        return tokens
    }
}
