package com.vasmarfas.card.data

import com.vasmarfas.card.core.KeyValueStore
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.appLang
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class MemoryStore(vararg entries: Pair<String, String>) : KeyValueStore {
    val values = mutableMapOf(*entries)
    var writes = 0

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        writes++
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class AppSettingsTest {
    // AppSettings takes the system language, the formatting tests expect English
    @AfterTest
    fun restoreLang() {
        appLang = Lang.EN
    }

    @Test
    fun mergedToolsMoveToTheirNewId() {
        val store = MemoryStore(
            "tools.favorites" to "color-palette,ping,color-converter",
            "tools.recent" to "regex-builder,regex-tester,ping",
        )
        val settings = AppSettings(store)
        assertEquals(listOf("color-converter", "ping"), settings.myTools)
        assertEquals(listOf("regex-tester", "ping"), settings.recent)
        assertEquals("color-converter,ping", store.values["tools.favorites"])
        assertEquals("regex-tester,ping", store.values["tools.recent"])
    }

    @Test
    fun currentIdsAreNotWrittenBack() {
        val store = MemoryStore("tools.favorites" to "ping,no-such-tool", "tools.recent" to "ping")
        assertEquals(listOf("ping", "no-such-tool"), AppSettings(store).myTools)
        assertEquals(0, store.writes)
    }

    @Test
    fun pinningTheNewIdOfAMergedToolKeepsOneEntry() {
        val settings = AppSettings(MemoryStore("tools.favorites" to "color-palette"))
        settings.pin("color-converter")
        assertEquals(listOf("color-converter"), settings.myTools)
        settings.unpin("color-converter")
        assertEquals(emptyList(), settings.myTools)
    }
}
