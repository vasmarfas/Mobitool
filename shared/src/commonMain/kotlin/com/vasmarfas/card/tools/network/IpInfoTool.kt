package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.regionName
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

val ipInfoTool = Tool(
    id = "ip-info",
    category = ToolCategory.NETWORK,
    title = Res.string.my_ip_and_ip_lookup,
    description = Res.string.ip_info_description,
    icon = Icons.Filled.Public,
    keywords = listOf("geoip", "asn", "isp", "location", "external ip", "abuse", "rdap", "ptr", "внешний ip", "провайдер", "геолокация", "владелец", "жалоба"),
) { IpInfoScreen() }

private typealias Facts = List<Pair<StringResource, String>>

private data class IpFacts(val ip: String, val address: Facts, val place: Facts, val connection: Facts, val lat: Double?, val lon: Double?)

private const val OTHER_FAMILY_TIMEOUT_MS = 5_000L

private val monoFields = setOf(
    Res.string.ip_field_ip, Res.string.ip_field_asn, Res.string.ip_field_coordinates, Res.string.ip_field_hostname,
    Res.string.ip_field_your_ipv4, Res.string.ip_field_your_ipv6, Res.string.ip_owner_range, Res.string.ip_owner_cidr,
)

private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() }

private fun localCountry(name: String, code: String?): String =
    if (code == null) name else "${regionName(code, appLang) ?: name} ($code)"

private object IpApi {
    suspend fun lookup(ip: String): IpFacts {
        val primary = runCatching { ipwho(ip) }
        return primary.getOrElse { ipapi(ip) }
    }

    private suspend fun ipwho(ip: String): IpFacts {
        val text = Net.client.get("https://ipwho.is/$ip").bodyAsText()
        val json = Net.json.parseToJsonElement(text).jsonObject
        if (json.s("success") == "false") error(json.s("message") ?: getString(Res.string.ip_service_no_data))
        val connection = json["connection"] as? JsonObject
        val tz = json["timezone"] as? JsonObject
        val flag = (json["flag"] as? JsonObject)?.s("emoji") ?: ""
        return IpFacts(
            ip = json.s("ip") ?: ip,
            address = listOfNotNull(
                json.s("ip")?.let { Res.string.ip_field_ip to it },
                json.s("type")?.let { Res.string.ip_field_type to it },
            ),
            place = listOfNotNull(
                json.s("country")?.let { Res.string.ip_field_country to "$flag ${localCountry(it, json.s("country_code"))}".trim() },
                json.s("region")?.let { Res.string.ip_field_region to it },
                json.s("city")?.let { Res.string.ip_field_city to it },
                json.s("postal")?.let { Res.string.ip_field_postal_code to it },
                json.s("latitude")?.let { lat -> Res.string.ip_field_coordinates to "$lat, ${json.s("longitude")}" },
                tz?.s("id")?.let { Res.string.ip_field_time_zone to "$it (${tz.s("utc") ?: ""})" },
                tz?.s("current_time")?.let { Res.string.ip_field_local_time to it },
                json.s("calling_code")?.let { Res.string.ip_field_calling_code to "+$it" },
                json.s("is_eu")?.let { Res.string.ip_field_eu to it },
            ),
            connection = listOfNotNull(
                connection?.s("isp")?.let { Res.string.ip_field_isp to it },
                connection?.s("org")?.let { Res.string.ip_field_organization to it },
                connection?.s("asn")?.let { Res.string.ip_field_asn to "AS$it" },
                connection?.s("domain")?.let { Res.string.ip_field_domain to it },
            ),
            lat = json.s("latitude")?.toDoubleOrNull(),
            lon = json.s("longitude")?.toDoubleOrNull(),
        )
    }

    private suspend fun ipapi(ip: String): IpFacts {
        val path = if (ip.isEmpty()) "https://ipapi.co/json/" else "https://ipapi.co/$ip/json/"
        val text = Net.client.get(path).bodyAsText()
        val json = Net.json.parseToJsonElement(text).jsonObject
        if (json.s("error") == "true") error(json.s("reason") ?: getString(Res.string.ip_service_no_data))
        return IpFacts(
            ip = json.s("ip") ?: ip,
            address = listOfNotNull(
                json.s("ip")?.let { Res.string.ip_field_ip to it },
                json.s("version")?.let { Res.string.ip_field_type to it },
            ),
            place = listOfNotNull(
                json.s("country_name")?.let { Res.string.ip_field_country to localCountry(it, json.s("country_code")) },
                json.s("region")?.let { Res.string.ip_field_region to it },
                json.s("city")?.let { Res.string.ip_field_city to it },
                json.s("postal")?.let { Res.string.ip_field_postal_code to it },
                json.s("latitude")?.let { lat -> Res.string.ip_field_coordinates to "$lat, ${json.s("longitude")}" },
                json.s("timezone")?.let { Res.string.ip_field_time_zone to "$it (${json.s("utc_offset") ?: ""})" },
            ),
            connection = listOfNotNull(
                json.s("org")?.let { Res.string.ip_field_organization to it },
                json.s("asn")?.let { Res.string.ip_field_asn to it },
            ),
            lat = json.s("latitude")?.toDoubleOrNull(),
            lon = json.s("longitude")?.toDoubleOrNull(),
        )
    }
}

// the regional registry keeps who holds the block and where to report abuse, rdap.org redirects to the right one
private object NetworkOwner {
    private val registries = listOf(
        "arin" to Res.string.rir_arin,
        "ripe" to Res.string.rir_ripe,
        "apnic" to Res.string.rir_apnic,
        "lacnic" to Res.string.rir_lacnic,
        "afrinic" to Res.string.rir_afrinic,
    )

    private fun JsonObject.roles(): List<String> = (this["roles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    // arin nests the abuse and admin contacts inside the organisation
    private fun JsonObject.entities(): List<JsonObject> =
        (this["entities"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().flatMap { listOf(it) + it.entities() }

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun lookup(ip: String): Facts {
        val response = Net.client.get("https://rdap.org/ip/$ip") { header("accept", "application/rdap+json, application/json") }
        if (response.status.value >= 400) error("HTTP ${response.status.value}")
        val json = Net.json.parseToJsonElement(response.bodyAsText()).jsonObject
        val entities = json.entities()
        val registrant = entities.firstOrNull { "registrant" in it.roles() && Rdap.vcardValue(it, "kind") == "org" }
            ?: entities.firstOrNull { "registrant" in it.roles() }
        val abuse = entities.firstOrNull { "abuse" in it.roles() && Rdap.vcardValue(it, "email") != null }
        val admin = entities.firstOrNull { ("technical" in it.roles() || "administrative" in it.roles()) && Rdap.vcardValue(it, "email") != null }
        val abuseEmail = abuse?.let { Rdap.vcardValue(it, "email") }
        val description = json.objects("remarks").firstOrNull { it.s("title") == null || it.s("title") == "description" }
            ?.let { remark -> (remark["description"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n") }
        val cidrs = json.objects("cidr0_cidrs").mapNotNull { c -> (c.s("v4prefix") ?: c.s("v6prefix"))?.let { "$it/${c.s("length")}" } }
        fun event(action: String) = json.objects("events").firstOrNull { it.s("eventAction") == action }?.s("eventDate")?.substringBefore('T')
        val registry = json.s("port43")?.let { host -> registries.firstOrNull { host.contains(it.first) } }?.second
        return listOfNotNull(
            json.s("name")?.let { Res.string.ip_owner_network to it },
            description?.takeIf { it.isNotBlank() }?.let { Res.string.description to it },
            registrant?.let { Rdap.vcardValue(it, "fn") }?.let { Res.string.ip_field_organization to it },
            registrant?.let { Rdap.vcardValue(it, "adr") }?.takeIf { it.isNotBlank() }?.let { Res.string.address to it.replace("\n", ", ") },
            abuseEmail?.let { Res.string.ip_owner_abuse_email to it },
            abuse?.let { Rdap.vcardValue(it, "tel") }?.let { Res.string.ip_owner_abuse_phone to it.removePrefix("tel:") },
            admin?.let { Rdap.vcardValue(it, "email") }?.takeIf { it != abuseEmail }?.let { Res.string.ip_owner_admin_email to it },
            json.s("startAddress")?.let { Res.string.ip_owner_range to "$it – ${json.s("endAddress") ?: ""}" },
            cidrs.takeIf { it.isNotEmpty() }?.let { Res.string.ip_owner_cidr to it.joinToString("\n") },
            json.s("country")?.let { Res.string.ip_field_country to localCountry(it, it) },
            registry?.let { Res.string.ip_owner_registry to getString(it) },
            event("registration")?.let { Res.string.ip_owner_registered to it },
            event("last changed")?.let { Res.string.ip_owner_changed to it },
        )
    }
}

private suspend fun resolve(host: String): String? {
    val cloudflare = DnsResolver.CLOUDFLARE
    for (type in intArrayOf(1, 28)) {
        val answer = DnsClient.queryDoh(cloudflare.dohUrl!!, host, type, cloudflare.dohFlavour).answers.firstOrNull { it.type == type }
        if (answer != null) return answer.data
    }
    return null
}

private suspend fun hostName(ip: String): String? {
    val name = Ipv4.parse(ip)?.let(Ipv4::ptrName) ?: Ipv6Address.parse(ip)?.ptrName() ?: return null
    val cloudflare = DnsResolver.CLOUDFLARE
    return DnsClient.queryDoh(cloudflare.dohUrl!!, name, 12, cloudflare.dohFlavour).answers.firstOrNull { it.type == 12 }?.data
}

// the lookup service sees one address, the other family is asked from hosts that answer only over it
private suspend fun otherFamily(ip: String): Pair<StringResource, String>? {
    val v4 = Ipv4.parse(ip) != null
    val url = if (v4) "https://api6.ipify.org" else "https://api.ipify.org"
    val other = withTimeoutOrNull(OTHER_FAMILY_TIMEOUT_MS) { runCatching { Net.client.get(url).bodyAsText().trim() }.getOrNull() }
    return other?.takeIf { looksLikeIp(it) && it != ip }?.let { (if (v4) Res.string.ip_field_your_ipv6 else Res.string.ip_field_your_ipv4) to it }
}

@Composable
private fun IpInfoScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var facts by remember { mutableStateOf<IpFacts?>(null) }
    var own by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf<Facts>(emptyList()) }
    var owner by remember { mutableStateOf<Result<Facts>?>(null) }
    val scope = rememberCoroutineScope()

    fun run(input: String) {
        loading = true; error = null; facts = null
        scope.launch {
            runCatching {
                val host = hostFrom(input)
                val ip = if (host.isEmpty() || looksLikeIp(host)) host else resolve(host) ?: error(getString(Res.string.ip_host_not_found, host))
                val found = IpApi.lookup(ip)
                if (ip == host) found else found.copy(address = listOf(Res.string.host to host) + found.address)
            }.onSuccess { facts = it; own = input.isBlank() }.onFailure { error = networkErrorText(it) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (facts == null) run("") }
    val ip = facts?.ip
    LaunchedEffect(ip) {
        extra = emptyList()
        owner = null
        if (ip == null) return@LaunchedEffect
        launch { runCatching { hostName(ip) }.getOrNull()?.let { extra = listOf(Res.string.ip_field_hostname to it) + extra } }
        if (own) launch { otherFamily(ip)?.let { extra = extra + it } }
        owner = runCatching { NetworkOwner.lookup(ip) }
    }

    ToolInputField(
        value = query,
        onValueChange = { query = it },
        label = Res.string.ip_address_or_hostname_empty.str(),
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.lookup.str(), onClick = { run(query) }, enabled = !loading)
        TextButton(onClick = { query = ""; run("") }, enabled = !loading) { Text(Res.string.my_ip.str()) }
    }
    if (loading) LoadingRow()
    error?.let { ErrorText(it) }
    val f = facts ?: return
    ResultCard { FactRows(f.address + extra) }
    if (f.place.isNotEmpty()) {
        ResultCard(Res.string.ip_section_location.str()) {
            FactRows(f.place)
            if (f.lat != null && f.lon != null) {
                TextButton(onClick = { openUrl("https://www.openstreetmap.org/?mlat=${f.lat}&mlon=${f.lon}#map=10/${f.lat}/${f.lon}") }) {
                    Text(Res.string.show_on_map.str())
                }
            }
            Hint(Res.string.ip_location_hint.str())
        }
    }
    if (f.connection.isNotEmpty()) ResultCard(Res.string.ip_section_connection.str()) { FactRows(f.connection) }
    val rows = owner?.getOrNull()
    when {
        owner == null -> LoadingRow(Res.string.ip_owner_loading.str())
        rows.isNullOrEmpty() -> Hint(Res.string.ip_owner_failed.str())
        else -> ResultCard(Res.string.ip_section_owner.str()) {
            FactRows(rows)
            Hint(Res.string.ip_owner_hint.str())
        }
    }
}

@Composable
private fun FactRows(rows: Facts) {
    rows.forEach { (k, v) ->
        val value = when (v) {
            "true" -> Res.string.yes.str()
            "false" -> Res.string.no.str()
            else -> v
        }
        KeyValueRow(k.str(), value, mono = k in monoFields)
    }
}
