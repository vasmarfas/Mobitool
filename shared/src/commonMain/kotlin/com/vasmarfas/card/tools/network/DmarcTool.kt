package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.tools.developer.formatted
import com.vasmarfas.card.tools.developer.localDateTime
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadedFile
import com.vasmarfas.card.ui.components.LoadedFileCard
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.OpenFileButton
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val dmarcTool = Tool(
    id = "dmarc",
    category = ToolCategory.NETWORK,
    title = Res.string.dmarc_analyzer,
    description = Res.string.dmarc_analyzer_description,
    icon = Icons.Filled.MarkEmailRead,
    keywords = listOf("dmarc", "spf", "dkim", "rua", "ruf", "email", "mail", "spoofing", "aggregate report", "почта", "отчёт", "подделка", "антиспам"),
) { DmarcScreen() }

private enum class DmarcMode(val title: StringResource) {
    RECORD(Res.string.dmarc_record),
    REPORT(Res.string.dmarc_report),
    BUILD(Res.string.dmarc_builder),
}

private val tagLabels = mapOf(
    "v" to Res.string.version,
    "p" to Res.string.dmarc_tag_p,
    "sp" to Res.string.dmarc_tag_sp,
    "np" to Res.string.dmarc_tag_np,
    "pct" to Res.string.dmarc_tag_pct,
    "rua" to Res.string.dmarc_tag_rua,
    "ruf" to Res.string.dmarc_tag_ruf,
    "adkim" to Res.string.dmarc_tag_adkim,
    "aspf" to Res.string.dmarc_tag_aspf,
    "fo" to Res.string.dmarc_tag_fo,
    "rf" to Res.string.dmarc_tag_rf,
    "ri" to Res.string.dmarc_tag_ri,
)

private val policyLabels = mapOf(
    "none" to Res.string.dmarc_policy_deliver,
    "quarantine" to Res.string.dmarc_policy_quarantine,
    "reject" to Res.string.dmarc_policy_reject,
)

private val alignmentLabels = mapOf("r" to Res.string.dmarc_relaxed, "s" to Res.string.dmarc_strict)

private val failureOptionLabels = mapOf(
    "0" to Res.string.dmarc_fo_all,
    "1" to Res.string.dmarc_fo_any,
    "d" to Res.string.dmarc_fo_dkim,
    "s" to Res.string.dmarc_fo_spf,
)

private class OpenedReport(val file: LoadedFile, val reports: List<DmarcReport>, val error: StringResource?)

@Composable
private fun DmarcScreen() {
    var mode by rememberSaveable { mutableStateOf(DmarcMode.RECORD) }
    SegmentedChoice(options = DmarcMode.entries, selected = mode, onSelect = { mode = it }, label = { it.title.str() })
    when (mode) {
        DmarcMode.RECORD -> RecordCheck()
        DmarcMode.REPORT -> ReportViewer()
        DmarcMode.BUILD -> RecordBuilder()
    }
}

@Composable
private fun RecordCheck() {
    var domain by rememberSaveable { mutableStateOf("vasmarfas.com") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lookup by remember { mutableStateOf<DmarcLookup?>(null) }
    val scope = rememberCoroutineScope()

    fun check() {
        val name = Punycode.toAscii(hostFrom(domain.substringAfterLast('@')))
        if (name.isEmpty()) return
        loading = true; error = null; lookup = null
        scope.launch {
            runCatching { Dmarc.lookup(name) }
                .onSuccess { lookup = it }
                .onFailure { error = networkErrorText(it) }
            loading = false
        }
    }

    ToolInputField(
        value = domain,
        onValueChange = { domain = it },
        label = Res.string.dmarc_domain_or_email.str(),
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    ActionButton(text = Res.string.check.str(), onClick = ::check, enabled = !loading, icon = Icons.Filled.Search)
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    val result = lookup ?: return
    if (result.records.isEmpty()) {
        ErrorText(stringResource(Res.string.dmarc_no_record, result.domain))
        return
    }
    if (result.source != result.domain) Hint(stringResource(Res.string.dmarc_inherited, result.domain, result.source))
    if (result.records.size > 1) ErrorText(Res.string.dmarc_several_records.str())
    result.records.forEach { RecordCard(result.source, it, result.unauthorized) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordBuilder() {
    var domain by rememberSaveable { mutableStateOf("vasmarfas.com") }
    var policy by rememberSaveable { mutableStateOf("quarantine") }
    var subdomains by rememberSaveable { mutableStateOf("") }
    var pct by rememberSaveable { mutableStateOf("100") }
    var rua by rememberSaveable { mutableStateOf("") }
    var strictDkim by rememberSaveable { mutableStateOf(false) }
    var strictSpf by rememberSaveable { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf("0") }
    val name = Punycode.toAscii(hostFrom(domain.substringAfterLast('@')))
    val record = listOfNotNull(
        "v=DMARC1",
        "p=$policy",
        subdomains.ifEmpty { null }?.let { "sp=$it" },
        pct.trim().takeIf { it.isNotEmpty() && it != "100" }?.let { "pct=$it" },
        rua.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
            ?.joinToString(",", prefix = "rua=") { if (it.startsWith("mailto:")) it else "mailto:$it" },
        "adkim=s".takeIf { strictDkim },
        "aspf=s".takeIf { strictSpf },
        failure.takeIf { it != "0" }?.let { "fo=$it" },
    ).joinToString("; ")

    ToolInputField(
        value = domain,
        onValueChange = { domain = it },
        label = Res.string.domain.str(),
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    ToolSection(tagLabels.getValue("p").str()) {
        ChoiceChips(options = policyLabels.keys.toList(), selected = policy, onSelect = { policy = it }, label = { policyLabels.getValue(it).str() })
    }
    ToolSection(tagLabels.getValue("sp").str()) {
        ChoiceChips(
            options = listOf("") + policyLabels.keys,
            selected = subdomains,
            onSelect = { subdomains = it },
            label = { policyLabels[it]?.str() ?: Res.string.dmarc_same_as_p.str() },
        )
    }
    NumberField(value = pct, onValueChange = { pct = it }, label = tagLabels.getValue("pct").str(), suffix = "%")
    ToolInputField(
        value = rua,
        onValueChange = { rua = it },
        label = tagLabels.getValue("rua").str(),
        placeholder = "dmarc@$name",
        keyboardType = KeyboardType.Email,
        monospace = true,
    )
    SwitchRow(
        label = tagLabels.getValue("adkim").str(),
        checked = strictDkim,
        onCheckedChange = { strictDkim = it },
        description = alignmentLabels.getValue(if (strictDkim) "s" else "r").str(),
    )
    SwitchRow(
        label = tagLabels.getValue("aspf").str(),
        checked = strictSpf,
        onCheckedChange = { strictSpf = it },
        description = alignmentLabels.getValue(if (strictSpf) "s" else "r").str(),
    )
    ToolSection(tagLabels.getValue("fo").str()) {
        val chosen = failure.split(':')
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            failureOptionLabels.forEach { (option, label) ->
                FilterChip(
                    selected = option in chosen,
                    onClick = {
                        val others = if (option == "0" || option == "1") chosen - "0" - "1" else chosen
                        val next = if (option in chosen) chosen - option else others + option
                        failure = failureOptionLabels.keys.filter { it in next }.joinToString(":").ifEmpty { "0" }
                    },
                    label = { Text(label.str()) },
                )
            }
        }
    }
    ResultCard {
        KeyValueRow(Res.string.name.str(), "_dmarc.$name")
        KeyValueRow("TXT", record)
        Dmarc.parse(record).issues.forEach { MailIssueText(it) }
        Dmarc.externalReportHosts(record, name).forEach { host ->
            Text(
                stringResource(Res.string.dmarc_build_external, host, "$name._report._dmarc.$host"),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RecordCard(domain: String, txt: String, unauthorized: List<String>) {
    val record = remember(txt) { Dmarc.parse(txt) }
    ResultCard(title = "_dmarc.$domain") {
        KeyValueRow("TXT", txt)
        if (record.issues.isEmpty() && unauthorized.isEmpty()) {
            Text(Res.string.dmarc_record_ok.str(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        record.issues.forEach { MailIssueText(it) }
        unauthorized.forEach { host ->
            Text(
                stringResource(Res.string.dmarc_rua_not_authorized, host, domain, "$domain._report._dmarc.$host"),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        record.tags.forEach { tag -> KeyValueRow(tagMeaning(tag), "${tag.name}=${tag.value}", copyable = false) }
    }
}

@Composable
fun MailIssueText(issue: MailIssue) {
    Text(
        stringResource(issue.text, *issue.args),
        color = if (issue.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun tagMeaning(tag: DmarcTag): String {
    val label = (tagLabels[tag.name] ?: Res.string.dmarc_unknown_tag).str()
    val value = tag.value.lowercase()
    val detail = when (tag.name) {
        "p", "sp", "np" -> policyLabels[value]?.str()
        "adkim", "aspf" -> alignmentLabels[value]?.str()
        "pct" -> value.toIntOrNull()?.let { stringResource(Res.string.percent_value, it.toString()) }
        "ri" -> value.toLongOrNull()?.let { "${(it / 3600.0).fmt(1)} ${Res.string.unit_h.str()}" }
        "fo" -> value.split(':').mapNotNull { failureOptionLabels[it.trim()]?.str() }.joinToString(", ").ifEmpty { null }
        else -> null
    }
    return if (detail == null) label else "$label: $detail"
}

@Composable
private fun ReportViewer() {
    var typed by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf(listOf<OpenedReport>()) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var hosts by remember { mutableStateOf(mapOf<String, String>()) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tooLarge = stringResource(Res.string.dmarc_file_too_large, Dmarc.MAX_REPORT_BYTES / (1024 * 1024))
    if (opened.isEmpty()) {
        ToolInputField(
            value = typed,
            onValueChange = { typed = it },
            label = Res.string.dmarc_report_xml.str(),
            singleLine = false,
            minLines = 6,
            placeholder = "<feedback>…</feedback>",
            monospace = true,
        )
    }
    opened.forEach { file ->
        LoadedFileCard(file.file) { opened = opened - file }
        file.error?.let { ErrorText(it.str()) }
    }
    OpenFileButton(setOf("xml", "gz", "zip"), label = (if (opened.isEmpty()) Res.string.open_file else Res.string.add_files).str(), multiple = true) { picked ->
        fileError = null
        if (picked.size() > Dmarc.MAX_REPORT_BYTES) {
            fileError = tooLarge
        } else {
            val bytes = picked.readBytes()
            val result = withContext(Dispatchers.Default) { runCatching { Dmarc.readReports(bytes) } }
            val error = result.exceptionOrNull()?.let { (it as? DmarcException)?.reason ?: Res.string.dmarc_not_a_report }
            opened = opened + OpenedReport(LoadedFile(picked.name, bytes), result.getOrDefault(emptyList()), error)
        }
    }
    fileError?.let { ErrorText(it) }
    val typedReport = remember(typed) { typed.takeIf { it.isNotBlank() }?.let { runCatching { Dmarc.parseReport(it) }.getOrNull() } }
    if (opened.isEmpty() && typed.isBlank()) return
    val reports = if (opened.isEmpty()) listOfNotNull(typedReport) else opened.flatMap { it.reports }
    if (reports.isEmpty()) {
        if (opened.isEmpty()) ErrorText(Res.string.dmarc_not_a_report.str())
        return
    }
    val report = remember(reports) { Dmarc.merge(reports) }
    val total = report.total
    ResultCard(title = report.org.ifEmpty { report.domain }) {
        if (report.reports > 1) KeyValueRow(Res.string.dmarc_reports.str(), report.reports.toString(), copyable = false)
        KeyValueRow(Res.string.domain.str(), report.domain)
        KeyValueRow(Res.string.period.str(), "${localDateTime(report.begin * 1000).formatted()} – ${localDateTime(report.end * 1000).formatted()}")
        if (report.reportId.isNotEmpty()) KeyValueRow(Res.string.dmarc_report_id.str(), report.reportId)
        KeyValueRow(Res.string.dmarc_published_policy.str(), report.policy)
        KeyValueRow(Res.string.dmarc_messages.str(), total.fmtGrouped(), copyable = false)
        KeyValueRow(Res.string.dmarc_passed.str(), share(report.passed, total), copyable = false)
        KeyValueRow(Res.string.dmarc_failed.str(), share(total - report.passed, total), copyable = false)
        report.count("quarantine").takeIf { it > 0 }?.let { KeyValueRow(Res.string.dmarc_quarantined.str(), share(it, total), copyable = false) }
        report.count("reject").takeIf { it > 0 }?.let { KeyValueRow(Res.string.dmarc_rejected.str(), share(it, total), copyable = false) }
    }
    ResultCard(title = Res.string.dmarc_senders.str()) {
        val weights = listOf(2.4f, 1.1f, 1.75f, 1.75f)
        TableBlock {
            TableRow(listOf("IP", Res.string.dmarc_messages.str(), "SPF", "DKIM"), header = true, weights = weights)
            report.rows.forEach { row ->
                val source = listOf(row.sourceIp, hosts[row.sourceIp].orEmpty(), row.disposition.takeIf { it != "none" }.orEmpty())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                TableRow(
                    listOf(source, row.count.fmtGrouped(), authCell(row.spf, row.spfAuth), authCell(row.dkim, row.dkimAuth)),
                    weights = weights,
                    mono = false,
                )
            }
        }
        Hint(Res.string.dmarc_alignment_hint.str())
        val unresolved = report.rows.map { it.sourceIp }.filter { it !in hosts }
        if (unresolved.isNotEmpty()) {
            TextButton(
                onClick = {
                    resolving = true
                    scope.launch {
                        hosts = hosts + Dmarc.hostNames(unresolved)
                        resolving = false
                    }
                },
                enabled = !resolving,
            ) { Text(Res.string.dmarc_resolve_hosts.str()) }
        }
        if (resolving) LoadingRow()
    }
}

@Composable
private fun share(count: Long, total: Long): String =
    if (total == 0L) count.fmtGrouped() else "${count.fmtGrouped()} · ${stringResource(Res.string.percent_value, (count * 100.0 / total).fmt(1))}"

private fun authCell(result: String, details: List<Pair<String, String>>): String =
    result + details.joinToString("") { "\n${it.first} ${it.second}" }
