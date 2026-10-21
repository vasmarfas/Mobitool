package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Inflate
import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.XmlElement
import com.vasmarfas.card.core.ZipArchive
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Crc32
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.StringResource

class DmarcException(val reason: StringResource) : Exception(reason.key)

class MailIssue(val error: Boolean, val text: StringResource, vararg val args: String)

class DmarcTag(val name: String, val value: String)

class DmarcRecord(val tags: List<DmarcTag>, val issues: List<MailIssue>)

class DmarcLookup(val domain: String, val source: String, val records: List<String>, val unauthorized: List<String>)

data class DmarcRow(
    val sourceIp: String,
    val count: Long,
    val disposition: String,
    val dkim: String,
    val spf: String,
    val dkimAuth: List<Pair<String, String>>,
    val spfAuth: List<Pair<String, String>>,
) {
    val passed: Boolean get() = dkim == "pass" || spf == "pass"
}

class DmarcReport(
    val org: String,
    val reportId: String,
    val begin: Long,
    val end: Long,
    val domain: String,
    val policy: String,
    val rows: List<DmarcRow>,
    val reports: Int = 1,
) {
    val total: Long get() = rows.sumOf { it.count }
    val passed: Long get() = rows.filter { it.passed }.sumOf { it.count }

    fun count(disposition: String): Long = rows.filter { it.disposition == disposition }.sumOf { it.count }
}

object Dmarc {
    const val MAX_REPORT_BYTES = 64 * 1024 * 1024

    private val policies = setOf("none", "quarantine", "reject")
    private val reportSize = Regex("!\\d+[kmgt]?", RegexOption.IGNORE_CASE)
    private val cloudflare = DnsResolver.CLOUDFLARE.dohUrl!!

    fun isRecord(txt: String): Boolean = parse(txt).tags.firstOrNull()?.let { it.name == "v" && it.value == "DMARC1" } == true

    fun parse(text: String): DmarcRecord {
        val tags = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map {
            DmarcTag(it.substringBefore('=').trim().lowercase(), it.substringAfter('=', "").trim())
        }
        val issues = mutableListOf<MailIssue>()
        val first = tags.firstOrNull()
        if (first?.name != "v" || first.value != "DMARC1") issues += MailIssue(true, Res.string.dmarc_version_first)
        tags.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { issues += MailIssue(true, Res.string.dmarc_repeated_tag, it) }
        for (tag in tags) {
            val value = tag.value.lowercase()
            val valid = when (tag.name) {
                "p", "sp", "np" -> value in policies
                "adkim", "aspf" -> value == "r" || value == "s"
                "pct" -> value.toIntOrNull()?.let { it in 0..100 } == true
                "ri" -> value.toLongOrNull()?.let { it > 0 } == true
                "fo" -> value.split(':').all { it.trim() in setOf("0", "1", "d", "s") }
                "rf" -> value.split(':').all { it.trim() == "afrf" }
                "rua", "ruf" -> value.split(',').all { uri ->
                    val address = uri.trim().replace(reportSize, "")
                    address.startsWith("mailto:") && '@' in address
                }
                else -> true
            }
            if (!valid) issues += MailIssue(true, Res.string.dmarc_bad_value, tag.name, tag.value)
        }
        fun value(name: String) = tags.firstOrNull { it.name == name }?.value?.lowercase()
        val policy = value("p")
        if (policy == null) issues += MailIssue(true, Res.string.dmarc_no_policy)
        if (policy == "none") issues += MailIssue(false, Res.string.dmarc_policy_none)
        if (policy != null && policy != "none" && value("sp") == "none") issues += MailIssue(false, Res.string.dmarc_sp_none)
        value("pct")?.toIntOrNull()?.takeIf { it in 0..99 }?.let { issues += MailIssue(false, Res.string.dmarc_pct_partial, it.toString()) }
        if (value("rua") == null) issues += MailIssue(false, Res.string.dmarc_no_rua)
        return DmarcRecord(tags, issues)
    }

    fun externalReportHosts(record: String, domain: String): List<String> =
        parse(record).tags.filter { it.name == "rua" || it.name == "ruf" }
            .flatMap { it.value.split(',') }
            .map { it.trim().replace(reportSize, "") }
            .filter { '@' in it }
            .map { it.substringAfterLast('@').lowercase() }
            .distinct()
            .filter { it.split('.').takeLast(2) != domain.split('.').takeLast(2) }

    suspend fun lookup(domain: String): DmarcLookup {
        val start = domain.trim().trimEnd('.').lowercase()
        var name = start
        while (true) {
            val records = dmarcRecords("_dmarc.$name")
            if (records.isNotEmpty() || name.count { it == '.' } < 2) {
                val hosts = records.flatMap { externalReportHosts(it, name) }.distinct()
                return DmarcLookup(start, name, records, hosts.filter { dmarcRecords("$name._report._dmarc.$it").isEmpty() })
            }
            name = name.substringAfter('.')
        }
    }

    private suspend fun dmarcRecords(name: String): List<String> =
        DnsClient.queryDoh(cloudflare, name, 16, DohFlavour.JSON).answers.filter { it.type == 16 && isRecord(it.data) }.map { it.data }

    suspend fun hostNames(ips: List<String>): Map<String, String> = coroutineScope {
        val gate = Semaphore(8)
        ips.distinct().map { ip ->
            async {
                val name = Ipv4.parse(ip)?.let(Ipv4::ptrName) ?: Ipv6Address.parse(ip)?.ptrName()
                val answer = name?.let { gate.withPermit { runCatching { DnsClient.queryDoh(cloudflare, it, 12, DohFlavour.JSON) }.getOrNull() } }
                ip to answer?.answers?.firstOrNull { it.type == 12 }?.data.orEmpty()
            }
        }.awaitAll().toMap()
    }

    fun readReports(bytes: ByteArray): List<DmarcReport> {
        val documents = runCatching { unpack(bytes) }.getOrElse { throw DmarcException(Res.string.dmarc_damaged_archive) }
        return documents.map { runCatching { parseReport(TextDecoding.decode(it)) }.getOrNull() ?: throw DmarcException(Res.string.dmarc_not_a_report) }
            .ifEmpty { throw DmarcException(Res.string.dmarc_not_a_report) }
    }

    fun parseReport(xml: String): DmarcReport? {
        val root = parseXml(xml)
        if (root.localName != "feedback") return null
        val meta = root.child("report_metadata")
        val range = meta?.child("date_range")
        val published = root.child("policy_published")
        return DmarcReport(
            org = meta.text("org_name"),
            reportId = meta.text("report_id"),
            begin = range.text("begin").toLongOrNull() ?: 0,
            end = range.text("end").toLongOrNull() ?: 0,
            domain = published.text("domain"),
            policy = published?.elements.orEmpty().filter { it.localName != "domain" }.joinToString(" ") { "${it.localName}=${it.text().trim()}" },
            rows = root.children("record").map { record ->
                val row = record.child("row")
                val evaluated = row?.child("policy_evaluated")
                val auth = record.child("auth_results")
                DmarcRow(
                    sourceIp = row.text("source_ip"),
                    count = row.text("count").toLongOrNull() ?: 0,
                    disposition = evaluated.text("disposition").lowercase(),
                    dkim = evaluated.text("dkim").lowercase(),
                    spf = evaluated.text("spf").lowercase(),
                    dkimAuth = auth?.children("dkim").orEmpty().map { it.text("domain") to it.text("result").lowercase() },
                    spfAuth = auth?.children("spf").orEmpty().map { it.text("domain") to it.text("result").lowercase() },
                )
            }.sortedByDescending { it.count },
        )
    }

    fun merge(reports: List<DmarcReport>): DmarcReport {
        val unique = reports.distinctBy { it.org to it.reportId }
        return unique.singleOrNull() ?: DmarcReport(
            org = unique.map { it.org }.distinct().joinToString(", "),
            reportId = "",
            begin = unique.minOf { it.begin },
            end = unique.maxOf { it.end },
            domain = unique.map { it.domain }.distinct().joinToString(", "),
            policy = unique.map { it.policy }.distinct().joinToString("\n"),
            rows = unique.flatMap { it.rows }.groupBy { it.copy(count = 0) }
                .map { (row, group) -> row.copy(count = group.sumOf { it.count }) }
                .sortedByDescending { it.count },
            reports = unique.size,
        )
    }

    private fun XmlElement?.text(name: String): String = this?.child(name)?.text()?.trim().orEmpty()

    private fun unpack(bytes: ByteArray): List<ByteArray> = when {
        bytes.size > 18 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte() -> listOf(gunzip(bytes))
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> {
            val zip = ZipArchive(bytes)
            zip.entries.filter { !it.isDirectory && it.name.endsWith(".xml", ignoreCase = true) }.map { zip.read(it) }
        }
        else -> listOf(bytes)
    }

    private fun gunzip(data: ByteArray): ByteArray {
        fun uint(at: Int, bytes: Int) = (0 until bytes).fold(0L) { acc, i -> acc or ((data[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun afterZero(from: Int) = (from until data.size).first { data[it] == 0.toByte() } + 1
        val flags = data[3].toInt()
        var pos = 10
        if (flags and 4 != 0) pos += 2 + uint(pos, 2).toInt()
        if (flags and 8 != 0) pos = afterZero(pos)
        if (flags and 16 != 0) pos = afterZero(pos)
        if (flags and 2 != 0) pos += 2
        val size = uint(data.size - 4, 4)
        require(size <= MAX_REPORT_BYTES)
        val out = Inflate.inflateUpTo(data, pos, data.size - 8 - pos, size.toInt())
        check(Crc32.compute(out) == uint(data.size - 8, 4).toInt())
        return out
    }
}
