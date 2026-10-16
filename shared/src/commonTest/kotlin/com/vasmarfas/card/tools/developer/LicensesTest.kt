package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicensesTest {
    private fun top(needs: LicenseNeeds): String = LicensePicker.pick(needs).first().license.id

    private fun ids(needs: LicenseNeeds): List<String> = LicensePicker.pick(needs).map { it.license.id }

    private fun license(id: String) = assertNotNull(Licenses.byId(id), id)

    private fun verdict(dep: String, project: String, usage: LicenseUsage = LicenseUsage.DISTRIBUTE, projectOrLater: Boolean = false): Verdict {
        val expr = assertNotNull(LicenseCompat.parse(dep), dep)
        return LicenseCompat.evaluate(expr, license(project), projectOrLater, usage).verdict
    }

    @Test
    fun catalogIsConsistent() {
        val ids = Licenses.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Licenses.all.forEach { license ->
            assertTrue(license.popularity in 1..5, license.id)
            assertTrue(license.subjects.isNotEmpty(), license.id)
            assertTrue(license.url.isNotEmpty() || license.family == LicenseFamily.PROPRIETARY, license.id)
            if (LicenseFlag.MODIFY !in license) assertEquals(Copyleft.NONE, license.copyleft, license.id)
        }
        LicenseFamily.entries.forEach { family -> assertTrue(Licenses.all.any { it.family == family }, family.name) }
    }

    @Test
    fun everyAnswerCombinationGivesSomething() {
        LicenseSubject.entries.forEach { subject ->
            LicensePicker.commercialOptions(subject).forEach { commercial ->
                val copylefts = LicensePicker.copyleftOptions(subject, commercial).ifEmpty { listOf(PickCopyleft.NONE) }
                copylefts.forEach { copyleft ->
                    val needs = LicenseNeeds(subject, commercial, copyleft)
                    assertTrue(LicensePicker.pick(needs).isNotEmpty(), needs.toString())
                }
            }
        }
    }

    @Test
    fun defaultsPickTheUsualSuspects() {
        assertEquals(listOf("MIT", "Apache-2.0"), ids(LicenseNeeds()).take(2))
        assertEquals("Apache-2.0", top(LicenseNeeds(patent = true)))
        assertEquals("GPL-3.0", top(LicenseNeeds(copyleft = PickCopyleft.STRONG)))
        assertEquals("AGPL-3.0", top(LicenseNeeds(copyleft = PickCopyleft.NETWORK)))
        assertEquals("MPL-2.0", top(LicenseNeeds(copyleft = PickCopyleft.WEAK)))
        assertTrue(top(LicenseNeeds(credit = false)) in setOf("Unlicense", "0BSD", "MIT-0"))
    }

    @Test
    fun contentGetsCreativeCommons() {
        val content = LicenseNeeds(subject = LicenseSubject.CONTENT)
        assertEquals("CC-BY-4.0", top(content))
        assertEquals("CC-BY-SA-4.0", top(content.copy(copyleft = PickCopyleft.STRONG)))
        assertEquals("CC-BY-NC-4.0", top(content.copy(commercial = PickCommercial.NONCOMMERCIAL)))
        assertEquals("CC-BY-NC-ND-4.0", top(content.copy(commercial = PickCommercial.NONCOMMERCIAL, changes = false)))
        assertEquals("CC-BY-ND-4.0", top(content.copy(changes = false)))
        assertEquals("CC0-1.0", top(content.copy(credit = false)))
        assertEquals("OFL-1.1", top(LicenseNeeds(subject = LicenseSubject.FONT, copyleft = PickCopyleft.STRONG)))
        assertEquals("CERN-OHL-S-2.0", top(LicenseNeeds(subject = LicenseSubject.HARDWARE, copyleft = PickCopyleft.STRONG)))
    }

    @Test
    fun restrictedAnswersStayRestricted() {
        assertEquals("PolyForm-Small-Business-1.0.0", top(LicenseNeeds(commercial = PickCommercial.SMALL)))
        assertEquals("PolyForm-Noncommercial-1.0.0", top(LicenseNeeds(commercial = PickCommercial.NONCOMMERCIAL)))
        assertEquals("LicenseRef-PolyForm-Strict-1.0.0", top(LicenseNeeds(commercial = PickCommercial.NONCOMMERCIAL, changes = false)))
        assertEquals("LicenseRef-Proprietary", top(LicenseNeeds(commercial = PickCommercial.CONTRACT)))
        val delayed = ids(LicenseNeeds(commercial = PickCommercial.LIMITED, delayed = true))
        assertEquals(setOf("BUSL-1.1", "FSL-1.1-MIT"), delayed.toSet())
        assertTrue(ids(LicenseNeeds(commercial = PickCommercial.ANY, gpl = true)).none { it == "EPL-2.0" || it == "GPL-2.0" })
    }

    @Test
    fun resolvesSpdxIdsAndCommonNames() {
        assertEquals("MIT", LicenseCompat.resolve("MIT License")?.license?.id)
        assertEquals("Apache-2.0", LicenseCompat.resolve("Apache 2.0")?.license?.id)
        assertEquals("BSD-2-Clause", LicenseCompat.resolve("Simplified BSD")?.license?.id)
        val later = assertNotNull(LicenseCompat.resolve("GPL-2.0-or-later"))
        assertEquals("GPL-2.0", later.license.id)
        assertTrue(later.orLater)
        assertTrue(assertNotNull(LicenseCompat.resolve("GPLv3+")).orLater)
        assertEquals(false, LicenseCompat.resolve("GPL-3.0-only")?.orLater)
        assertEquals("CC-BY-SA-4.0", LicenseCompat.resolve("CC BY-SA 4.0")?.license?.id)
        assertEquals("LicenseRef-PolyForm-Strict-1.0.0", LicenseCompat.resolve("LicenseRef-PolyForm-Strict-1.0.0")?.license?.id)
        assertEquals("LicenseRef-Proprietary", LicenseCompat.resolve("UNLICENSED")?.license?.id)
        assertEquals("Unlicense", LicenseCompat.resolve("The Unlicense")?.license?.id)
        assertNull(LicenseCompat.resolve("Totally Made Up"))
    }

    @Test
    fun parsesExpressions() {
        val or = assertIs<SpdxExpr.Or>(LicenseCompat.parse("MIT OR Apache-2.0"))
        assertIs<SpdxExpr.Leaf>(or.left)
        assertIs<SpdxExpr.And>(LicenseCompat.parse("(MIT AND BSD-3-Clause)"))
        assertIs<SpdxExpr.Or>(LicenseCompat.parse("MIT/Apache-2.0"))
        val with = assertIs<SpdxExpr.Leaf>(LicenseCompat.parse("GPL-2.0-only WITH Classpath-exception-2.0"))
        assertEquals(LicenseException.CLASSPATH, with.ref?.exception)
        val spaced = assertIs<SpdxExpr.Leaf>(LicenseCompat.parse("Apache License 2.0"))
        assertEquals("Apache-2.0", spaced.ref?.license?.id)
        assertNull(LicenseCompat.parse("   "))
    }

    @Test
    fun permissiveCodeGoesAnywhereButApacheIntoGpl2() {
        assertEquals(Verdict.YES, verdict("MIT", "GPL-2.0"))
        assertEquals(Verdict.YES, verdict("BSD-3-Clause", "LicenseRef-Proprietary"))
        assertEquals(Verdict.YES, verdict("Apache-2.0", "GPL-3.0"))
        assertEquals(Verdict.NO, verdict("Apache-2.0", "GPL-2.0"))
        assertEquals(Verdict.YES, verdict("Apache-2.0", "GPL-2.0", projectOrLater = true))
        assertEquals(Verdict.YES, verdict("Apache-2.0 WITH LLVM-exception", "GPL-2.0"))
    }

    @Test
    fun gplVersionsMatter() {
        assertEquals(Verdict.NO, verdict("GPL-2.0-only", "GPL-3.0"))
        assertEquals(Verdict.YES, verdict("GPL-2.0-or-later", "GPL-3.0"))
        assertEquals(Verdict.YES, verdict("GPL-3.0-only", "AGPL-3.0"))
        assertEquals(Verdict.NO, verdict("GPL-3.0-only", "GPL-2.0"))
        assertEquals(Verdict.YES, verdict("LGPL-2.1-only", "GPL-3.0"))
        assertEquals(Verdict.NO, verdict("LGPL-3.0-only", "GPL-2.0"))
        assertEquals(Verdict.CONDITIONS, verdict("AGPL-3.0-only", "GPL-3.0"))
        assertEquals(Verdict.NO, verdict("AGPL-3.0-only", "GPL-2.0"))
    }

    @Test
    fun copyleftInClosedProjects() {
        assertEquals(Verdict.NO, verdict("GPL-3.0-only", "MIT"))
        assertEquals(Verdict.YES, verdict("GPL-3.0-only", "LicenseRef-Proprietary", LicenseUsage.NETWORK))
        assertEquals(Verdict.NO, verdict("AGPL-3.0-only", "LicenseRef-Proprietary", LicenseUsage.NETWORK))
        assertEquals(Verdict.YES, verdict("AGPL-3.0-only", "LicenseRef-Proprietary", LicenseUsage.INTERNAL))
        assertEquals(Verdict.CONDITIONS, verdict("LGPL-2.1-or-later", "LicenseRef-Proprietary"))
        assertEquals(Verdict.CONDITIONS, verdict("MPL-2.0", "Apache-2.0"))
        assertEquals(Verdict.YES, verdict("MPL-2.0", "GPL-3.0"))
        assertEquals(Verdict.CONDITIONS, verdict("EPL-2.0", "GPL-3.0"))
        assertEquals(Verdict.CONDITIONS, verdict("GPL-2.0-only WITH Classpath-exception-2.0", "LicenseRef-Proprietary"))
        assertEquals(Verdict.NO, verdict("SSPL-1.0", "AGPL-3.0"))
        assertEquals(Verdict.YES, verdict("EUPL-1.2", "GPL-3.0"))
        assertEquals(Verdict.NO, verdict("EUPL-1.2", "MIT", LicenseUsage.NETWORK))
    }

    @Test
    fun choiceAndCombination() {
        assertEquals(Verdict.YES, verdict("MIT OR GPL-3.0-only", "LicenseRef-Proprietary"))
        assertEquals(Verdict.NO, verdict("MIT AND GPL-3.0-only", "LicenseRef-Proprietary"))
        assertEquals(Verdict.UNKNOWN, verdict("Mystery-1.0", "MIT"))
        assertEquals(Verdict.YES, verdict("Mystery-1.0 OR MIT", "MIT"))
    }

    @Test
    fun contentAndSourceAvailable() {
        assertEquals(Verdict.YES, verdict("CC-BY-SA-4.0", "GPL-3.0"))
        assertEquals(Verdict.CONDITIONS, verdict("CC-BY-SA-4.0", "MIT"))
        assertEquals(Verdict.YES, verdict("CC-BY-4.0", "LicenseRef-Proprietary"))
        assertEquals(Verdict.CONDITIONS, verdict("OFL-1.1", "LicenseRef-Proprietary"))
        assertEquals(Verdict.CONDITIONS, verdict("CC-BY-ND-4.0", "CC-BY-4.0"))
        assertEquals(Verdict.NO, verdict("CC-BY-NC-4.0", "MIT"))
        assertEquals(Verdict.CONDITIONS, verdict("CC-BY-NC-4.0", "LicenseRef-Proprietary"))
        assertEquals(Verdict.NO, verdict("CC-BY-NC-SA-4.0", "CC-BY-NC-4.0"))
        assertEquals(Verdict.NO, verdict("BUSL-1.1", "Apache-2.0"))
        assertEquals(Verdict.CONDITIONS, verdict("BUSL-1.1", "LicenseRef-Proprietary"))
        assertEquals(Verdict.NO, verdict("LicenseRef-PolyForm-Strict-1.0.0", "LicenseRef-Proprietary"))
        assertEquals(Verdict.NO, verdict("UNLICENSED", "MIT"))
    }

    @Test
    fun readsLinesFromReports() {
        val lines = LicenseCompat.checkList("react: MIT\nleft-pad\tWTFPL\n\nGPL-3.0-only", license("MIT"), false, LicenseUsage.DISTRIBUTE)
        assertEquals(listOf("react", "left-pad", "GPL-3.0-only"), lines.map { it.label })
        assertEquals(listOf(Verdict.YES, Verdict.UNKNOWN, Verdict.NO), lines.map { it.result.verdict })
    }

    @Test
    fun fillsTemplates() {
        assertEquals("Copyright (c) 2026 Jane Doe", LicenseTexts.fill(license("MIT"), "Copyright (c) <year> <copyright holders>", "Jane Doe", "2026", "").trim())
        assertEquals("text <year>", LicenseTexts.fill(license("MIT"), "text <year>", " ", "2026", ""))
        assertTrue(LicenseTexts.fill(license("LicenseRef-PolyForm-Strict-1.0.0"), "terms", "Jane", "2026", "").startsWith("Required Notice: Copyright 2026 Jane"))
        val busl = LicenseTexts.fill(license("BUSL-1.1"), "Terms", "Jane", "2026", "Widget")
        assertTrue("Change Date:          2030-01-01" in busl, busl)
        assertTrue("Licensed Work:        Widget" in busl, busl)
        assertEquals("GPL text", LicenseTexts.fill(license("GPL-3.0"), "GPL text", "Jane", "2026", ""))
    }

    @Test
    fun buildsHeadersBadgesAndManifests() {
        val short = LicenseTexts.header("MIT", "Jane", "2026", "", null)
        assertEquals("// SPDX-FileCopyrightText: 2026 Jane\n// SPDX-License-Identifier: MIT", LicenseTexts.comment(short, CommentStyle.SLASHES))
        assertEquals("/*\n * a\n *\n * b\n */", LicenseTexts.comment(listOf("a", "", "b"), CommentStyle.BLOCK))
        val full = LicenseTexts.header("GPL-3.0-or-later", "Jane", "2026", "", "{project}\nCopyright (C) {year}  {holder}\n\nbody\n")
        assertEquals(listOf("Copyright (C) 2026  Jane", "", "body", "", "SPDX-License-Identifier: GPL-3.0-or-later"), full)
        assertEquals("GPL-3.0-or-later", license("GPL-3.0").spdx(true))
        assertEquals("LGPL-2.1-only", license("LGPL-2.1").spdx(false))
        assertEquals("MIT", license("MIT").spdx(true))
        assertTrue("license-GPL--3.0--or--later-blue" in LicenseTexts.badge("GPL-3.0-or-later", "https://example.org"))
        val npm = LicenseTexts.manifests(license("LicenseRef-Proprietary"), "LicenseRef-Proprietary").first { it.first == "package.json" }.second
        assertEquals("\"license\": \"UNLICENSED\"", npm)
        val polyform = LicenseTexts.manifests(license("LicenseRef-PolyForm-Shield-1.0.0"), "LicenseRef-PolyForm-Shield-1.0.0").first { it.first == "Cargo.toml" }.second
        assertEquals("license-file = \"LICENSE\"", polyform)
        assertEquals("files/licenses/headers/AGPL-3.0-only.txt", LicenseTexts.headerFile(license("AGPL-3.0"), false))
        assertNull(LicenseTexts.headerFile(license("MIT"), true))
    }

    @Test
    fun splitsTextsIntoParagraphs() {
        val wrapped = "Title Line\n\nFirst line of a paragraph that was wrapped\nat seventy columns by the author.\n  (a) an item\n  (b) another"
        val blocks = LicenseTexts.blocks(wrapped, markdown = false)
        assertEquals(4, blocks.size)
        assertEquals("First line of a paragraph that was wrapped at seventy columns by the author.", (blocks[1] as TextBlock.Paragraph).text)
        assertIs<TextBlock.Heading>(LicenseTexts.blocks("TERMS AND CONDITIONS", markdown = false).single())
        val md = LicenseTexts.blocks("# PolyForm\n\n## Acceptance\n\nSee [Notices](#notices) and ***this***.\n\n> Required Notice: x", markdown = true)
        assertEquals(listOf("PolyForm", "Acceptance"), md.filterIsInstance<TextBlock.Heading>().map { it.text })
        assertEquals("See Notices and this.", (md[2] as TextBlock.Paragraph).text)
        assertEquals(1, (md[3] as TextBlock.Paragraph).indent)
    }

    @Test
    fun attributionLinksTitleAndLicense() {
        val parts = LicenseTexts.attribution(Attribution("Sunset", "Ann", "https://example.org/a?b=1&c=2", license("CC-BY-4.0")))
        assertEquals(listOf("Sunset", "Ann", "CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)"), parts[0])
        assertEquals("<a href=\"https://example.org/a?b=1&amp;c=2\">Sunset</a>", parts[1][0])
        assertEquals("[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)", parts[2][2])
    }
}
