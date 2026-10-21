package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.monoFamily
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val spfTool = Tool(
    id = "spf",
    category = ToolCategory.NETWORK,
    title = Res.string.spf_checker,
    description = Res.string.spf_checker_description,
    icon = Icons.Filled.VerifiedUser,
    keywords = listOf(
        "spf", "txt", "include", "redirect", "ip4", "ip6", "rfc 7208", "email", "mail", "sender", "spoofing",
        "почта", "отправитель", "подделка", "антиспам",
    ),
) { SpfScreen() }

private val mechanismLabels = mapOf(
    "ip4" to Res.string.spf_mech_ip,
    "ip6" to Res.string.spf_mech_ip,
    "a" to Res.string.spf_mech_a,
    "mx" to Res.string.spf_mech_mx,
    "include" to Res.string.spf_mech_include,
    "all" to Res.string.spf_mech_all,
    "exists" to Res.string.spf_mech_exists,
    "ptr" to Res.string.spf_mech_ptr,
    "redirect" to Res.string.spf_mech_redirect,
    "exp" to Res.string.spf_mech_exp,
)

private val verdictTexts = mapOf(
    "pass" to Res.string.spf_verdict_pass,
    "fail" to Res.string.spf_verdict_fail,
    "softfail" to Res.string.spf_verdict_softfail,
    "neutral" to Res.string.spf_verdict_neutral,
    "none" to Res.string.spf_verdict_none,
    "permerror" to Res.string.spf_verdict_permerror,
)

@Composable
private fun SpfScreen() {
    var domain by rememberSaveable { mutableStateOf("gmail.com") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<SpfCheck?>(null) }
    val scope = rememberCoroutineScope()

    fun run() {
        val name = Punycode.toAscii(hostFrom(domain.substringAfterLast('@')))
        if (name.isEmpty()) return
        loading = true; error = null; check = null
        scope.launch {
            runCatching { Spf.check(name) }
                .onSuccess { check = it }
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
    ActionButton(text = Res.string.check.str(), onClick = ::run, enabled = !loading, icon = Icons.Filled.Search)
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    val result = check ?: return
    if (result.root.records.isEmpty()) {
        ErrorText(stringResource(Res.string.spf_no_record, result.root.domain))
        return
    }
    ResultCard(title = result.root.domain) {
        result.root.records.forEach { KeyValueRow("TXT", it) }
        if (result.issues.isEmpty()) {
            Text(Res.string.dmarc_record_ok.str(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        result.issues.forEach { MailIssueText(it) }
        KeyValueRow(Res.string.spf_lookups.str(), "${result.lookups} / ${Spf.MAX_LOOKUPS}", mono = false, copyable = false)
        KeyValueRow(Res.string.spf_voids.str(), "${result.voids} / ${Spf.MAX_VOIDS}", mono = false, copyable = false)
    }
    IpCheck(result)
    ResultCard(title = Res.string.spf_mechanisms.str()) { Terms(result.root, 0) }
    val ranges = result.ranges
    if (ranges.isNotEmpty()) {
        ResultCard { KeyValueRow("${Res.string.spf_allowed.str()}: ${ranges.size}", ranges.joinToString("\n")) }
    }
}

@Composable
private fun IpCheck(check: SpfCheck) {
    var ip by rememberSaveable { mutableStateOf("") }
    var verdict by remember(check) { mutableStateOf<SpfVerdict?>(null) }
    var error by remember(check) { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val address = ip.trim()
    ToolSection(Res.string.spf_check_ip.str()) {
        ToolInputField(
            value = ip,
            onValueChange = { ip = it; verdict = null },
            label = Res.string.spf_ip.str(),
            placeholder = "209.85.220.41",
            keyboardType = KeyboardType.Uri,
            monospace = true,
        )
        ActionButton(
            text = Res.string.check.str(),
            onClick = {
                running = true; error = null
                scope.launch {
                    runCatching { Spf.evaluate(check, address) }
                        .onSuccess { verdict = it }
                        .onFailure { error = networkErrorText(it) }
                    running = false
                }
            },
            enabled = !running && (Ipv4.parse(address) != null || Ipv6Address.parse(address) != null),
            icon = Icons.Filled.Search,
        )
        if (running) LoadingRow()
        error?.let { ErrorText(it) }
        val found = verdict ?: return@ToolSection
        ResultCard {
            KeyValueRow("SPF", found.result, mono = false, copyable = false)
            Text(
                stringResource(verdictTexts.getValue(found.result), address, check.root.domain),
                color = when (found.result) {
                    "pass" -> MaterialTheme.colorScheme.primary
                    "fail", "permerror" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            found.term?.let { KeyValueRow(Res.string.spf_matched.str(), "${it.raw} · ${found.domain}", copyable = false) }
        }
    }
}

@Composable
private fun Terms(node: SpfNode, depth: Int) {
    node.terms.forEach { term ->
        Column(Modifier.padding(start = (depth * 16).dp)) {
            Text(term.raw, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = monoFamily()))
            val label = (mechanismLabels[term.name] ?: Res.string.dmarc_unknown_tag).str()
            Hint(if (term.modifier) label else "${Spf.result(term.qualifier)}, $label")
            if (term.addresses.isNotEmpty()) Hint(term.addresses.joinToString(", "))
        }
        term.child?.let { Terms(it, depth + 1) }
    }
}
