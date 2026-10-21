package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val DKIM_HANDOFF_KEY = "dkim.handoff"

val dkimTool = Tool(
    id = "dkim",
    category = ToolCategory.NETWORK,
    title = Res.string.dkim_checker,
    description = Res.string.dkim_checker_description,
    icon = Icons.Filled.VpnKey,
    keywords = listOf(
        "dkim", "domainkey", "selector", "dkim-signature", "rsa", "ed25519", "public key", "email", "mail",
        "почта", "подпись", "селектор", "ключ",
    ),
) { DkimScreen() }

private val dkimTagLabels = mapOf(
    "v" to Res.string.version,
    "k" to Res.string.dkim_tag_k,
    "t" to Res.string.dkim_tag_t,
    "h" to Res.string.dkim_tag_h,
    "s" to Res.string.dkim_tag_s,
    "n" to Res.string.dkim_tag_n,
)

private val flagLabels = mapOf("y" to Res.string.dkim_flag_y, "s" to Res.string.dkim_flag_s)

@Composable
private fun DkimScreen() {
    var domain by rememberSaveable { mutableStateOf("github.com") }
    var selector by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var keys by remember { mutableStateOf<List<DkimKey>?>(null) }
    var checked by remember { mutableStateOf("" to "") }
    val scope = rememberCoroutineScope()

    fun run() {
        val name = Punycode.toAscii(hostFrom(domain.substringAfterLast('@')))
        if (name.isEmpty()) return
        val wanted = selector.trim()
        loading = true; error = null; keys = null
        scope.launch {
            runCatching { Dkim.lookup(name, wanted) }
                .onSuccess { keys = it; checked = name to wanted }
                .onFailure { error = networkErrorText(it) }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val sent = Prefs.store.get(DKIM_HANDOFF_KEY) ?: return@LaunchedEffect
        Prefs.store.remove(DKIM_HANDOFF_KEY)
        domain = sent.substringBefore(' ')
        selector = sent.substringAfter(' ', "")
        run()
    }

    ToolInputField(
        value = domain,
        onValueChange = { domain = it },
        label = Res.string.dmarc_domain_or_email.str(),
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    ToolInputField(
        value = selector,
        onValueChange = { selector = it },
        label = Res.string.dkim_selector.str(),
        placeholder = "google",
        keyboardType = KeyboardType.Uri,
        monospace = true,
        supportingText = if (selector.isBlank()) stringResource(Res.string.dkim_selector_hint, Dkim.commonSelectors.joinToString(", ")) else null,
    )
    ActionButton(text = Res.string.check.str(), onClick = ::run, enabled = !loading, icon = Icons.Filled.Search)
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    val found = keys ?: return
    val (name, wanted) = checked
    if (found.isEmpty()) {
        ErrorText(if (wanted.isEmpty()) stringResource(Res.string.dkim_none_common, name) else stringResource(Res.string.dkim_not_found, "$wanted._domainkey.$name"))
        return
    }
    found.forEach { KeyCard(name, it) }
}

@Composable
private fun KeyCard(domain: String, key: DkimKey) {
    val issues = remember(key) { key.issues }
    ResultCard(title = "${key.selector}._domainkey.$domain") {
        KeyValueRow("TXT", key.record)
        if (issues.isEmpty()) {
            Text(Res.string.dmarc_record_ok.str(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        issues.forEach { MailIssueText(it) }
        key.bits?.let { KeyValueRow(Res.string.dkim_key_bits.str(), "$it · ${key.type.uppercase()}", mono = false, copyable = false) }
        key.tags.filterKeys { it != "p" }.forEach { (tag, value) ->
            val label = (dkimTagLabels[tag] ?: Res.string.dmarc_unknown_tag).str()
            val flags = if (tag == "t") value.split(':').mapNotNull { flagLabels[it]?.str() }.joinToString(", ") else ""
            KeyValueRow(if (flags.isEmpty()) label else "$label: $flags", "$tag=$value", copyable = false)
        }
    }
}
