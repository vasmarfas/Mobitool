package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

enum class LicenseUsage(val title: StringResource) {
    DISTRIBUTE(Res.string.license_usage_distribute),
    NETWORK(Res.string.license_usage_network),
    INTERNAL(Res.string.license_usage_internal),
}

enum class Verdict { YES, CONDITIONS, NO, UNKNOWN }

enum class LicenseException(val id: String) {
    CLASSPATH("classpath-exception-2.0"),
    GCC_RUNTIME("gcc-exception-3.1"),
    LLVM("llvm-exception"),
}

class LicenseRef(val license: License, val orLater: Boolean = false, val exception: LicenseException? = null)

class CompatResult(val verdict: Verdict, val reason: StringResource, val license: String = "", val args: List<String> = emptyList())

sealed class SpdxExpr {
    class Leaf(val text: String, val ref: LicenseRef?) : SpdxExpr()
    class And(val left: SpdxExpr, val right: SpdxExpr) : SpdxExpr()
    class Or(val left: SpdxExpr, val right: SpdxExpr) : SpdxExpr()
}

class DependencyLine(val label: String, val expression: String, val result: CompatResult, val choice: Boolean)

object LicenseCompat {
    private val aliases: Map<String, Pair<String, Boolean>> = buildMap {
        Licenses.all.forEach { license ->
            listOf(license.id, license.short, license.name).forEach { put(normalize(it), license.id to false) }
            if (license.versioned) {
                put(normalize(license.id + "-only"), license.id to false)
                put(normalize(license.id + "-or-later"), license.id to true)
                put(normalize(license.id + "+"), license.id to true)
            }
        }
        fun gnu(id: String, vararg names: String) = names.forEach { name ->
            put(normalize(name), id to false)
            put(normalize("$name+"), id to true)
            put(normalize("$name or later"), id to true)
        }
        gnu("GPL-2.0", "gplv2", "gpl2", "gpl 2", "gpl-2", "gpl v2", "gnu gpl v2")
        gnu("GPL-3.0", "gplv3", "gpl3", "gpl 3", "gpl-3", "gpl v3", "gnu gpl v3")
        gnu("LGPL-2.1", "lgplv2.1", "lgpl2.1", "lgpl 2.1", "lgpl-2.1", "lgplv2", "lgpl-2.0", "lgpl2")
        gnu("LGPL-3.0", "lgplv3", "lgpl3", "lgpl 3", "lgpl-3")
        gnu("AGPL-3.0", "agplv3", "agpl3", "agpl 3", "agpl-3", "agpl")
        gnu("GFDL-1.3", "gfdl", "fdl 1.3", "gfdlv1.3")
        // a GPL without a version lets the user pick any version, the closest catalog entry is v2 or later
        put("gpl", "GPL-2.0" to true)
        put("lgpl", "LGPL-2.1" to true)
        listOf(
            "expat" to "MIT", "mit license" to "MIT", "x11" to "MIT",
            "apache 2.0" to "Apache-2.0", "apache2" to "Apache-2.0", "apache-2" to "Apache-2.0", "apache 2" to "Apache-2.0",
            "apache license 2.0" to "Apache-2.0", "apache license, version 2.0" to "Apache-2.0", "asl 2.0" to "Apache-2.0", "asl2" to "Apache-2.0",
            "bsd" to "BSD-3-Clause", "new bsd" to "BSD-3-Clause", "revised bsd" to "BSD-3-Clause", "bsd-3" to "BSD-3-Clause", "3-clause bsd" to "BSD-3-Clause",
            "bsd 3-clause" to "BSD-3-Clause", "simplified bsd" to "BSD-2-Clause", "freebsd" to "BSD-2-Clause", "bsd-2" to "BSD-2-Clause",
            "2-clause bsd" to "BSD-2-Clause", "bsd 2-clause" to "BSD-2-Clause", "isc license" to "ISC", "zlib license" to "Zlib",
            "boost" to "BSL-1.0", "boost software license" to "BSL-1.0", "public domain" to "Unlicense", "unlicense" to "Unlicense",
            "cc0" to "CC0-1.0", "bsd-zero-clause" to "0BSD", "bsd zero clause" to "0BSD", "mit no attribution" to "MIT-0",
            "mpl 2.0" to "MPL-2.0", "mpl2" to "MPL-2.0", "mpl-2" to "MPL-2.0", "mozilla public license 2.0" to "MPL-2.0",
            "epl 2.0" to "EPL-2.0", "epl2" to "EPL-2.0", "eupl" to "EUPL-1.2", "eupl 1.2" to "EUPL-1.2",
            "busl" to "BUSL-1.1", "business source license" to "BUSL-1.1", "fsl" to "FSL-1.1-MIT", "functional source license" to "FSL-1.1-MIT",
            "fsl-1.1-alv2" to "FSL-1.1-MIT", "elastic" to "Elastic-2.0", "elastic license 2.0" to "Elastic-2.0", "elv2" to "Elastic-2.0",
            "sspl" to "SSPL-1.0", "server side public license" to "SSPL-1.0",
            "cc-by" to "CC-BY-4.0", "cc by" to "CC-BY-4.0", "cc-by-sa" to "CC-BY-SA-4.0", "cc by-sa" to "CC-BY-SA-4.0",
            "cc-by-nd" to "CC-BY-ND-4.0", "cc by-nd" to "CC-BY-ND-4.0", "cc-by-nc" to "CC-BY-NC-4.0", "cc by-nc" to "CC-BY-NC-4.0",
            "cc-by-nc-sa" to "CC-BY-NC-SA-4.0", "cc by-nc-sa" to "CC-BY-NC-SA-4.0", "cc-by-nc-nd" to "CC-BY-NC-ND-4.0", "cc by-nc-nd" to "CC-BY-NC-ND-4.0",
            "ofl" to "OFL-1.1", "sil ofl" to "OFL-1.1", "sil ofl 1.1" to "OFL-1.1", "ofl-1.1-rfn" to "OFL-1.1", "ofl-1.1-no-rfn" to "OFL-1.1",
            "odbl" to "ODbL-1.0", "cdla-permissive" to "CDLA-Permissive-2.0",
            "unlicensed" to "LicenseRef-Proprietary", "proprietary" to "LicenseRef-Proprietary", "commercial" to "LicenseRef-Proprietary",
            "all rights reserved" to "LicenseRef-Proprietary", "see license in license" to "LicenseRef-Proprietary",
            "polyform noncommercial" to "PolyForm-Noncommercial-1.0.0", "polyform small business" to "PolyForm-Small-Business-1.0.0",
            "polyform shield" to "LicenseRef-PolyForm-Shield-1.0.0", "polyform perimeter" to "LicenseRef-PolyForm-Perimeter-1.0.1",
            "polyform-perimeter-1.0.0" to "LicenseRef-PolyForm-Perimeter-1.0.1", "polyform internal use" to "LicenseRef-PolyForm-Internal-Use-1.0.0",
            "polyform free trial" to "LicenseRef-PolyForm-Free-Trial-1.0.0", "polyform strict" to "LicenseRef-PolyForm-Strict-1.0.0",
        ).forEach { (name, id) -> put(normalize(name), id to false) }
    }

    private fun normalize(text: String): String = text.trim().lowercase()
        .removePrefix("licenseref-")
        .replace('_', '-')
        .replace(Regex("\\s+"), " ")
        .removePrefix("the ")
        .removeSuffix(" license").removeSuffix(" licence")
        .trimEnd('*')
        .trim()

    fun resolve(text: String): LicenseRef? {
        val key = normalize(text)
        val (id, orLater) = aliases[key] ?: return null
        return Licenses.byId(id)?.let { LicenseRef(it, orLater) }
    }

    fun parse(text: String): SpdxExpr? {
        val tokens = text.replace("(", " ( ").replace(")", " ) ").replace("/", " OR ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else SpdxParser(tokens).parse()
    }

    fun evaluate(expr: SpdxExpr, project: License, projectOrLater: Boolean, usage: LicenseUsage): CompatResult = when (expr) {
        is SpdxExpr.Leaf -> expr.ref?.let { check(it, project, projectOrLater, usage) }
            ?: CompatResult(Verdict.UNKNOWN, Res.string.license_compat_unknown, expr.text)
        is SpdxExpr.Or -> listOf(evaluate(expr.left, project, projectOrLater, usage), evaluate(expr.right, project, projectOrLater, usage))
            .minBy { it.verdict.ordinal }
        is SpdxExpr.And -> listOf(evaluate(expr.left, project, projectOrLater, usage), evaluate(expr.right, project, projectOrLater, usage))
            .maxBy { it.verdict.ordinal }
    }

    fun checkList(text: String, project: License, projectOrLater: Boolean, usage: LicenseUsage): List<DependencyLine> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val split = listOf("\t", ": ").firstNotNullOfOrNull { separator -> line.indexOf(separator).takeIf { it > 0 }?.let { it to separator.length } }
            val label = split?.let { line.substring(0, it.first).trim() } ?: line
            val expression = split?.let { line.substring(it.first + it.second).trim() } ?: line
            val parsed = parse(expression)
            val result = parsed?.let { evaluate(it, project, projectOrLater, usage) }
                ?: CompatResult(Verdict.UNKNOWN, Res.string.license_compat_unknown, expression)
            DependencyLine(label, expression, result, parsed !is SpdxExpr.Leaf && parsed != null)
        }

    private fun gnuVersions(id: String, orLater: Boolean): Set<Int> = when (id) {
        "GPL-2.0" -> if (orLater) setOf(2, 3) else setOf(2)
        "LGPL-2.1" -> setOf(2, 3)
        "GPL-3.0", "LGPL-3.0", "AGPL-3.0" -> setOf(3)
        else -> emptySet()
    }

    private val gplHosts = setOf("GPL-2.0", "GPL-3.0", "AGPL-3.0")

    private val euplHosts = setOf("GPL-2.0", "GPL-3.0", "AGPL-3.0", "LGPL-2.1", "LGPL-3.0", "MPL-2.0")

    fun check(dep: LicenseRef, project: License, projectOrLater: Boolean, usage: LicenseUsage): CompatResult {
        val license = dep.license
        fun result(verdict: Verdict, reason: StringResource, vararg args: String) = CompatResult(verdict, reason, license.short, args.toList())
        val projectName = project.short
        val restricted = license.commercial != Commercial.ANY
        val content = LicenseSubject.CODE !in license.subjects && LicenseSubject.HARDWARE !in license.subjects
        val projectGnu = gnuVersions(project.id, projectOrLater)
        val depGnu = gnuVersions(license.id, dep.orLater)

        if (license.id == project.id) return result(Verdict.YES, Res.string.license_compat_same)
        if (license.family == LicenseFamily.PROPRIETARY) return result(Verdict.NO, Res.string.license_compat_proprietary)
        if (LicenseFlag.DISTRIBUTE !in license) {
            return if (usage == LicenseUsage.INTERNAL) result(Verdict.CONDITIONS, Res.string.license_compat_own_use)
            else result(Verdict.NO, Res.string.license_compat_no_distribution)
        }
        if (usage == LicenseUsage.INTERNAL) {
            return when {
                license.commercial == Commercial.NONE -> result(Verdict.CONDITIONS, Res.string.license_compat_noncommercial_only)
                restricted -> result(Verdict.CONDITIONS, Res.string.license_compat_restricted_same)
                else -> result(Verdict.YES, Res.string.license_compat_internal)
            }
        }
        if (restricted) {
            return when {
                project.commercial == Commercial.ANY -> result(Verdict.NO, Res.string.license_compat_restricted_open, projectName)
                license.copyleft.level >= 2 -> result(Verdict.NO, Res.string.license_compat_copyleft_whole, license.short)
                LicenseFlag.MODIFY !in license -> result(Verdict.CONDITIONS, Res.string.license_compat_no_derivatives)
                else -> result(Verdict.CONDITIONS, Res.string.license_compat_restricted_same)
            }
        }
        if (license.id == "CC-BY-SA-4.0" && project.id == "GPL-3.0") return result(Verdict.YES, Res.string.license_compat_cc_by_sa_gpl)
        if (content) {
            return when {
                license.id == "OFL-1.1" -> result(Verdict.CONDITIONS, Res.string.license_compat_font)
                LicenseFlag.MODIFY !in license -> result(Verdict.CONDITIONS, Res.string.license_compat_no_derivatives)
                license.copyleft.level >= 2 -> result(Verdict.CONDITIONS, Res.string.license_compat_asset_share_alike, license.short)
                LicenseFlag.NOTICE in license -> result(Verdict.YES, Res.string.license_compat_credit)
                else -> result(Verdict.YES, Res.string.license_compat_public_domain)
            }
        }
        if (dep.exception == LicenseException.GCC_RUNTIME) return result(Verdict.YES, Res.string.license_compat_runtime_exception)
        if (dep.exception == LicenseException.CLASSPATH) return result(Verdict.CONDITIONS, Res.string.license_compat_classpath)
        val notDistributed = usage == LicenseUsage.NETWORK
        return when (license.copyleft) {
            Copyleft.NONE -> when {
                license.id == "Apache-2.0" && dep.exception != LicenseException.LLVM && project.id == "GPL-2.0" && !projectOrLater ->
                    result(Verdict.NO, Res.string.license_compat_apache_gpl2)
                LicenseFlag.NOTICE in license -> result(Verdict.YES, Res.string.license_compat_permissive)
                else -> result(Verdict.YES, Res.string.license_compat_public_domain)
            }
            Copyleft.FILE -> when {
                notDistributed -> result(Verdict.YES, Res.string.license_compat_not_distributed)
                projectGnu.isNotEmpty() && license.id == "MPL-2.0" -> result(Verdict.YES, Res.string.license_compat_mpl_gpl)
                projectGnu.isNotEmpty() && license.id == "EPL-2.0" -> result(Verdict.CONDITIONS, Res.string.license_compat_epl_gpl)
                else -> result(Verdict.CONDITIONS, Res.string.license_compat_file, license.short)
            }
            Copyleft.LIBRARY -> when {
                project.id in gplHosts && (depGnu intersect projectGnu).isEmpty() -> result(Verdict.NO, Res.string.license_compat_lgpl3_gpl2)
                project.id in gplHosts -> result(Verdict.YES, Res.string.license_compat_gpl_family, projectName)
                notDistributed -> result(Verdict.YES, Res.string.license_compat_not_distributed)
                else -> result(Verdict.CONDITIONS, Res.string.license_compat_lgpl)
            }
            Copyleft.PROJECT -> when {
                notDistributed -> result(Verdict.YES, Res.string.license_compat_not_distributed)
                project.id in gplHosts && depGnu.isNotEmpty() && (depGnu intersect projectGnu).isNotEmpty() ->
                    result(Verdict.YES, Res.string.license_compat_gpl_family, projectName)
                project.id in gplHosts && depGnu.isNotEmpty() -> result(Verdict.NO, Res.string.license_compat_gpl_version)
                else -> result(Verdict.NO, Res.string.license_compat_copyleft_whole, license.short)
            }
            Copyleft.NETWORK -> when {
                license.id == "EUPL-1.2" && project.id in euplHosts -> result(Verdict.YES, Res.string.license_compat_eupl)
                license.id == "EUPL-1.2" -> result(Verdict.NO, Res.string.license_compat_eupl_no)
                license.id == "AGPL-3.0" && 3 in projectGnu && project.id != "LGPL-3.0" && project.id != "LGPL-2.1" ->
                    result(Verdict.CONDITIONS, Res.string.license_compat_agpl_gpl)
                license.id == "SSPL-1.0" -> result(Verdict.NO, Res.string.license_compat_sspl)
                else -> result(Verdict.NO, Res.string.license_compat_network)
            }
        }
    }
}

private class SpdxParser(private val tokens: List<String>) {
    private var pos = 0

    fun parse(): SpdxExpr? = or()

    private fun operator(): String? = tokens.getOrNull(pos)?.uppercase()?.takeIf { it == "AND" || it == "OR" || it == "WITH" }

    private fun name(): String {
        val words = mutableListOf<String>()
        while (pos < tokens.size && tokens[pos] != "(" && tokens[pos] != ")" && operator() == null) words += tokens[pos++]
        return words.joinToString(" ")
    }

    private fun or(): SpdxExpr? {
        var left = and() ?: return null
        while (operator() == "OR") {
            pos++
            left = SpdxExpr.Or(left, and() ?: break)
        }
        return left
    }

    private fun and(): SpdxExpr? {
        var left = atom() ?: return null
        while (operator() == "AND") {
            pos++
            left = SpdxExpr.And(left, atom() ?: break)
        }
        return left
    }

    private fun atom(): SpdxExpr? {
        if (tokens.getOrNull(pos) == "(") {
            pos++
            val inner = or()
            if (tokens.getOrNull(pos) == ")") pos++
            return inner
        }
        val text = name()
        if (text.isEmpty()) return null
        var ref = LicenseCompat.resolve(text)
        if (operator() == "WITH") {
            pos++
            val exception = name().lowercase()
            ref = ref?.let { LicenseRef(it.license, it.orLater, LicenseException.entries.firstOrNull { e -> e.id == exception }) }
        }
        return SpdxExpr.Leaf(text, ref)
    }
}
