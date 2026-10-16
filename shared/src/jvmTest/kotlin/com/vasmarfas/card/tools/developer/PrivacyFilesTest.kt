package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyFilesTest {
    private val languages = listOf("en", "ru")

    private fun read(document: PolicyDocument, lang: String): String =
        runBlocking { Res.readBytes("files/privacy/${document.key}-$lang.md").decodeToString() }

    private val knownFlags = setOf("app", "site", "both", "nothing", "sdk", "children", "in-app-deletion", "deletion-url", "country") +
        PolicyData.entries.map { it.key } + PolicySdk.entries.map { "sdk:${it.key}" }

    private val subsets: List<Set<PolicyData>> = (0 until (1 shl PolicyData.entries.size)).map { mask ->
        PolicyData.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }

    private fun input(product: PolicyProduct, data: Set<PolicyData>, children: Boolean) = PolicyInput(
        app = "Notes",
        developer = "Jane Doe",
        contact = "jane@example.com",
        country = if (children) "Germany" else "",
        product = product,
        data = data,
        sdks = PolicySdk.entries.toSet(),
        children = children,
        inAppDeletion = children,
        deletionUrl = if (children) "" else "https://example.com/delete",
        date = "2026-10-16",
    )

    @Test
    fun languagesHaveTheSameBlocks() {
        PolicyDocument.entries.forEach { document ->
            val (en, ru) = languages.map { PolicyTemplates.blocks(read(document, it)) }
            assertEquals(en.map { it.condition }, ru.map { it.condition }, document.key)
            assertEquals(PolicyTemplates.defaults(read(document, "en")).keys, PolicyTemplates.defaults(read(document, "ru")).keys, document.key)
        }
    }

    @Test
    fun conditionsAndPlaceholdersAreKnown() {
        val values = PolicyTemplates.values("", PolicyInput()).keys
        PolicyDocument.entries.forEach { document ->
            languages.forEach { lang ->
                val template = read(document, lang)
                PolicyTemplates.blocks(template).forEach { block ->
                    val condition = block.condition
                    if (condition != PolicyTemplates.ALWAYS && condition != PolicyTemplates.PLACEHOLDERS) {
                        assertTrue(condition.startsWith("if "), "$document $lang: $condition")
                        condition.removePrefix("if ").split(' ').flatMap { it.split('|') }.forEach { term ->
                            assertTrue(term.removePrefix("!") in knownFlags, "$document $lang: unknown flag $term")
                        }
                    }
                    assertTrue(PolicyTemplates.placeholders(block.text).all { it in values }, "$document $lang: ${block.text.take(60)}")
                }
                assertTrue(PolicyTemplates.defaults(template).keys.containsAll(PolicyTemplates.placeholders(template)), "$document $lang")
            }
        }
    }

    @Test
    fun everySdkHasAParagraphWithItsPolicy() {
        languages.forEach { lang ->
            val blocks = PolicyTemplates.blocks(read(PolicyDocument.PRIVACY, lang))
            PolicySdk.entries.forEach { sdk ->
                val block = blocks.singleOrNull { it.condition == "if sdk:${sdk.key}" }
                assertTrue(block != null && "](https://" in block.text, "$lang: ${sdk.key}")
            }
        }
    }

    @Test
    fun everyAnswerCombinationRendersCleanly() {
        PolicyDocument.entries.forEach { document ->
            languages.forEach { lang ->
                val template = read(document, lang)
                PolicyProduct.entries.forEach { product ->
                    subsets.forEach { data ->
                        listOf(false, true).forEach { children ->
                            val input = input(product, data, children)
                            val markdown = PolicyTemplates.markdown(template, input)
                            val label = "$document $lang $product $data $children"
                            assertTrue(PolicyTemplates.placeholders(markdown).isEmpty(), label)
                            assertFalse("<!--" in markdown, label)
                            val lines = markdown.lines().filter { it.isNotBlank() }
                            lines.zipWithNext().forEach { (line, next) ->
                                if (line.startsWith("#")) {
                                    assertFalse(next.startsWith("#") && next.takeWhile { it == '#' }.length <= line.takeWhile { it == '#' }.length, "$label: empty section $line")
                                }
                            }
                            assertFalse(lines.last().startsWith("#"), label)
                            assertEquals(1, lines.count { it.startsWith("# ") }, label)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun htmlHasNoMarkdownLeft() {
        val singles = PolicyData.entries.map { setOf(it) } + listOf(emptySet(), PolicyData.entries.toSet())
        PolicyDocument.entries.forEach { document ->
            languages.forEach { lang ->
                val template = read(document, lang)
                PolicyProduct.entries.forEach { product ->
                    singles.forEach { data ->
                        val html = PolicyTemplates.html(template, input(product, data, data.size == 1), lang)
                        val label = "$document $lang $product $data"
                        listOf("**", "](", "`", "<!--", "\\").forEach { leftover -> assertFalse(leftover in html, "$label: $leftover") }
                        assertTrue(PolicyTemplates.placeholders(html).isEmpty(), label)
                        assertTrue("<html lang=\"$lang\">" in html, label)
                    }
                }
            }
        }
    }

    @Test
    fun nothingCollectedSaysSoAndNamesNoService() {
        val input = input(PolicyProduct.APP, emptySet(), false)
        val en = PolicyTemplates.markdown(read(PolicyDocument.PRIVACY, "en"), input)
        assertTrue("does not collect personal information" in en)
        PolicySdk.entries.forEach { assertFalse("sdk:${it.key}" in PolicyTemplates.flags(input)) }
        assertFalse("Google" in en)
    }
}
