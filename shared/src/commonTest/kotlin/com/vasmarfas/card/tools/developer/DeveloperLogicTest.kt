package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlCodecTest {
    @Test
    fun encodesAndDecodes() {
        assertEquals("a%20b%2Fc", UrlCodec.encodeComponent("a b/c"))
        assertEquals("a+b", UrlCodec.encodeComponent("a b", spaceAsPlus = true))
        assertEquals("%D0%B4%D0%B0", UrlCodec.encodeComponent("да"))
        assertEquals("да", UrlCodec.decode("%D0%B4%D0%B0"))
        assertEquals("a b", UrlCodec.decode("a+b", plusAsSpace = true))
        assertNull(UrlCodec.decode("%zz"))
    }

    @Test
    fun parsesUrlParts() {
        val url = assertNotNull(UrlCodec.parse("https://user@example.com:8443/a/b?x=1&y=два#frag"))
        assertEquals("https", url.scheme)
        assertEquals("user", url.userInfo)
        assertEquals("example.com", url.host)
        assertEquals(8443, url.port)
        assertEquals(listOf("a", "b"), url.segments)
        assertEquals(listOf("x" to "1", "y" to "два"), url.params)
        assertEquals("frag", url.fragment)
        assertEquals(443, assertNotNull(UrlCodec.parse("https://example.com/")).defaultPort)
    }

    @Test
    fun hostWithPortIsNotAScheme() {
        val api = assertNotNull(UrlCodec.parse("example.com:8080/api"))
        assertEquals("example.com", api.host)
        assertEquals(8080, api.port)
        assertEquals("/api", api.path)
        assertEquals(3000, assertNotNull(UrlCodec.parse("localhost:3000")).port)
        assertEquals("192.168.1.1", assertNotNull(UrlCodec.parse("192.168.1.1:8080/admin")).host)
        assertEquals("tel", assertNotNull(UrlCodec.parse("tel:5551234")).scheme)
    }

    @Test
    fun barePercentIsEncoded() {
        assertEquals("50%25%20off", UrlCodec.encodeFull("50% off"))
        assertEquals("a%20b", UrlCodec.encodeFull("a%20b"))
        assertEquals("100%25", UrlCodec.encodeFull("100%"))
    }
}

class ChmodTest {
    @Test
    fun convertsBothWays() {
        assertEquals("755", Chmod.octal(0x1ED))
        assertEquals("rwxr-xr-x", Chmod.symbolic(0x1ED))
        assertEquals(0x1ED, Chmod.parseOctal("755"))
        assertEquals(0x1ED, Chmod.parseSymbolic("rwxr-xr-x"))
        assertEquals(0x1ED, Chmod.parseSymbolic("-rwxr-xr-x"))
        assertEquals("644", Chmod.octal(assertNotNull(Chmod.parseSymbolic("rw-r--r--"))))
        assertNull(Chmod.parseOctal("958"))
        assertNull(Chmod.parseSymbolic("rwx"))
    }

    @Test
    fun specialBits() {
        val mode = assertNotNull(Chmod.parseOctal("4755"))
        assertEquals("rwsr-xr-x", Chmod.symbolic(mode))
        assertEquals("4755", Chmod.octal(mode))
        assertEquals("1777", Chmod.octal(assertNotNull(Chmod.parseSymbolic("rwxrwxrwt"))))
    }
}

class CronTest {
    @Test
    fun parsesFields() {
        val expr = assertNotNull(Cron.parse("*/15 9-17 * * 1-5").getOrNull())
        assertEquals(setOf(0, 15, 30, 45), expr.minute.values)
        assertEquals((9..17).toSet(), expr.hour.values)
        assertEquals(setOf(1, 2, 3, 4, 5), expr.dayOfWeek.values)
        assertTrue(Cron.parse("* * *").isFailure)
        assertTrue(Cron.parse("99 * * * *").isFailure)
    }

    @Test
    fun expandsMacrosAndNames() {
        val expr = assertNotNull(Cron.parse("@daily").getOrNull())
        assertEquals(setOf(0), expr.minute.values)
        assertEquals(setOf(0), expr.hour.values)
        val named = assertNotNull(Cron.parse("0 0 * jan mon").getOrNull())
        assertEquals(setOf(1), named.month.values)
        assertEquals(setOf(1), named.dayOfWeek.values)
        assertEquals(setOf(0), assertNotNull(Cron.parse("0 0 * * 7").getOrNull()).dayOfWeek.values)
    }

    @Test
    fun nextRunsMoveForward() {
        val expr = assertNotNull(Cron.parse("0 9 * * *").getOrNull())
        val runs = Cron.nextRuns(expr, CronTime(2024, 3, 10, 10, 0), 3)
        assertEquals(3, runs.size)
        assertEquals("2024-03-11 09:00", runs[0].formatted())
        assertEquals("2024-03-13 09:00", runs[2].formatted())
    }

    @Test
    fun knownDayOfWeek() {
        assertEquals(1, Cron.dayOfWeek(2024, 3, 11))
        assertEquals(29, Cron.daysInMonth(2024, 2))
        assertEquals(28, Cron.daysInMonth(2023, 2))
    }

    @Test
    fun steppedDayFieldJoinsWithAnd() {
        val stepped = assertNotNull(Cron.parse("0 0 */2 * 1").getOrNull())
        assertTrue(Cron.describe(stepped, Lang.EN).endsWith("every 2nd day and on Monday"))
        val listed = assertNotNull(Cron.parse("0 0 1 * 1").getOrNull())
        assertTrue(Cron.describe(listed, Lang.EN).endsWith("on day-of-month 1 or on Monday"))
    }

    @Test
    fun reversedSteppedRangeIsAnError() {
        assertTrue(Cron.parse("50-10/5 * * * *").isFailure)
        assertEquals(setOf(10, 25, 40), assertNotNull(Cron.parse("10-50/15 * * * *").getOrNull()).minute.values)
    }
}

class CsvTest {
    @Test
    fun parsesQuotedFields() {
        val table = Csv.parse("name,note\nAnn,\"a, b\"\nBob,\"say \"\"hi\"\"\"", ',')
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Ann", "a, b"), table.rows[1])
        assertEquals(listOf("Bob", "say \"hi\""), table.rows[2])
    }

    @Test
    fun detectsDelimiter() {
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals('\t', Csv.detectDelimiter("a\tb\n1\t2"))
        assertEquals(',', Csv.detectDelimiter("a,b\n1,2"))
        assertEquals(';', Csv.detectDelimiter("1,5;2,5\n3,5;4,5\n"))
        assertEquals(',', Csv.detectDelimiter("name,note\nAnn,\"a; b\"\nBob,\"c; d\""))
    }

    @Test
    fun convertsToJsonAndBack() {
        val table = Csv.parse("name,age\nAnn,30", ',')
        val json = Csv.toJson(table, hasHeader = true)
        assertEquals(1, json.size)
        assertEquals("[{\"name\":\"Ann\",\"age\":30}]", JsonTools.minify(json))
        assertEquals("name,age\nAnn,30", Csv.fromJson(json, ','))
    }

    @Test
    fun keepsIdentifiersAsText() {
        val table = Csv.parse("account,phone,zip,share,x,big,price\n40817810099910004312,+79161234567,007,0.5,NaN,1e400,1.50", ',')
        val json = Csv.toJson(table, hasHeader = true)
        assertEquals(
            """[{"account":"40817810099910004312","phone":"+79161234567","zip":"007","share":0.5,"x":"NaN","big":"1e400","price":1.50}]""",
            JsonTools.minify(json),
        )
        assertTrue(JsonTools.format(json, 2).contains("\"price\": 1.50"))
    }

    @Test
    fun markdownTableHasSeparator() {
        val md = Csv.toMarkdown(Csv.parse("a,b\n1,2", ','), hasHeader = true)
        assertEquals(3, md.lines().size)
        assertTrue(md.lines()[1].contains("---"))
    }
}

class JsonToolsTest {
    @Test
    fun rejectsDeepNesting() {
        assertTrue(JsonTools.parse("[".repeat(1000) + "]".repeat(1000)).exceptionOrNull() is JsonTooDeepException)
        assertTrue(JsonTools.parse("{\"a\":".repeat(5000)).exceptionOrNull() is JsonTooDeepException)
        assertTrue(JsonTools.parse("[".repeat(JsonTools.MAX_DEPTH) + "]".repeat(JsonTools.MAX_DEPTH)).isSuccess)
        assertTrue(JsonTools.parse("{\"a\":\"\\\"" + "[".repeat(1000) + "\"}").isSuccess)
    }
}

class JwtTest {
    @Test
    fun decodesHeaderAndPayload() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjoxNTE2MjM5MDIyfQ.abc"
        val parts = assertNotNull(Jwt.decode(token).getOrNull())
        assertEquals("HS256", Jwt.claimText(assertNotNull(parts.header["alg"])))
        assertEquals("1234567890", Jwt.claimText(assertNotNull(parts.payload["sub"])))
        assertEquals(1516239022L, Jwt.epochSeconds(assertNotNull(parts.payload["exp"])))
        assertTrue(Jwt.decode("not.a").isFailure)
    }
}

class StringEscapesTest {
    @Test
    fun escapeRoundTrip() {
        val text = "line1\nline2\t\"quoted\" \\ back 'single' <tag> & дом"
        EscapeTarget.entries.forEach { target ->
            assertEquals(text, StringEscapes.unescape(StringEscapes.escape(text, target), target), target.title.english())
        }
        val controls = "\u0001\u0085\u000C $5 😀"
        listOf(EscapeTarget.JAVA, EscapeTarget.KOTLIN, EscapeTarget.C, EscapeTarget.JAVASCRIPT).forEach { target ->
            assertEquals(controls, StringEscapes.unescape(StringEscapes.escape(controls, target), target), target.title.english())
        }
    }

    @Test
    fun knownForms() {
        assertEquals("a\\nb", StringEscapes.escape("a\nb", EscapeTarget.JSON))
        assertEquals("&lt;a&gt;", StringEscapes.escape("<a>", EscapeTarget.HTML))
        assertEquals("'it'\\''s'", StringEscapes.escape("it's", EscapeTarget.SHELL))
        assertEquals("'it''s'", StringEscapes.escape("it's", EscapeTarget.SQL))
        assertEquals("\\u0414", StringEscapes.escape("Д", EscapeTarget.JAVA))
        assertEquals("\$5\\f", StringEscapes.escape("\$5\u000C", EscapeTarget.JAVA))
        assertEquals("\\\$5\\u000C", StringEscapes.escape("\$5\u000C", EscapeTarget.KOTLIN))
        assertEquals("\\001\\205\\u0414\\U0001F600", StringEscapes.escape("\u0001\u0085Д😀", EscapeTarget.C))
    }
}

class MarkdownTest {
    @Test
    fun blockKinds() {
        val blocks = Markdown.blocks("# Title\n\ntext\n\n- item\n\n> quote\n\n```kt\ncode\n```\n\n---")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.ListItem)
        assertTrue(blocks[3] is MdBlock.Quote)
        val code = assertNotNull(blocks[4] as? MdBlock.Code)
        assertEquals("kt", code.language)
        assertEquals("code", code.code)
        assertTrue(blocks[5] is MdBlock.Rule)
    }

    @Test
    fun inlineSpans() {
        val spans = Markdown.spans("a **b** *c* `d` [e](https://x.dev)")
        assertTrue(spans.any { it is MdSpan.Bold && it.text == "b" })
        assertTrue(spans.any { it is MdSpan.Italic && it.text == "c" })
        assertTrue(spans.any { it is MdSpan.Code && it.text == "d" })
        assertTrue(spans.any { it is MdSpan.Link && it.url == "https://x.dev" && it.text == "e" })
    }
}

class UuidTest {
    @Test
    fun v4HasVersionAndVariant() {
        val info = assertNotNull(Uuids.parse(Uuids.v4(ByteArray(16) { 0x11.toByte() })))
        assertEquals(4, info.version)
        assertEquals("RFC 4122 / RFC 9562", info.variant)
        assertEquals(36, info.canonical.length)
    }

    @Test
    fun v7CarriesTimestamp() {
        val ms = 1_700_000_000_000L
        val info = assertNotNull(Uuids.parse(Uuids.v7(ms, ByteArray(16))))
        assertEquals(7, info.version)
        assertEquals(ms, info.timestampMs)
    }

    @Test
    fun parsesFormats() {
        val plain = assertNotNull(Uuids.parse("{01234567-89AB-4CDE-8F01-234567890ABC}"))
        assertEquals("01234567-89ab-4cde-8f01-234567890abc", plain.canonical)
        assertTrue(assertNotNull(Uuids.parse("00000000-0000-0000-0000-000000000000")).isNil)
        assertNull(Uuids.parse("12345"))
    }
}

class RegexTesterTest {
    @Test
    fun findsMatchesWithGroups() {
        val result = RegexTester.run("(\\w+)@(\\w+)", "a@b c@d", ignoreCase = false, multiline = false, dotAll = false, replacement = "$2")
        assertEquals(2, result.matches.size)
        assertEquals(listOf("a", "b"), result.matches[0].groups)
        assertEquals(0, result.matches[0].start)
        assertEquals("b d", result.replaced)
        assertNull(result.error)
    }

    @Test
    fun reportsInvalidPattern() {
        assertNotNull(RegexTester.run("(unclosed", "x", false, false, false, null).error)
    }
}

class Base64ToolsTest {
    @Test
    fun encodesUrlSafeWithoutPadding() {
        val bytes = byteArrayOf(-5, -16, 0)
        assertEquals("+/AA", Base64Tools.encode(bytes, urlSafe = false, padding = true))
        assertEquals("-_AA", Base64Tools.encode(bytes, urlSafe = true, padding = false))
    }

    @Test
    fun decodesBothAlphabets() {
        assertEquals("привет", Base64Tools.decode("0L/RgNC40LLQtdGC")?.decodeToString())
        assertEquals(3, assertNotNull(Base64Tools.decode("-_AA")).size)
        assertNull(Base64Tools.decode("!!!"))
    }
}

class MimeTypesTest {
    @Test
    fun searchReadsBothLanguages() {
        assertEquals(listOf("image/png"), MimeTypes.search(".png").map { it.type })
        assertTrue(MimeTypes.search("archive").any { it.type == "application/zip" })
        assertTrue(MimeTypes.search("архив").any { it.type == "application/zip" })
    }

    @Test
    fun everyDescriptionIsTranslated() {
        MimeTypes.all.forEach { assertNotNull(it.description.russian(), it.type) }
    }
}
