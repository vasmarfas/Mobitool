package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*

data class SpfTerm(
    val raw: String,
    val qualifier: Char,
    val name: String,
    val value: String,
    val modifier: Boolean,
    val child: SpfNode? = null,
    val addresses: List<String> = emptyList(),
) {
    val target: String get() = value.substringBefore('/')
    val cidr: String get() = value.removePrefix(target)
}

class SpfNode(val domain: String, val records: List<String>, val terms: List<SpfTerm>)

class SpfCheck(val root: SpfNode, val lookups: Int, val voids: Int, val issues: List<MailIssue>) {
    val ranges: List<String>
        get() = buildList {
            fun collect(node: SpfNode) {
                node.terms.filter { it.qualifier == '+' }.forEach { term ->
                    term.child?.let(::collect)
                    if (term.name == "ip4" || term.name == "ip6") add(term.value)
                    addAll(term.addresses)
                }
            }
            collect(root)
        }.distinct()
}

class SpfVerdict(val result: String, val term: SpfTerm?, val domain: String)

object Spf {
    const val MAX_LOOKUPS = 10
    const val MAX_VOIDS = 2

    private val mechanisms = setOf("all", "include", "a", "mx", "ptr", "ip4", "ip6", "exists")
    private val whitespace = Regex("\\s+")
    private val macro = Regex("""%\{([a-zA-Z])(\d*)([rR]?)([.\-+,/_=]*)}|%%|%_|%-""")
    private val cloudflare = DnsResolver.CLOUDFLARE.dohUrl!!

    fun isRecord(txt: String): Boolean = txt.trim().lowercase().let { it == "v=spf1" || it.startsWith("v=spf1 ") }

    fun parse(record: String): List<SpfTerm> = record.trim().split(whitespace).drop(1).map { raw ->
        val qualifier = raw.first().takeIf { it in "+-~?" }
        val body = if (qualifier == null) raw else raw.drop(1)
        val split = body.indexOfFirst { it == ':' || it == '/' || it == '=' }
        val value = when {
            split < 0 -> ""
            body[split] == '/' -> body.substring(split)
            else -> body.substring(split + 1)
        }
        SpfTerm(raw, qualifier ?: '+', (if (split < 0) body else body.substring(0, split)).lowercase(), value, split >= 0 && body[split] == '=')
    }

    suspend fun check(domain: String): SpfCheck {
        var lookups = 0
        var voids = 0
        val issues = mutableListOf<MailIssue>()

        suspend fun query(name: String, type: Int) = DnsClient.queryDoh(cloudflare, name, type, DohFlavour.JSON).answers.filter { it.type == type }.map { it.data }

        suspend fun addresses(name: String, cidr: String): List<String> {
            val v4 = cidr.substringAfter('/', "").substringBefore('/').ifEmpty { null }
            val v6 = cidr.substringAfter("//", "").ifEmpty { null }
            return listOf(1 to v4, 28 to v6).flatMap { (type, prefix) -> query(name, type).map { if (prefix == null) it else "$it/$prefix" } }
        }

        suspend fun node(name: String, txt: List<String>, path: List<String>): SpfNode {
            val found = txt.filter(::isRecord)
            if (found.size > 1) issues += MailIssue(true, Res.string.spf_several_records, name)
            val terms = found.firstOrNull()?.let(::parse).orEmpty().map { term ->
                val target = term.target.ifEmpty { name }
                val skip = '%' in target || lookups > MAX_LOOKUPS * 2
                when (term.name) {
                    "include", "redirect" -> {
                        lookups++
                        if (skip || target in path) {
                            term
                        } else {
                            val answer = query(target, 16)
                            if (answer.isEmpty()) voids++
                            val child = node(target, answer, path + name)
                            if (child.records.isEmpty()) issues += MailIssue(true, Res.string.spf_target_without_record, term.raw, target)
                            term.copy(child = child)
                        }
                    }
                    "a" -> {
                        lookups++
                        if (skip) term else term.copy(addresses = addresses(target, term.cidr)).also { if (it.addresses.isEmpty()) voids++ }
                    }
                    "mx" -> {
                        lookups++
                        if (skip) {
                            term
                        } else {
                            val hosts = query(target, 15).map { it.substringAfterLast(' ') }
                            if (hosts.isEmpty()) voids++
                            term.copy(addresses = hosts.take(MAX_LOOKUPS).flatMap { addresses(it, term.cidr) })
                        }
                    }
                    "ptr", "exists" -> term.also { lookups++ }
                    else -> term
                }
            }
            return SpfNode(name, found, terms)
        }

        val root = node(domain, query(domain, 16), emptyList())
        fun inspect(node: SpfNode) {
            node.terms.forEach { term ->
                when {
                    term.modifier -> {}
                    term.name !in mechanisms -> issues += MailIssue(true, Res.string.spf_unknown_mechanism, term.raw, node.domain)
                    term.name == "ptr" -> issues += MailIssue(false, Res.string.spf_ptr, node.domain)
                    term.name == "all" && term.qualifier == '+' -> issues += MailIssue(true, Res.string.spf_pass_all, node.domain)
                    term.name == "all" && term.qualifier == '?' -> issues += MailIssue(false, Res.string.spf_neutral_all, node.domain)
                }
                term.child?.let(::inspect)
            }
        }
        inspect(root)
        if (root.records.isNotEmpty() && root.terms.none { it.name == "all" || it.name == "redirect" }) issues += MailIssue(false, Res.string.spf_no_all)
        if (lookups > MAX_LOOKUPS) issues += MailIssue(true, Res.string.spf_too_many_lookups, lookups.toString(), MAX_LOOKUPS.toString())
        if (voids > MAX_VOIDS) issues += MailIssue(true, Res.string.spf_too_many_voids, voids.toString(), MAX_VOIDS.toString())
        return SpfCheck(root, lookups, voids, issues)
    }

    suspend fun evaluate(check: SpfCheck, ip: String): SpfVerdict =
        if (check.lookups > MAX_LOOKUPS || check.voids > MAX_VOIDS) SpfVerdict("permerror", null, check.root.domain) else evaluate(check.root, ip)

    private suspend fun evaluate(node: SpfNode, ip: String): SpfVerdict {
        if (node.records.isEmpty()) return SpfVerdict("none", null, node.domain)
        if (node.records.size > 1) return SpfVerdict("permerror", null, node.domain)
        for (term in node.terms) {
            if (term.modifier) continue
            val matched = when (term.name) {
                "all" -> true
                "ip4", "ip6" -> contains(term.value, ip)
                "a", "mx" -> term.addresses.any { contains(it, ip) }
                "include" -> {
                    val inner = term.child?.let { evaluate(it, ip) } ?: return SpfVerdict("permerror", term, node.domain)
                    when (inner.result) {
                        "pass" -> return SpfVerdict(result(term.qualifier), inner.term, inner.domain)
                        "none", "permerror" -> return SpfVerdict("permerror", term, node.domain)
                        else -> false
                    }
                }
                "exists" -> DnsClient.queryDoh(cloudflare, expand(term.value, ip, node.domain), 1, DohFlavour.JSON).answers.any { it.type == 1 }
                "ptr" -> false
                else -> return SpfVerdict("permerror", term, node.domain)
            }
            if (matched) return SpfVerdict(result(term.qualifier), term, node.domain)
        }
        val redirect = node.terms.firstOrNull { it.name == "redirect" } ?: return SpfVerdict("neutral", null, node.domain)
        return redirect.child?.let { evaluate(it, ip) }?.takeIf { it.result != "none" } ?: SpfVerdict("permerror", redirect, node.domain)
    }

    fun result(qualifier: Char): String = when (qualifier) {
        '-' -> "fail"
        '~' -> "softfail"
        '?' -> "neutral"
        else -> "pass"
    }

    fun contains(range: String, ip: String): Boolean {
        val prefix = range.substringAfter('/', "").toIntOrNull()
        val network = range.substringBefore('/')
        Ipv4.parse(ip)?.let { address ->
            val net = Ipv4.parse(network) ?: return false
            val bits = prefix ?: 32
            return bits in 0..32 && Ipv4Subnet(net, bits).network == Ipv4Subnet(address, bits).network
        }
        val address = Ipv6Address.parse(ip) ?: return false
        val net = Ipv6Address.parse(network) ?: return false
        val bits = prefix ?: 128
        return bits in 0..128 && net.withPrefix(bits).groups.contentEquals(address.withPrefix(bits).groups)
    }

    fun expand(spec: String, ip: String, domain: String): String = macro.replace(spec) { match ->
        when (match.value) {
            "%%" -> "%"
            "%_" -> " "
            "%-" -> "%20"
            else -> {
                val v4 = Ipv4.parse(ip)
                val value = when (match.groupValues[1].lowercase()) {
                    "s" -> "postmaster@$domain"
                    "l" -> "postmaster"
                    "o", "d", "h" -> domain
                    "i" -> v4?.let(Ipv4::format) ?: Ipv6Address.parse(ip)?.expanded()?.replace(":", "")?.toList()?.joinToString(".").orEmpty()
                    "v" -> if (v4 != null) "in-addr" else "ip6"
                    else -> "unknown"
                }
                var parts = value.split(*match.groupValues[4].ifEmpty { "." }.toCharArray())
                if (match.groupValues[3].isNotEmpty()) parts = parts.reversed()
                match.groupValues[2].toIntOrNull()?.let { parts = parts.takeLast(it) }
                parts.joinToString(".")
            }
        }
    }
}
