package com.vasmarfas.card.tools.network

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlTest {
    @Test
    fun parsesMethodHeadersAndBody() {
        val spec = Curl.parse(
            """
            curl -X POST 'https://api.example.com/v1/items?limit=10' \
              -H 'Accept: application/json' \
              -H "Content-Type: application/json" \
              --data-raw '{"name":"first"}'
            """.trimIndent(),
        )
        assertNotNull(spec)
        assertEquals("POST", spec.method)
        assertEquals("https://api.example.com/v1/items?limit=10", spec.url)
        assertEquals(listOf(HttpField("Accept", "application/json")), spec.headers)
        assertEquals(listOf(HttpField("limit", "10")), spec.query)
        assertEquals(HttpBodyMode.RAW, spec.bodyMode)
        assertEquals(RawBodyType.JSON, spec.rawType)
        assertEquals("""{"name":"first"}""", spec.rawBody)
    }

    @Test
    fun infersMethodFromData() {
        val get = Curl.parse("curl https://example.com/api")
        assertNotNull(get)
        assertEquals("GET", get.method)
        assertEquals(HttpBodyMode.NONE, get.bodyMode)

        val post = Curl.parse("curl https://example.com/api -d 'a=1&b=2' -H 'Content-Type: application/x-www-form-urlencoded'")
        assertNotNull(post)
        assertEquals("POST", post.method)
        assertEquals(HttpBodyMode.FORM, post.bodyMode)
        assertEquals(listOf(HttpField("a", "1"), HttpField("b", "2")), post.form)
        assertTrue(post.headers.isEmpty())

        assertEquals("HEAD", Curl.parse("curl -I https://example.com")?.method)
        assertNull(Curl.parse("curl -L -s"))
    }

    @Test
    fun readsAuthRedirectsAndTimeout() {
        val bearer = Curl.parse("""curl -L --max-time 5 -H "Authorization: Bearer abc.def" https://example.com""")
        assertNotNull(bearer)
        assertEquals(HttpAuth(HttpAuthKind.BEARER, token = "abc.def"), bearer.auth)
        assertTrue(bearer.followRedirects)
        assertEquals(5_000L, bearer.timeoutMs)
        assertTrue(bearer.headers.isEmpty())

        val basic = Curl.parse("curl -u user:pass https://example.com")
        assertNotNull(basic)
        assertEquals(HttpAuth(HttpAuthKind.BASIC, user = "user", password = "pass"), basic.auth)
        assertEquals(HttpAuth(HttpAuthKind.BASIC, user = "user", password = "pass"), Curl.parse("curl -H 'Authorization: Basic dXNlcjpwYXNz' https://example.com")?.auth)
    }

    @Test
    fun handlesQuotesAndUnknownFlags() {
        val spec = Curl.parse("""curl -s -k --compressed -X PUT "https://example.com/a" -H 'X-Note: it'\''s fine' -o out.txt""")
        assertNotNull(spec)
        assertEquals("PUT", spec.method)
        assertEquals("https://example.com/a", spec.url)
        assertEquals(listOf(HttpField("X-Note", "it's fine")), spec.headers)
    }

    @Test
    fun movesDataIntoQueryWithGetFlag() {
        val spec = Curl.parse("curl -G https://example.com/search -d 'q=kotlin' -d 'page=2'")
        assertNotNull(spec)
        assertEquals("GET", spec.method)
        assertEquals("https://example.com/search?q=kotlin&page=2", spec.url)
        assertEquals(HttpBodyMode.NONE, spec.bodyMode)
        assertEquals(listOf(HttpField("q", "kotlin"), HttpField("page", "2")), spec.query)
    }

    @Test
    fun buildAndParseRoundTrip() {
        val spec = HttpRequestSpec(
            method = "POST",
            url = "https://api.example.com/v1/items?limit=10",
            headers = listOf(HttpField("Accept", "application/json")),
            query = listOf(HttpField("limit", "10")),
            bodyMode = HttpBodyMode.RAW,
            rawType = RawBodyType.JSON,
            rawBody = """{"name":"x"}""",
            auth = HttpAuth(HttpAuthKind.BEARER, token = "t0ken"),
            followRedirects = true,
            timeoutMs = 45_000,
        )
        val command = Curl.build(spec)
        assertTrue(command.contains("-X POST"))
        assertTrue(command.contains("-H 'Authorization: Bearer t0ken'"))
        assertTrue(command.contains("--max-time 45"))
        assertTrue(command.contains("""--data-raw '{"name":"x"}'"""))
        assertEquals(spec, Curl.parse(command))
    }

    @Test
    fun quotesValuesWithApostrophes() {
        val spec = HttpRequestSpec(
            method = "POST",
            url = "https://example.com",
            headers = listOf(HttpField("X-Note", "it's fine")),
            bodyMode = HttpBodyMode.RAW,
            rawType = RawBodyType.TEXT,
            rawBody = "don't",
            followRedirects = false,
        )
        assertEquals(spec, Curl.parse(Curl.build(spec)))
    }
}

class HttpQueryTest {
    @Test
    fun readsParamsFromUrl() {
        assertEquals(
            listOf(HttpField("q", "hello world"), HttpField("page", "2")),
            HttpQuery.parse("https://example.com/search?q=hello%20world&page=2"),
        )
        assertEquals("https://example.com/search", HttpQuery.base("https://example.com/search?q=1#top"))
        assertTrue(HttpQuery.parse("https://example.com/search").isEmpty())
    }

    @Test
    fun writesEnabledParamsBackIntoUrl() {
        assertEquals(
            "https://example.com/search?q=hello%20world#top",
            HttpQuery.apply(
                "https://example.com/search?q=1#top",
                listOf(HttpField("q", "hello world"), HttpField("page", "2", enabled = false)),
            ),
        )
        assertEquals("https://example.com/search", HttpQuery.apply("https://example.com/search?q=1", emptyList()))
    }

    @Test
    fun keepsDisabledParamsWhenUrlChanges() {
        assertEquals(
            listOf(HttpField("q", "2"), HttpField("sort", "asc"), HttpField("debug", "true", enabled = false)),
            HttpQuery.sync(
                "https://example.com/search?q=2&sort=asc",
                listOf(HttpField("q", "1"), HttpField("debug", "true", enabled = false)),
            ),
        )
    }

    @Test
    fun specKeepsUrlAndParamsTogether() {
        val spec = HttpRequests.synced(HttpRequestSpec(url = "example.com/a?x=1"))
        assertEquals(listOf(HttpField("x", "1")), spec.query)
        assertEquals("https://example.com/a?x=1", HttpRequests.targetUrl(spec))

        val params = spec.query.map { it.copy(enabled = false) }
        val disabled = spec.copy(query = params, url = HttpQuery.apply(spec.url, params))
        assertEquals("https://example.com/a", HttpRequests.targetUrl(disabled))
        assertEquals(params, HttpQuery.sync(disabled.url, disabled.query))
    }
}

class HttpAuthTest {
    @Test
    fun basicIsBase64Encoded() {
        assertEquals("Basic dXNlcjpwYXNz", HttpRequests.basicCredentials("user", "pass"))
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", HttpRequests.basicCredentials("Aladdin", "open sesame"))
        assertEquals(
            listOf("Authorization" to "Basic dXNlcjpwYXNz"),
            HttpRequests.requestHeaders(HttpRequestSpec(headers = emptyList(), auth = HttpAuth(HttpAuthKind.BASIC, user = "user", password = "pass"))),
        )
    }

    @Test
    fun bearerAndCustomHeader() {
        assertEquals(
            listOf("Authorization" to "Bearer abc"),
            HttpRequests.requestHeaders(HttpRequestSpec(headers = emptyList(), auth = HttpAuth(HttpAuthKind.BEARER, token = " abc "))),
        )
        assertEquals(
            listOf("X-Api-Key" to "secret"),
            HttpRequests.requestHeaders(HttpRequestSpec(headers = emptyList(), auth = HttpAuth(HttpAuthKind.HEADER, token = "secret", headerName = "X-Api-Key"))),
        )
        assertNull(HttpRequests.authHeader(HttpAuth()))
        assertNull(HttpRequests.authHeader(HttpAuth(HttpAuthKind.BEARER)))
    }

    @Test
    fun contentTypeFollowsTheBody() {
        val json = HttpRequestSpec(method = "POST", headers = emptyList(), bodyMode = HttpBodyMode.RAW, rawBody = "{}")
        assertEquals(listOf("Content-Type" to "application/json"), HttpRequests.requestHeaders(json))
        assertNull(HttpRequests.bodyText(json.copy(method = "GET")))

        val form = HttpRequestSpec(method = "POST", headers = emptyList(), bodyMode = HttpBodyMode.FORM, form = listOf(HttpField("a", "b c")))
        assertEquals("a=b%20c", HttpRequests.bodyText(form))
        assertEquals(listOf("Content-Type" to "application/x-www-form-urlencoded"), HttpRequests.requestHeaders(form))

        val declared = json.copy(headers = listOf(HttpField("Content-Type", "application/json; charset=utf-8")))
        assertEquals(listOf("Content-Type" to "application/json; charset=utf-8"), HttpRequests.requestHeaders(declared))
    }

    @Test
    fun readsSetCookieHeaders() {
        val cookies = HttpRequests.cookies(
            listOf(
                "Content-Type" to "text/html",
                "set-cookie" to "sid=abc123; Path=/; HttpOnly",
                "Set-Cookie" to "theme=dark",
            ),
        )
        assertEquals(listOf(HttpCookie("sid", "abc123", "Path=/; HttpOnly"), HttpCookie("theme", "dark", "")), cookies)
    }
}

class HttpHistoryTest {
    @Test
    fun historySurvivesJson() {
        val serializer = ListSerializer(HttpHistoryEntry.serializer())
        val list = listOf(
            HttpHistoryEntry(
                HttpRequestSpec(method = "POST", url = "https://api.example.com/items", bodyMode = HttpBodyMode.RAW, rawBody = "{}"),
                201,
                128,
            ),
            HttpHistoryEntry(HttpRequestSpec(), 200, 42),
        )
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }

    @Test
    fun savedRequestsSurviveJson() {
        val serializer = ListSerializer(SavedHttpRequest.serializer())
        val list = listOf(
            SavedHttpRequest(
                "Items",
                HttpRequestSpec(
                    url = "https://api.example.com/items?limit=10",
                    query = listOf(HttpField("limit", "10"), HttpField("debug", "true", enabled = false)),
                    auth = HttpAuth(HttpAuthKind.BASIC, user = "u", password = "p"),
                    timeoutMs = 5_000,
                ),
            ),
        )
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }

    @Test
    fun pushKeepsLastTwentyWithoutDuplicates() {
        var history = emptyList<HttpHistoryEntry>()
        repeat(25) { index ->
            history = HttpRequestStore.push(history, HttpHistoryEntry(HttpRequestSpec(url = "https://example.com/$index"), 200, index.toLong()))
        }
        assertEquals(HttpRequestStore.HISTORY_LIMIT, history.size)
        assertEquals("https://example.com/24", history.first().spec.url)

        val repeated = HttpRequestStore.push(history, HttpHistoryEntry(HttpRequestSpec(url = "https://example.com/20"), 500, 1))
        assertEquals(HttpRequestStore.HISTORY_LIMIT, repeated.size)
        assertEquals(500, repeated.first().status)
        assertEquals(1, repeated.count { it.spec.url == "https://example.com/20" })
    }

    @Test
    fun historyKeepsNoSecrets() {
        val spec = HttpRequests.synced(
            HttpRequestSpec(
                method = "POST",
                url = "https://api.example.com/items?api_key=abc&limit=10",
                headers = listOf(HttpField("X-API-Key", "k1"), HttpField("Cookie", "sid=1"), HttpField("Accept", "application/json")),
                bodyMode = HttpBodyMode.RAW,
                rawBody = "password=hunter2",
                auth = HttpAuth(HttpAuthKind.BEARER, token = "t0ken"),
            ),
        )
        val stored = HttpRequestStore.push(emptyList(), HttpHistoryEntry(spec, 200, 10)).single().spec
        assertEquals("https://api.example.com/items?api_key=&limit=10", stored.url)
        assertEquals(listOf(HttpField("api_key", ""), HttpField("limit", "10")), stored.query)
        assertEquals(listOf(HttpField("X-API-Key", ""), HttpField("Cookie", ""), HttpField("Accept", "application/json")), stored.headers)
        assertEquals("", stored.rawBody)
        assertEquals(HttpAuth(HttpAuthKind.BEARER), stored.auth)
        val basic = HttpRequestStore.push(emptyList(), HttpHistoryEntry(HttpRequestSpec(url = "https://admin:pw@192.168.1.1/api?x=1"), 200, 5))
        assertEquals("https://192.168.1.1/api?x=1", basic.single().spec.url)
    }
}
