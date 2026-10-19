package com.vasmarfas.card.tools.network

import androidx.compose.runtime.Composable
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.quicDnsQuery
import com.vasmarfas.card.core.tcpDnsQuery
import com.vasmarfas.card.core.tlsDnsQuery
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

enum class DnsTransport(val label: String, val scheme: String, val defaultPort: Int) {
    UDP("UDP", "udp", 53),
    TCP("TCP", "tcp", 53),
    DOT("DoT", "tls", 853),
    DOQ("DoQ", "quic", 853),
    DOH("DoH", "https", 443);

    val availableHere: Boolean
        get() = when (this) {
            UDP -> NetCapabilities.udp
            TCP -> NetCapabilities.tcp
            DOT -> NetCapabilities.dnsOverTls
            DOQ -> NetCapabilities.dnsOverQuic
            DOH -> true
        }
}

enum class DnsResolver(
    val label: StringResource,
    val dohUrl: String?,
    val dohFlavour: DohFlavour,
    val udpAddress: String?,
    val dotHost: String?,
    val doqHost: String?,
    val description: StringResource,
) {
    CLOUDFLARE(Res.string.cloudflare_1_1_1_1, "https://cloudflare-dns.com/dns-query", DohFlavour.JSON, "1.1.1.1", "one.one.one.one", null, Res.string.reliable_anycast_no_filtering),
    GOOGLE(Res.string.google_8_8_8_8, "https://dns.google/resolve", DohFlavour.JSON, "8.8.8.8", "dns.google", null, Res.string.reliable_anycast_no_filtering),
    QUAD9(Res.string.quad9_9_9_9_9, "https://dns.quad9.net:5053/dns-query", DohFlavour.JSON, "9.9.9.9", "dns.quad9.net", "dns.quad9.net", Res.string.blocks_malicious_domains),
    QUAD9_ECS(Res.string.quad9_ecs_9_9_9_11, "https://dns11.quad9.net/dns-query", DohFlavour.AUTO, "9.9.9.11", "dns11.quad9.net", "dns11.quad9.net", Res.string.doh_same_filtering_plus_edns),
    ADGUARD(Res.string.adguard_94_140_14_14, "https://dns.adguard-dns.com/resolve", DohFlavour.JSON, "94.140.14.14", "dns.adguard-dns.com", "dns.adguard-dns.com", Res.string.blocks_ads_and_trackers),
    MULLVAD(Res.string.mullvad_194_242_2_2, "https://dns.mullvad.net/dns-query", DohFlavour.AUTO, "194.242.2.2", "dns.mullvad.net", null, Res.string.no_logging),
    DNS4EU(Res.string.dns4eu_86_54_11_1, "https://unfiltered.joindns4.eu/dns-query", DohFlavour.AUTO, "86.54.11.1", "unfiltered.joindns4.eu", null, Res.string.eu_public_resolver_unfiltered),
    COMSS(Res.string.comss_one, "https://dns.comss.one/dns-query", DohFlavour.AUTO, null, "dns.comss.one", "dns.comss.one", Res.string.nodes_in_russia_and_europe),
    YANDEX(Res.string.yandex_77_88_8_8, null, DohFlavour.AUTO, "77.88.8.8", "common.dot.dns.yandex.net", null, Res.string.no_filtering),
    YANDEX_SAFE(Res.string.yandex_safe_77_88_8_88, null, DohFlavour.AUTO, "77.88.8.88", "safe.dot.dns.yandex.net", null, Res.string.yandex_dns_safe),
    YANDEX_FAMILY(Res.string.yandex_family_77_88_8_7, null, DohFlavour.AUTO, "77.88.8.7", "family.dot.dns.yandex.net", null, Res.string.yandex_dns_family),
    CUSTOM(Res.string.custom_dns_server, null, DohFlavour.AUTO, null, null, null, Res.string.any_dns_server_by_address);

    val transports: List<DnsTransport>
        get() = DnsTransport.entries.filter { transport ->
            transport.availableHere && when (transport) {
                DnsTransport.UDP, DnsTransport.TCP -> udpAddress != null || this == CUSTOM
                DnsTransport.DOT -> dotHost != null || this == CUSTOM
                DnsTransport.DOQ -> doqHost != null || this == CUSTOM
                DnsTransport.DOH -> dohUrl != null
            }
        }

    val usableHere: Boolean get() = transports.isNotEmpty()
}

@Serializable
data class CustomResolver(val name: String, val url: String, val flavour: DohFlavour = DohFlavour.AUTO)

sealed interface ResolverChoice {
    val key: String
    val transports: List<DnsTransport>

    @Composable
    fun label(): String

    data class BuiltIn(val resolver: DnsResolver) : ResolverChoice {
        override val key: String get() = resolver.name
        override val transports: List<DnsTransport> get() = resolver.transports

        @Composable
        override fun label(): String = resolver.label.str()
    }

    data class Custom(val resolver: CustomResolver) : ResolverChoice {
        override val key: String get() = "custom:${resolver.url}"
        override val transports: List<DnsTransport> get() = listOf(DnsTransport.DOH)

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

    suspend fun query(name: String, type: String, choice: ResolverChoice, transport: DnsTransport, customServer: String = ""): DnsQueryResult {
        val typeCode = DnsTypes.byName[type] ?: type.removePrefix("TYPE").toIntOrNull() ?: 1
        val resolver = when (choice) {
            is ResolverChoice.Custom -> return doh(DohRequest.normalize(choice.resolver.url), name, typeCode, choice.resolver.flavour)
            is ResolverChoice.BuiltIn -> choice.resolver
        }
        if (transport == DnsTransport.DOH) return doh(resolver.dohUrl!!, name, typeCode, resolver.dohFlavour)
        val (host, port) = when {
            resolver == DnsResolver.CUSTOM -> parseServer(customServer, transport.defaultPort)
            transport == DnsTransport.DOT -> resolver.dotHost!! to transport.defaultPort
            transport == DnsTransport.DOQ -> resolver.doqHost!! to transport.defaultPort
            else -> resolver.udpAddress!! to transport.defaultPort
        }
        val endpoint = "${transport.scheme}://${if (host.contains(':')) "[$host]" else host}:$port"
        if (transport == DnsTransport.UDP) return DnsQueryResult(queryUdp(host, name, typeCode, port), endpoint, null)
        val payload = DnsMessage.buildQuery(name, typeCode, if (transport == DnsTransport.DOQ) 0 else Random.nextInt(1, 0xFFFF))
        val answer = when (transport) {
            DnsTransport.DOT -> tlsDnsQuery(host, port, payload, 4000)
            DnsTransport.DOQ -> quicDnsQuery(host, port, payload, 4000)
            else -> tcpDnsQuery(host, port, payload, 4000) ?: error(getString(Res.string.dns_no_response, host))
        }
        return DnsQueryResult(DnsMessage.parse(answer), endpoint, null)
    }

    fun parseServer(text: String, defaultPort: Int): Pair<String, Int> {
        val value = text.trim()
        fun port(raw: String) = raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        return when {
            value.startsWith("[") -> value.substringAfter('[').substringBefore(']') to port(value.substringAfter("]:", ""))
            value.count { it == ':' } == 1 -> value.substringBefore(':') to port(value.substringAfter(':'))
            else -> value to defaultPort
        }
    }

    suspend fun queryUdp(server: String, name: String, typeCode: Int, port: Int = 53): DnsResponse {
        val id = Random.nextInt(1, 0xFFFF)
        val payload = DnsMessage.buildQuery(name, typeCode, id)
        val response = DnsMessage.parse(udpQuery(server, port, payload, 4000) ?: error(getString(Res.string.dns_no_response, server)))
        if (!response.truncated) return response
        return tcpDnsQuery(server, port, payload, 4000)?.let(DnsMessage::parse) ?: response
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
