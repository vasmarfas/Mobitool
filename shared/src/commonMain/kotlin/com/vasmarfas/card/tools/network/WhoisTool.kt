package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
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
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.whoisQuery
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.StringResource

val whoisTool = Tool(
    id = "whois-rdap",
    category = ToolCategory.NETWORK,
    title = Res.string.whois_rdap,
    description = Res.string.whois_rdap_description,
    icon = Icons.Filled.Badge,
    keywords = listOf("registrar", "domain", "asn", "rdap", "регистратор", "домен", "владелец"),
) { WhoisScreen() }

private data class WhoisFacts(val rows: List<Pair<String, String>>, val raw: String)

private val prettyJson = Json { prettyPrint = true }

private val rdapLabels: Map<String, StringResource> = mapOf(
    "Domain" to Res.string.domain,
    "Unicode name" to Res.string.rdap_unicode_name,
    "Handle" to Res.string.rdap_handle,
    "Name" to Res.string.name,
    "Type" to Res.string.type,
    "Range" to Res.string.range,
    "Country" to Res.string.country,
    "Status" to Res.string.status,
    "Name servers" to Res.string.rdap_name_servers,
    "Whois server" to Res.string.rdap_whois_server,
    "Registration" to Res.string.rdap_registration,
    "Expiration" to Res.string.rdap_expiration,
    "Last changed" to Res.string.rdap_last_changed,
    "Last update of rdap database" to Res.string.rdap_last_update,
    "Transfer" to Res.string.rdap_transfer,
    "Registrar" to Res.string.rdap_registrar,
    "Registrant" to Res.string.rdap_registrant,
    "Abuse" to Res.string.rdap_abuse,
    "Technical" to Res.string.rdap_technical,
    "Administrative" to Res.string.rdap_administrative,
)

object Rdap {
    fun url(query: String): String {
        val q = query.trim().lowercase()
        return when {
            q.startsWith("as") && q.drop(2).all { it.isDigit() } -> "https://rdap.org/autnum/${q.drop(2)}"
            q.all { it.isDigit() } -> "https://rdap.org/autnum/$q"
            looksLikeIp(q) || q.contains('/') -> "https://rdap.org/ip/$q"
            else -> "https://rdap.org/domain/${Punycode.toAscii(q)}"
        }
    }

    fun facts(json: JsonObject): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        fun str(key: String) = (json[key] as? JsonPrimitive)?.content
        str("ldhName")?.let { rows += "Domain" to it }
        str("unicodeName")?.let { if (it != str("ldhName")) rows += "Unicode name" to it }
        str("handle")?.let { rows += "Handle" to it }
        str("name")?.let { rows += "Name" to it }
        str("type")?.let { rows += "Type" to it }
        str("startAddress")?.let { rows += "Range" to "$it – ${str("endAddress") ?: ""}" }
        str("country")?.let { rows += "Country" to it }
        (json["status"] as? JsonArray)?.let { rows += "Status" to it.joinToString(", ") { s -> s.jsonPrimitive.content } }
        (json["events"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            val action = (o["eventAction"] as? JsonPrimitive)?.content ?: return@forEach
            val date = (o["eventDate"] as? JsonPrimitive)?.content ?: ""
            rows += action.replaceFirstChar { it.uppercase() } to date
        }
        (json["nameservers"] as? JsonArray)?.let { ns ->
            rows += "Name servers" to ns.joinToString("\n") { (it.jsonObject["ldhName"] as? JsonPrimitive)?.content ?: "" }
        }
        (json["secureDNS"] as? JsonObject)?.get("delegationSigned")?.let { rows += "DNSSEC" to it.jsonPrimitive.content }
        (json["entities"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            val roles = (o["roles"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content } ?: "entity"
            val name = vcardValue(o, "fn") ?: (o["handle"] as? JsonPrimitive)?.content ?: ""
            val email = vcardValue(o, "email")
            val org = vcardValue(o, "org")
            val value = listOfNotNull(name.ifBlank { null }, org, email).joinToString(" · ")
            if (value.isNotBlank()) rows += roles.replaceFirstChar { it.uppercase() } to value
            (o["publicIds"] as? JsonArray)?.forEach { id ->
                val io = id.jsonObject
                rows += ((io["type"] as? JsonPrimitive)?.content ?: "id") to ((io["identifier"] as? JsonPrimitive)?.content ?: "")
            }
        }
        (json["port43"] as? JsonPrimitive)?.let { rows += "Whois server" to it.content }
        return rows
    }

    fun vcardValue(entity: JsonObject, field: String): String? {
        val vcard = (entity["vcardArray"] as? JsonArray)?.getOrNull(1) as? JsonArray ?: return null
        for (item in vcard) {
            val arr = item as? JsonArray ?: continue
            if ((arr.getOrNull(0) as? JsonPrimitive)?.content == field) {
                ((arr.getOrNull(1) as? JsonObject)?.get("label") as? JsonPrimitive)?.content?.let { return it }
                val value: JsonElement = arr.getOrNull(3) ?: continue
                return when (value) {
                    is JsonPrimitive -> value.content
                    is JsonArray -> value.joinToString(" ") { (it as? JsonPrimitive)?.content ?: "" }.trim()
                    else -> null
                }
            }
        }
        return null
    }
}

@Composable
private fun WhoisScreen() {
    var query by rememberSaveable { mutableStateOf("vasmarfas.com") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rdap by remember { mutableStateOf<WhoisFacts?>(null) }
    var whois by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runRdap() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true; error = null; rdap = null; whois = null
        scope.launch {
            runCatching {
                val response = Net.client.get(Rdap.url(q)) { header("accept", "application/rdap+json, application/json") }
                val text = response.bodyAsText()
                if (response.status.value >= 400) error("HTTP ${response.status.value}: ${text.take(200)}")
                val json = Net.json.parseToJsonElement(text).jsonObject
                WhoisFacts(Rdap.facts(json), prettyJson.encodeToString(JsonElement.serializer(), json))
            }.onSuccess { rdap = it }.onFailure { error = networkErrorText(it) }
            loading = false
        }
    }

    fun runWhois() {
        val q = hostFrom(query).let { if (it.all { c -> c.code < 0x80 }) it else Punycode.toAscii(it) }
        if (q.isEmpty()) return
        loading = true; error = null; whois = null
        scope.launch {
            runCatching {
                val first = whoisQuery("whois.iana.org", q, 8000)
                val referral = Regex("(?im)^(?:refer|whois):\\s*(\\S+)").find(first)?.groupValues?.get(1)
                if (referral != null) {
                    val detail = whoisQuery(referral, q, 8000)
                    val second = Regex("(?im)^\\s*Registrar WHOIS Server:\\s*(\\S+)").find(detail)?.groupValues?.get(1)
                    if (second != null && !second.equals(referral, ignoreCase = true)) {
                        "% $referral\n$detail\n\n% $second\n" + runCatching { whoisQuery(second, q, 8000) }.getOrDefault("")
                    } else "% $referral\n$detail"
                } else "% whois.iana.org\n$first"
            }.onSuccess { whois = it }.onFailure { error = networkErrorText(it) }
            loading = false
        }
    }

    ToolInputField(
        value = query,
        onValueChange = { query = it },
        label = Res.string.whois_domain_ip_address_network.str(),
        placeholder = "example.com · 8.8.8.8 · AS15169",
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = "RDAP", onClick = ::runRdap, enabled = !loading)
        if (NetCapabilities.tcp) {
            TextButton(onClick = ::runWhois, enabled = !loading) { Text(Res.string.whois_port_43.str()) }
        }
    }
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    rdap?.let { facts ->
        ResultCard(title = "RDAP") {
            facts.rows.forEach { (k, v) -> KeyValueRow(rdapLabels.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value?.str() ?: k, v, mono = false) }
        }
        ResultCard(title = Res.string.raw_response.str()) { MonoText(facts.raw) }
    }
    whois?.let { ResultCard(title = "Whois") { MonoText(it) } }
}
