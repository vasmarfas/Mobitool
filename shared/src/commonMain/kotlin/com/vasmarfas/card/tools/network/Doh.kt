package com.vasmarfas.card.tools.network

import androidx.compose.runtime.Composable
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpDnsQuery
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.resources.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

enum class DohFlavour(val label: StringResource) {
    AUTO(Res.string.auto),
    JSON(Res.string.json_api),
    WIRE(Res.string.wire_rfc_8484),
}

enum class DnsResolver(val label: StringResource, val dohUrl: String?, val dohFlavour: DohFlavour, val udpAddress: String?, val description: StringResource) {
    CLOUDFLARE(Res.string.cloudflare_1_1_1_1, "https://cloudflare-dns.com/dns-query", DohFlavour.JSON, "1.1.1.1", Res.string.dns_over_https),
    GOOGLE(Res.string.google_8_8_8_8, "https://dns.google/resolve", DohFlavour.JSON, "8.8.8.8", Res.string.dns_over_https),
    QUAD9(Res.string.quad9_9_9_9_9, "https://dns.quad9.net:5053/dns-query", DohFlavour.JSON, "9.9.9.9", Res.string.blocks_malicious_domains),
    QUAD9_ECS(Res.string.quad9_ecs_9_9_9_11, "https://dns11.quad9.net/dns-query", DohFlavour.AUTO, "9.9.9.11", Res.string.doh_same_filtering_plus_edns),
    ADGUARD(Res.string.adguard_94_140_14_14, "https://dns.adguard-dns.com/resolve", DohFlavour.JSON, "94.140.14.14", Res.string.blocks_ads_and_trackers),
    MULLVAD(Res.string.mullvad_194_242_2_2, "https://dns.mullvad.net/dns-query", DohFlavour.AUTO, "194.242.2.2", Res.string.no_logging),
    DNS4EU(Res.string.dns4eu_86_54_11_1, "https://unfiltered.joindns4.eu/dns-query", DohFlavour.AUTO, "86.54.11.1", Res.string.eu_public_resolver_unfiltered),
    COMSS(Res.string.comss_one, "https://dns.comss.one/dns-query", DohFlavour.AUTO, null, Res.string.nodes_in_russia_and_europe),
    YANDEX(Res.string.yandex_77_88_8_8, null, DohFlavour.AUTO, "77.88.8.8", Res.string.udp_only),
    YANDEX_SAFE(Res.string.yandex_safe_77_88_8_88, null, DohFlavour.AUTO, "77.88.8.88", Res.string.yandex_dns_safe),
    YANDEX_FAMILY(Res.string.yandex_family_77_88_8_7, null, DohFlavour.AUTO, "77.88.8.7", Res.string.yandex_dns_family),
    CUSTOM(Res.string.custom_udp_server, null, DohFlavour.AUTO, null, Res.string.any_dns_server_over_udp_53);

    val usableHere: Boolean get() = dohUrl != null || (udpAddress != null || this == CUSTOM) && NetCapabilities.udp
}

@Serializable
data class CustomResolver(val name: String, val url: String, val flavour: DohFlavour = DohFlavour.AUTO)

sealed interface ResolverChoice {
    val key: String

    @Composable
    fun label(): String

    data class BuiltIn(val resolver: DnsResolver) : ResolverChoice {
        override val key: String get() = resolver.name

        @Composable
        override fun label(): String = resolver.label.str()
    }

    data class Custom(val resolver: CustomResolver) : ResolverChoice {
        override val key: String get() = "custom:${resolver.url}"

        @Composable
        override fun label(): String = resolver.name.ifBlank { resolver.url }
    }
}

data class DnsQueryResult(val response: DnsResponse, val endpoint: String, val flavour: DohFlavour?)

object CustomResolvers {
    private const val KEY = "dns.customResolvers"
    private val serializer = ListSerializer(CustomResolver.serializer())

    fun load(): List<CustomResolver> = runCatching { Prefs.store.get(KEY)?.let { Net.json.decodeFromString(serializer, it) } }.getOrNull() ?: emptyList()

    fun save(list: List<CustomResolver>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, list))
}

@OptIn(ExperimentalEncodingApi::class)
object DohRequest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    fun jsonUrl(endpoint: String, name: String, type: Int): String =
        withQuery(endpoint, "name=${name.trim().trimEnd('.').encodeURLParameter()}&type=$type")

    fun wireUrl(endpoint: String, query: ByteArray): String =
        withQuery(endpoint, "dns=${base64.encode(query)}")

    private fun withQuery(endpoint: String, query: String): String =
        endpoint + (if (endpoint.contains('?')) "&" else "?") + query
}

object DnsClient {
    private val flavours = mutableMapOf<String, DohFlavour>()

    suspend fun query(name: String, type: String, choice: ResolverChoice, customServer: String = ""): DnsQueryResult {
        val typeCode = DnsTypes.byName[type] ?: type.removePrefix("TYPE").toIntOrNull() ?: 1
        return when (choice) {
            is ResolverChoice.Custom -> doh(DohRequest.normalize(choice.resolver.url), name, typeCode, choice.resolver.flavour)
            is ResolverChoice.BuiltIn -> {
                val resolver = choice.resolver
                val server = if (resolver == DnsResolver.CUSTOM) customServer.trim() else resolver.udpAddress
                when {
                    resolver.dohUrl != null -> doh(resolver.dohUrl, name, typeCode, resolver.dohFlavour)
                    !server.isNullOrBlank() && NetCapabilities.udp -> DnsQueryResult(queryUdp(server, name, typeCode), "udp://$server:53", null)
                    else -> doh(DnsResolver.CLOUDFLARE.dohUrl!!, name, typeCode, DnsResolver.CLOUDFLARE.dohFlavour)
                }
            }
        }
    }

    suspend fun queryUdp(server: String, name: String, typeCode: Int): DnsResponse {
        val id = Random.nextInt(1, 0xFFFF)
        val payload = DnsMessage.buildQuery(name, typeCode, id)
        val response = DnsMessage.parse(udpQuery(server, 53, payload, 4000) ?: error(getString(Res.string.dns_no_response, server)))
        if (!response.truncated) return response
        return tcpDnsQuery(server, payload, 4000)?.let(DnsMessage::parse) ?: response
    }

    suspend fun queryDoh(url: String, name: String, typeCode: Int, flavour: DohFlavour = DohFlavour.AUTO): DnsResponse {
        val known = if (flavour == DohFlavour.AUTO) flavours[url] else flavour
        if (known != null) {
            val response = request(known, url, name, typeCode)
            flavours[url] = known
            return response
        }
        val json = runCatching { request(DohFlavour.JSON, url, name, typeCode) }
        if (json.isSuccess) {
            flavours[url] = DohFlavour.JSON
            return json.getOrThrow()
        }
        val response = request(DohFlavour.WIRE, url, name, typeCode)
        flavours[url] = DohFlavour.WIRE
        return response
    }

    private suspend fun doh(url: String, name: String, typeCode: Int, flavour: DohFlavour): DnsQueryResult =
        DnsQueryResult(queryDoh(url, name, typeCode, flavour), url, flavours[url])

    private suspend fun request(flavour: DohFlavour, url: String, name: String, typeCode: Int): DnsResponse =
        if (flavour == DohFlavour.WIRE) wireQuery(url, name, typeCode) else jsonQuery(url, name, typeCode)

    private suspend fun jsonQuery(url: String, name: String, typeCode: Int): DnsResponse {
        val response = Net.client.get(DohRequest.jsonUrl(url, name, typeCode)) {
            header("accept", "application/dns-json")
        }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}")
        val obj = Net.json.parseToJsonElement(response.bodyAsText()).jsonObject
        fun records(key: String): List<DnsRecord> = obj[key]?.jsonArray?.map { it.jsonObject }?.map { r ->
            val type = r["type"]?.jsonPrimitive?.intOrNull ?: 0
            DnsRecord(
                name = r["name"]?.jsonPrimitive?.content?.trimEnd('.') ?: "",
                type = type,
                ttl = r["TTL"]?.jsonPrimitive?.longOrNull ?: 0,
                data = r["data"]?.jsonPrimitive?.content?.let { jsonData(type, it) } ?: "",
            )
        } ?: emptyList()
        return DnsResponse(
            id = 0,
            rcode = obj["Status"]?.jsonPrimitive?.intOrNull ?: 0,
            authoritative = false,
            authenticData = obj["AD"]?.jsonPrimitive?.booleanOrNull ?: false,
            truncated = obj["TC"]?.jsonPrimitive?.booleanOrNull ?: false,
            answers = records("Answer"),
            authority = records("Authority"),
            additional = records("Additional"),
        )
    }

    private suspend fun wireQuery(url: String, name: String, typeCode: Int): DnsResponse {
        val query = DnsMessage.buildQuery(name, typeCode, 0)
        val response = Net.client.get(DohRequest.wireUrl(url, query)) {
            header("accept", "application/dns-message")
        }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}")
        return DnsMessage.parse(response.readRawBytes())
    }

    private val quoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun jsonData(type: Int, data: String): String {
        val text = data.trim()
        if (type == 16 || type == 99) return quoted.findAll(text).joinToString("") { it.groupValues[1] }.ifEmpty { text }
        return text.split(' ').joinToString(" ") { if (it.length > 1) it.trimEnd('.') else it }.replace("\"", "")
    }
}
