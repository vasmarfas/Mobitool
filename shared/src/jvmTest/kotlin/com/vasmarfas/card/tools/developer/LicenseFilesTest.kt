package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicenseFilesTest {
    private fun read(path: String): String = runBlocking { Res.readBytes(path).decodeToString() }

    // the picker ranks by text length, so the stored counts have to follow the bundled files
    @Test
    fun everyTextIsBundledAndCounted() {
        Licenses.all.forEach { license ->
            val text = read("files/licenses/${license.file}")
            assertTrue(text.isNotBlank(), license.id)
            if (license.family != LicenseFamily.PROPRIETARY) {
                assertEquals(Regex("\\S+").findAll(text).count(), license.words, license.id)
            }
        }
    }

    @Test
    fun everyFullNoticeIsBundled() {
        Licenses.all.forEach { license ->
            listOf(true, false).mapNotNull { LicenseTexts.headerFile(license, it) }.forEach { path ->
                val notice = read(path)
                assertTrue("{year}" in notice && "{holder}" in notice, path)
            }
        }
    }

    @Test
    fun everyTextIsRecognizedAsItself() {
        val texts = Licenses.all.filter { it.family != LicenseFamily.PROPRIETARY }.associateWith { read("files/licenses/${it.file}") }
        val prints = texts.mapValues { (license, text) -> LicenseTexts.fingerprint(LicenseTexts.comparable(license, text)) }
        texts.forEach { (license, text) ->
            val guesses = LicenseTexts.identify(LicenseTexts.comparable(license, text), prints)
            assertEquals(license.id, guesses.first().license.id)
            assertTrue(guesses.first().score - guesses[1].score > 0.01, "${license.id} vs ${guesses[1].license.id}")
        }
        val mit = Licenses.byId("MIT")!!
        val filled = LicenseTexts.fill(mit, texts.getValue(mit), "Jane Doe", "2026", "").replace("\n", " ")
        assertEquals("MIT", LicenseTexts.identify(filled, prints).first().license.id)
    }

    @Test
    fun templatesHaveThePlaceholdersFillReplaces() {
        val placeholders = mapOf(
            "MIT" to "<copyright holders>",
            "BSD-2-Clause" to "<owner>",
            "BSD-3-Clause" to "<owner>",
            "ISC" to "<owner>",
            "MIT-0" to "<COPYRIGHT HOLDER>",
            "0BSD" to "YEAR by AUTHOR EMAIL",
            "FSL-1.1-MIT" to "\${licensor name}",
            "LicenseRef-Proprietary" to "{holder}",
        )
        placeholders.forEach { (id, placeholder) ->
            val license = Licenses.byId(id)!!
            val template = read("files/licenses/${license.file}")
            assertTrue(placeholder in template, id)
            assertTrue(placeholder !in LicenseTexts.fill(license, template, "Jane Doe", "2026", ""), id)
        }
    }
}
