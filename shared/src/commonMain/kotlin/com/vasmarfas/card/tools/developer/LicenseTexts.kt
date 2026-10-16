package com.vasmarfas.card.tools.developer

enum class CommentStyle(val label: String) {
    SLASHES("//"),
    HASH("#"),
    BLOCK("/* */"),
    HTML("<!-- -->"),
    DASHES("--"),
    SEMICOLON(";"),
}

sealed class TextBlock {
    class Heading(val text: String) : TextBlock()
    class Paragraph(val text: String, val indent: Int) : TextBlock()
}

class Attribution(val title: String, val author: String, val source: String, val license: License)

class LicenseGuess(val license: License, val score: Double)

object LicenseTexts {
    private val listItem = Regex("^\\s*(\\(?[0-9a-zA-Z]{1,4}[.)]|[-*•–])\\s")

    fun fill(license: License, template: String, holder: String, year: String, project: String): String {
        val who = holder.trim()
        val name = project.trim()
        if (who.isEmpty()) return template
        return when (license.id) {
            "MIT" -> template.replace("<year>", year).replace("<copyright holders>", who)
            "BSD-2-Clause", "BSD-3-Clause", "ISC" -> template.replace("<year>", year).replace("<owner>", who)
            "MIT-0" -> template.replace("<YEAR>", year).replace("<COPYRIGHT HOLDER>", who)
            "0BSD" -> template.replace("YEAR by AUTHOR EMAIL", "$year by $who")
            "FSL-1.1-MIT" -> template.replace("\${year}", year).replace("\${licensor name}", who)
            "Zlib" -> "Copyright (c) $year $who\n\n$template"
            "LicenseRef-Proprietary" -> template.replace("{year}", year).replace("{holder}", who)
            "BUSL-1.1" -> busl(who, year, name.ifEmpty { who }) + template
            else -> if ("PolyForm-" in license.id) "Required Notice: Copyright $year $who\n\n$template" else template
        }
    }

    private fun busl(holder: String, year: String, project: String): String {
        val change = (year.toIntOrNull() ?: 2026) + 4
        return """
            |Business Source License 1.1
            |
            |Parameters
            |
            |Licensor:             $holder
            |Licensed Work:        $project
            |                      The Licensed Work is (c) $year $holder
            |Additional Use Grant: None
            |Change Date:          $change-01-01
            |Change License:       Apache License, Version 2.0
            |
            |Notice
            |
            |The Business Source License (this document, or the "License") is not an Open
            |Source license. However, the Licensed Work will eventually be made available
            |under an Open Source License, as stated in this License.
            |
            |-----------------------------------------------------------------------------
            |
            |
        """.trimMargin()
    }

    fun headerFile(license: License, orLater: Boolean): String? = when {
        license.versioned && license.id != "GFDL-1.3" -> "files/licenses/headers/${license.spdx(orLater)}.txt"
        license.id in setOf("Apache-2.0", "MPL-2.0", "EPL-2.0") -> "files/licenses/headers/${license.id}.txt"
        else -> null
    }

    fun header(spdx: String, holder: String, year: String, project: String, notice: String?): List<String> {
        val who = holder.trim().ifEmpty { "<copyright holder>" }
        if (notice == null) return listOf("SPDX-FileCopyrightText: $year $who", "SPDX-License-Identifier: $spdx")
        val filled = notice.trimEnd().lines()
            .filter { project.isNotBlank() || "{project}" !in it }
            .map { it.replace("{project}", project.trim()).replace("{year}", year).replace("{holder}", who) }
        return filled + "" + "SPDX-License-Identifier: $spdx"
    }

    fun comment(lines: List<String>, style: CommentStyle): String = when (style) {
        CommentStyle.BLOCK -> (listOf("/*") + lines.map { if (it.isEmpty()) " *" else " * $it" } + " */").joinToString("\n")
        CommentStyle.HTML -> (listOf("<!--") + lines.map { if (it.isEmpty()) "" else "  $it" } + "-->").joinToString("\n")
        else -> lines.joinToString("\n") { if (it.isEmpty()) style.label else "${style.label} $it" }
    }

    fun badge(spdx: String, url: String): String {
        val escaped = spdx.replace("-", "--").replace("_", "__").replace(" ", "_")
        val image = "https://img.shields.io/badge/license-$escaped-blue.svg"
        return if (url.isEmpty()) "![License: $spdx]($image)" else "[![License: $spdx]($image)]($url)"
    }

    fun manifests(license: License, spdx: String): List<Pair<String, String>> {
        val proprietary = license.family == LicenseFamily.PROPRIETARY
        val listed = license.inSpdxList && !proprietary
        val npm = when {
            proprietary -> "UNLICENSED"
            listed -> spdx
            else -> "SEE LICENSE IN LICENSE"
        }
        return listOf(
            "package.json" to "\"license\": \"$npm\"",
            "pyproject.toml" to "license = \"$spdx\"\nlicense-files = [\"LICENSE\"]",
            "Cargo.toml" to if (listed) "license = \"$spdx\"" else "license-file = \"LICENSE\"",
            "build.gradle.kts" to "licenses {\n    license {\n        name = \"${license.name}\"\n        url = \"${license.url}\"\n    }\n}",
            ".csproj" to if (listed) "<PackageLicenseExpression>$spdx</PackageLicenseExpression>" else "<PackageLicenseFile>LICENSE</PackageLicenseFile>",
            "composer.json" to "\"license\": \"${if (proprietary) "proprietary" else spdx}\"",
            ".gemspec" to "spec.license = \"$spdx\"",
        )
    }

    fun blocks(text: String, markdown: Boolean): List<TextBlock> =
        if (markdown) markdownBlocks(text) else plainBlocks(text)

    private fun markdownBlocks(text: String): List<TextBlock> = text.split(Regex("\n\\s*\n")).mapNotNull { chunk ->
        val raw = chunk.trim()
        val clean = raw.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").replace(Regex("\\*{2,3}"), "").replace("<", "").replace(">", "")
        when {
            raw.isEmpty() -> null
            raw.startsWith("#") -> TextBlock.Heading(clean.trimStart('#', ' '))
            raw.startsWith(">") -> TextBlock.Paragraph(clean.lines().joinToString(" ") { it.trim() }, 1)
            else -> TextBlock.Paragraph(clean.lines().joinToString(" ") { it.trim() }, 0)
        }
    }

    private fun plainBlocks(text: String): List<TextBlock> {
        val lines = text.lines()
        val wrapped = (lines.maxOfOrNull { it.length } ?: 0) < 140
        val blocks = mutableListOf<TextBlock>()
        var current: StringBuilder? = null
        var indent = 0
        fun flush() {
            current?.let { blocks += block(it.toString(), indent) }
            current = null
        }
        lines.forEach { line ->
            when {
                line.isBlank() -> flush()
                current == null -> {
                    current = StringBuilder(line.trim())
                    indent = indentOf(line)
                }
                wrapped && !listItem.containsMatchIn(line) -> current?.append(' ')?.append(line.trim())
                else -> {
                    flush()
                    current = StringBuilder(line.trim())
                    indent = indentOf(line)
                }
            }
            if (!wrapped) flush()
        }
        flush()
        return blocks
    }

    private fun indentOf(line: String): Int {
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        return ((leading.count { it == ' ' } + leading.count { it == '\t' } * 4) / 4).coerceAtMost(3)
    }

    private fun block(text: String, indent: Int): TextBlock {
        val flat = text.replace('\t', ' ')
        val letters = flat.filter { it.isLetter() }
        return if (flat.length < 70 && letters.length > 3 && letters.none { it.isLowerCase() }) TextBlock.Heading(flat) else TextBlock.Paragraph(flat, indent)
    }

    fun fingerprint(text: String): Set<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        text.forEach { c ->
            if (c.isLetterOrDigit()) {
                word.append(c.lowercaseChar())
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words.zipWithNext { a, b -> "$a $b" }.toHashSet()
    }

    fun comparable(license: License, text: String): String =
        if (license.id == "LGPL-3.0") text.substringBefore("\nGNU GENERAL PUBLIC LICENSE") else text

    fun identify(text: String, prints: Map<License, Set<String>>): List<LicenseGuess> {
        val sample = fingerprint(text)
        if (sample.size < 8) return emptyList()
        return prints.map { (license, print) ->
            val shared = sample.count { it in print }.toDouble()
            LicenseGuess(license, (shared / sample.size + 4 * shared / (sample.size + print.size)) / 3)
        }.sortedByDescending { it.score }
    }

    fun attribution(attribution: Attribution): List<List<String>> {
        val license = attribution.license
        val source = attribution.source.trim()
        fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return listOf(
            listOf(attribution.title, attribution.author, if (license.url.isEmpty()) license.short else "${license.short} (${license.url})"),
            listOf(
                if (source.isEmpty()) html(attribution.title) else "<a href=\"${html(source)}\">${html(attribution.title)}</a>",
                html(attribution.author),
                if (license.url.isEmpty()) html(license.short) else "<a href=\"${html(license.url)}\">${html(license.short)}</a>",
            ),
            listOf(
                if (source.isEmpty()) attribution.title else "[${attribution.title}]($source)",
                attribution.author,
                if (license.url.isEmpty()) license.short else "[${license.short}](${license.url})",
            ),
        )
    }
}
