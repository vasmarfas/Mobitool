package com.vasmarfas.card.core

import com.vasmarfas.card.ui.navigation.HomeRoute
import com.vasmarfas.card.ui.navigation.ToolRoute
import com.vasmarfas.card.ui.navigation.ToolsRoute
import com.vasmarfas.card.ui.navigation.UrlRoutes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {
    @Test
    fun decimals() {
        assertEquals("3.14", 3.14159.fmt(2))
        assertEquals("3", 3.0.fmt(2))
        assertEquals("3.10", 3.1.fmt(2, minFraction = 2))
        assertEquals("-0.5", (-0.5).fmt(1))
        assertEquals("1 234 567.5", 1234567.5.fmt(1, grouping = true))
        assertEquals("NaN", Double.NaN.fmt())
        assertEquals("92 000 000 000 000 000", 9.2e16.fmt(2, grouping = true))
        assertEquals("2.25e18", 2.25e18.fmt(2, grouping = true))
    }

    @Test
    fun grouping() {
        assertEquals("1 000", 1000L.fmtGrouped())
        assertEquals("-12 345 678", (-12345678L).fmtGrouped())
        assertEquals("999", 999.fmtGrouped())
    }

    @Test
    fun bytes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KiB", formatBytes(1024))
        assertEquals("1.5 MiB", formatBytes(1572864))
        assertEquals("1 kB", formatBytes(1000, binary = false))
        appLang = Lang.RU
        try {
            assertEquals("512 Б", formatBytes(512))
            assertEquals("1.5 МиБ", formatBytes(1572864))
            assertEquals("2.87 МБ", formatBytes(2_874_855, binary = false))
        } finally {
            appLang = Lang.EN
        }
    }

    @Test
    fun durations() {
        assertEquals("250 ms", formatDurationMs(250))
        assertEquals("1m 5s", formatDurationMs(65_000))
        assertEquals("2h 0m 1s", formatDurationMs(7_201_000))
    }

    @Test
    fun counts() {
        assertEquals("850", formatCount(850))
        assertEquals("8.4K", formatCount(8442))
        assertEquals("8K", formatCount(8020))
        assertEquals("10K", formatCount(9960))
        assertEquals("71K", formatCount(70826))
        assertEquals("1M", formatCount(999_600))
        assertEquals("1.2M", formatCount(1_234_567))
        appLang = Lang.RU
        try {
            assertEquals("8.4\u00A0тыс.", formatCount(8442))
            assertEquals("71\u00A0тыс.", formatCount(70826))
            assertEquals("1.2\u00A0млн", formatCount(1_234_567))
        } finally {
            appLang = Lang.EN
        }
        assertEquals(10_000, parseCount("10K+"))
        assertEquals(8_400, parseCount("8,4K"))
        assertEquals(1_200_000, parseCount("1.2M"))
        assertEquals(5, parseCount("5+"))
        assertNull(parseCount(""))
        assertNull(parseCount("many"))
    }

    @Test
    fun lenientDouble() {
        assertEquals(1.5, "1,5".toDoubleLenient())
        assertEquals(1000.0, "1 000".toDoubleLenient())
        assertEquals(250_000.0, "250,000".toDoubleLenient())
        assertEquals(0.125, "0,125".toDoubleLenient())
        assertEquals(-1_234_567.5, "-1,234,567.5".toDoubleLenient())
        assertEquals(1_234_567.89, "1\u00A0234\u202F567,89".toDoubleLenient())
        assertNull("abc".toDoubleLenient())
        appLang = Lang.RU
        try {
            assertEquals(250.0, "250,000".toDoubleLenient())
            assertEquals(1_234_567.89, "1\u00A0234\u00A0567,89".toDoubleLenient())
        } finally {
            appLang = Lang.EN
        }
    }
}

class TrTest {
    @Test
    fun resolvesByLanguage() {
        val t = Tr("Hello", "Привет")
        assertEquals("Hello", t[Lang.EN])
        assertEquals("Привет", t[Lang.RU])
        assertEquals("Same", Tr("Same")[Lang.RU])
    }

    @Test
    fun serializesAsObjectAndAcceptsString() {
        val json = Json
        assertEquals("""{"en":"A","ru":"Б"}""", json.encodeToString(Tr.serializer(), Tr("A", "Б")))
        val decoded = json.decodeFromString(Tr.serializer(), """{"en":"A","ru":"Б"}""")
        assertEquals("Б", decoded.ru)
        assertEquals("plain", json.decodeFromString(Tr.serializer(), "\"plain\"").ru)
    }

    @Test
    fun systemTag() {
        assertEquals(Lang.RU, Lang.fromSystemTag("ru-RU"))
        assertEquals(Lang.EN, Lang.fromSystemTag("de-DE"))
        assertEquals(Lang.EN, Lang.fromSystemTag(null))
    }
}

class UrlRoutesTest {
    @Test
    fun parsesFragments() {
        assertEquals(HomeRoute, UrlRoutes.parse("#home"))
        assertEquals(ToolsRoute(), UrlRoutes.parse("#tools"))
        assertEquals(ToolsRoute(), UrlRoutes.parse("#tools/"))
        assertEquals(ToolRoute("ping"), UrlRoutes.parse("#tools/ping"))
        assertEquals(ToolRoute("dns-lookup"), UrlRoutes.parse("tools/dns-lookup?x=1"))
        assertNull(UrlRoutes.parse(""))
        assertNull(UrlRoutes.parse("#unknown"))
    }

    @Test
    fun parsesCatalogCategory() {
        assertEquals(ToolsRoute("documents"), UrlRoutes.parse("#tools?category=documents"))
        assertEquals(ToolsRoute("popular"), UrlRoutes.parse("#tools/?x=1&category=popular"))
        assertEquals(ToolsRoute(), UrlRoutes.parse("#tools?category="))
        assertEquals(ToolsRoute(), UrlRoutes.parse("#tools//"))
    }

    @Test
    fun buildsFragments() {
        assertEquals("#tools/ping", UrlRoutes.fragmentForTool("ping"))
        assertEquals("#tools", UrlRoutes.fragmentForCatalog(null))
        assertEquals("#tools?category=network", UrlRoutes.fragmentForCatalog("network"))
    }
}
