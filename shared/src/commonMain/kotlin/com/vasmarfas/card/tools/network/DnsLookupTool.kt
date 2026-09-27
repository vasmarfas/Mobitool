package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val dnsLookupTool = Tool(
    id = "dns-lookup",
    category = ToolCategory.NETWORK,
    title = Res.string.dns_lookup,
    description = Res.string.dns_lookup_description,
    icon = Icons.Filled.Dns,
    keywords = listOf("dig", "nslookup", "resolve", "domain", "mx", "txt", "ptr", "днс", "домен", "doh", "rfc 8484"),
) { DnsLookupScreen() }

private data class DnsState(
    val result: DnsQueryResult? = null,
    val error: String? = null,
    val elapsedMs: Long = 0,
    val loading: Boolean = false,
)

private class ResolverAnswer(val label: String, val response: DnsResponse?, val error: String?)

@Composable
private fun DnsLookupScreen() {
    val resolverLabels = DnsResolver.entries.associateWith { it.label.str() }
    var name by rememberSaveable { mutableStateOf("vasmarfas.com") }
    var type by rememberSaveable { mutableStateOf("A") }
    var resolverKey by rememberSaveable { mutableStateOf(DnsResolver.CLOUDFLARE.name) }
    var customServer by rememberSaveable { mutableStateOf("192.168.88.1") }
    var customResolvers by remember { mutableStateOf(CustomResolvers.load()) }
    var state by remember { mutableStateOf(DnsState()) }
    var compare by remember { mutableStateOf<List<ResolverAnswer>?>(null) }
    val scope = rememberCoroutineScope()
    val choices: List<ResolverChoice> =
        DnsResolver.entries.filter { it.usableHere }.map { ResolverChoice.BuiltIn(it) } + customResolvers.map { ResolverChoice.Custom(it) }
    val choice = choices.firstOrNull { it.key == resolverKey } ?: ResolverChoice.BuiltIn(DnsResolver.CLOUDFLARE)

    fun run() {
        val query = name.trim()
        if (query.isEmpty()) return
        val effectiveType = if (looksLikeIp(query)) "PTR" else type
        val effectiveName = when {
            Ipv4.parse(query) != null -> Ipv4.ptrName(Ipv4.parse(query)!!)
            Ipv6Address.parse(query) != null -> Ipv6Address.parse(query)!!.ptrName()
            else -> Punycode.toAscii(query)
        }
        state = DnsState(loading = true)
        compare = null
        scope.launch {
            val started = currentEpochMillis()
            state = runCatching { DnsClient.query(effectiveName, effectiveType, choice, customServer) }
                .fold(
                    onSuccess = { DnsState(result = it, elapsedMs = currentEpochMillis() - started) },
                    onFailure = { DnsState(error = networkErrorText(it)) },
                )
        }
    }

    fun runCompare() {
        val query = name.trim()
        if (query.isEmpty()) return
        val targets = DnsResolver.entries.filter { it.dohUrl != null }.map { Triple(resolverLabels.getValue(it), it.dohUrl!!, it.dohFlavour) } +
            customResolvers.map { Triple(it.name.ifBlank { it.url }, DohRequest.normalize(it.url), it.flavour) }
        state = state.copy(loading = true)
        scope.launch {
            compare = coroutineScope {
                targets.map { (label, url, flavour) ->
                    async {
                        runCatching { DnsClient.queryDoh(url, Punycode.toAscii(query), DnsTypes.byName[type] ?: 1, flavour) }
                            .fold({ ResolverAnswer(label, it, null) }, { ResolverAnswer(label, null, networkErrorText(it)) })
                    }
                }.awaitAll()
            }
            state = state.copy(loading = false)
        }
    }

    ToolInputField(
        value = name,
        onValueChange = { name = it },
        label = Res.string.domain_name_or_ip_reverse_lookup.str(),
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    ChoiceChips(options = DnsTypes.queryable, selected = type, onSelect = { type = it }, label = { it })
    DropdownChoice(
        options = choices,
        selected = choice,
        onSelect = { resolverKey = it.key },
        label = Res.string.resolver.str(),
        text = { it.label() },
    )
    if (choice is ResolverChoice.BuiltIn) {
        if (choice.resolver == DnsResolver.CUSTOM) {
            ToolInputField(value = customServer, onValueChange = { customServer = it }, label = Res.string.dns_server_ip.str(), keyboardType = KeyboardType.Uri, monospace = true)
        }
        Text(choice.resolver.description.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!NetCapabilities.udp) {
        Text(
            Res.string.dns_browser_build_only_dns.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.lookup.str(), onClick = ::run, enabled = !state.loading, icon = Icons.Filled.Search)
        TextButton(onClick = ::runCompare, enabled = !state.loading) { Text(Res.string.compare_resolvers.str()) }
    }
    if (state.loading) LoadingRow()
    state.error?.let { ErrorText(it) }
    state.result?.let { result ->
        val response = result.response
        val flags = listOfNotNull("AA".takeIf { response.authoritative }, "AD".takeIf { response.authenticData }, "TC".takeIf { response.truncated })
        ResultCard(title = (listOf(response.rcodeName, "${state.elapsedMs} ${Res.string.unit_ms.str()}") + flags).joinToString(" · ")) {
            KeyValueRow(
                Res.string.resolved_via.str(),
                result.endpoint + (result.flavour?.let { " · ${it.label.str()}" } ?: ""),
                copyable = false,
            )
            if (response.answers.isEmpty()) {
                Text(Res.string.no_results.str(), style = MaterialTheme.typography.bodyMedium)
            } else {
                RecordTable(response.answers)
            }
        }
        if (response.authority.isNotEmpty()) {
            ResultCard(title = Res.string.authority.str()) { RecordTable(response.authority) }
        }
        if (response.additional.isNotEmpty()) {
            ResultCard(title = Res.string.additional.str()) { RecordTable(response.additional) }
        }
    }
    compare?.let { results ->
        ResultCard(title = Res.string.answers_across_resolvers.str()) {
            results.forEach { answer ->
                val text = answer.response?.let { resp ->
                    resp.answers.joinToString("\n") { "${it.typeName} ${it.data} (TTL ${it.ttl})" }.ifEmpty { resp.rcodeName }
                } ?: answer.error.orEmpty()
                KeyValueRow(answer.label, text)
            }
            val distinct = results.mapNotNull { it.response?.answers?.map { a -> a.data }?.sorted() }.distinct()
            Text(
                if (distinct.size <= 1) Res.string.all_resolvers_agree.str()
                else Res.string.dns_resolvers_return_different.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    CustomResolverSection(
        resolvers = customResolvers,
        onChange = {
            customResolvers = it
            CustomResolvers.save(it)
        },
    )
}

@Composable
private fun CustomResolverSection(resolvers: List<CustomResolver>, onChange: (List<CustomResolver>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var flavour by rememberSaveable { mutableStateOf(DohFlavour.AUTO) }
    var editing by rememberSaveable { mutableStateOf(-1) }

    fun reset() {
        name = ""
        url = ""
        flavour = DohFlavour.AUTO
        editing = -1
    }

    ToolSection(Res.string.custom_doh_resolvers.str()) {
        Text(
            Res.string.dns_json_api_name.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolInputField(value = name, onValueChange = { name = it }, label = Res.string.item_name.str())
        ToolInputField(
            value = url,
            onValueChange = { url = it },
            label = Res.string.doh_endpoint_url.str(),
            placeholder = "https://dns.example.com/dns-query",
            keyboardType = KeyboardType.Uri,
            monospace = true,
        )
        ChoiceChips(options = DohFlavour.entries, selected = flavour, onSelect = { flavour = it }, label = { it.label.str() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = if (editing >= 0) Res.string.save.str() else Res.string.add_resolver.str(),
                onClick = {
                    val entry = CustomResolver(name.trim().ifBlank { hostFrom(url) }, DohRequest.normalize(url), flavour)
                    onChange(if (editing in resolvers.indices) resolvers.toMutableList().also { it[editing] = entry } else resolvers + entry)
                    reset()
                },
                enabled = url.isNotBlank(),
                icon = Icons.Filled.Add,
            )
            if (editing >= 0) {
                TextButton(onClick = ::reset) { Text(Res.string.cancel.str()) }
            }
        }
        resolvers.forEachIndexed { index, resolver ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(resolver.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${resolver.url} · ${resolver.flavour.label.str()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        name = resolver.name
                        url = resolver.url
                        flavour = resolver.flavour
                        editing = index
                    },
                ) { Icon(Icons.Filled.Edit, contentDescription = Res.string.edit.str()) }
                IconButton(
                    onClick = {
                        onChange(resolvers.filterIndexed { i, _ -> i != index })
                        reset()
                    },
                ) { Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str()) }
            }
        }
    }
}

@Composable
private fun RecordTable(records: List<DnsRecord>) {
    TableBlock {
        TableRow(listOf(Res.string.name.str(), Res.string.type.str(), "TTL", Res.string.data.str()), header = true, weights = listOf(2f, 0.8f, 0.8f, 3f))
        records.forEach { r ->
            TableRow(listOf(r.name, r.typeName, r.ttl.toString(), r.data), weights = listOf(2f, 0.8f, 0.8f, 3f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        val all = records.joinToString("\n") { "${it.name}\t${it.ttl}\tIN\t${it.typeName}\t${it.data}" }
        CopyIconButton(all)
    }
}
