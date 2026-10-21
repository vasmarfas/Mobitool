package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.XmlElement
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

object Stun {
    private const val COOKIE = 0x2112A442

    fun request(transaction: ByteArray): ByteArray =
        byteArrayOf(0, 1, 0, 0) + byteArrayOf((COOKIE ushr 24).toByte(), (COOKIE ushr 16).toByte(), (COOKIE ushr 8).toByte(), COOKIE.toByte()) + transaction

    fun mappedAddress(response: ByteArray): String? {
        if (response.size < 20 || response[0].toInt() != 1 || response[1].toInt() != 1) return null
        fun u8(at: Int) = response[at].toInt() and 0xFF
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        var at = 20
        var plain: String? = null
        while (at + 4 <= response.size) {
            val type = u16(at)
            val length = u16(at + 2)
            val value = at + 4
            if (value + length > response.size) break
            if (length >= 8 && u8(value + 1) == 1) {
                val port = u16(value + 2)
                val address = (0 until 4).map { u8(value + 4 + it) }
                when (type) {
                    0x0020 -> {
                        val cookie = listOf(0x21, 0x12, 0xA4, 0x42)
                        return address.zip(cookie) { a, c -> a xor c }.joinToString(".") + ":" + (port xor 0x2112)
                    }
                    0x0001 -> plain = address.joinToString(".") + ":$port"
                }
            }
            at = value + (length + 3) / 4 * 4
        }
        return plain
    }
}

object NatPmp {
    val request = byteArrayOf(0, 0)

    fun externalAddress(response: ByteArray?): String? {
        if (response == null || response.size < 12 || response[1].toInt() and 0xFF != 128 || response[2].toInt() != 0 || response[3].toInt() != 0) return null
        return (8 until 12).joinToString(".") { (response[it].toInt() and 0xFF).toString() }
    }

    // opcode 2 maps a TCP port for lifetime seconds; lifetime 0 with external port 0 removes the mapping
    fun mapRequest(internal: Int, external: Int, lifetime: Int): ByteArray = byteArrayOf(
        0, 2, 0, 0,
        (internal shr 8).toByte(), internal.toByte(), (external shr 8).toByte(), external.toByte(),
        (lifetime shr 24).toByte(), (lifetime shr 16).toByte(), (lifetime shr 8).toByte(), lifetime.toByte(),
    )

    fun mappedPort(response: ByteArray?): Int? {
        if (response == null || response.size < 16 || response[1].toInt() and 0xFF != 130 || response[2].toInt() != 0 || response[3].toInt() != 0) return null
        return ((response[10].toInt() and 0xFF) shl 8) or (response[11].toInt() and 0xFF)
    }
}

object Upnp {
    private val wanTypes = listOf("WANIPConnection", "WANPPPConnection")

    private fun XmlElement.all(): Sequence<XmlElement> = sequenceOf(this) + elements.asSequence().flatMap { it.all() }

    fun wanService(description: XmlElement): Pair<String, String>? = description.all()
        .filter { it.localName == "service" }
        .firstNotNullOfOrNull { service ->
            val type = service.child("serviceType")?.text()?.trim().orEmpty()
            val control = service.child("controlURL")?.text()?.trim().orEmpty()
            if (wanTypes.any { it in type } && control.isNotEmpty()) type to control else null
        }

    fun controlUrl(location: String, control: String): String = when {
        control.startsWith("http") -> control
        else -> location.substringBefore("://") + "://" + location.substringAfter("://").substringBefore('/') + "/" + control.trimStart('/')
    }

    fun request(url: String, serviceType: String, action: String, arguments: List<Pair<String, String>> = emptyList()): ByteArray {
        val values = arguments.joinToString("") { (name, value) -> "<$name>$value</$name>" }
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$serviceType">$values</u:$action></s:Body></s:Envelope>"""
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val payload = body.encodeToByteArray()
        val head = "POST $path HTTP/1.0\r\nHost: $authority\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n" +
            "SOAPAction: \"$serviceType#$action\"\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        return head.encodeToByteArray() + payload
    }

    fun externalIp(response: String): String? =
        Regex("<NewExternalIPAddress>\\s*([^<\\s]+)\\s*</NewExternalIPAddress>").find(response)?.groupValues?.get(1)
}

enum class NatKind { NONE, CONE, SYMMETRIC }

enum class IpClass(val title: StringResource) {
    WHITE(Res.string.nat_white),
    WHITE_LIKELY(Res.string.nat_white_likely),
    GRAY(Res.string.nat_gray),
    GRAY_LIKELY(Res.string.nat_gray_likely),
    UNKNOWN(Res.string.nat_unknown),
}

enum class IpSource(val host: String, val udp: Boolean = false, val domestic: Boolean = false) {
    YANDEX("ipv4-internet.yandex.net", domestic = true),
    BEGET("ip.beget.ru", domestic = true),
    CLOUDFLARE("1.1.1.1"),
    IPIFY("api.ipify.org"),
    STUN_GOOGLE("stun.l.google.com", udp = true),
    STUN_CLOUDFLARE("stun.cloudflare.com", udp = true),
    STUN_SIPNET("stun.sipnet.ru", udp = true, domestic = true),
    STUN_ZADARMA("stun.zadarma.com", udp = true, domestic = true),
    WEBRTC("WebRTC", udp = true),
}

class SeenIp(val source: IpSource, val address: String?) {
    val ip: String? get() = address?.substringBefore(':')
}

data class NatFacts(
    val local: Boolean,
    val http: List<SeenIp>? = null,
    val stun: List<SeenIp>? = null,
    val holders: Map<String, String> = emptyMap(),
    val ipv6: String? = null,
    val addresses: List<String>? = null,
    val gateway: String? = null,
    val router: String? = null,
    val routerSource: String = "",
    val routerDone: Boolean = false,
    val hops: List<String?>? = null,
) {
    private fun address(text: String): Ipv4Subnet? = Ipv4.parse(text.substringBefore(':'))?.let { Ipv4Subnet(it, 32) }

    val seen: List<SeenIp> get() = http.orEmpty() + stun.orEmpty()

    val observed: List<String> get() = seen.mapNotNull { it.ip }.filter { '.' in it }.distinct()

    val directIp: String? get() = seen.firstOrNull { it.source.domestic && it.ip != null }?.ip ?: observed.firstOrNull()

    val natKind: NatKind?
        get() {
            val mapped = stun?.mapNotNull { it.address }?.distinct() ?: return null
            if (mapped.isEmpty()) return null
            val ports = mapped.groupBy { it.substringBefore(':') }
            return when {
                ports.values.any { it.size > 1 } -> NatKind.SYMMETRIC
                addresses.orEmpty().any { it in ports } -> NatKind.NONE
                else -> NatKind.CONE
            }
        }

    val ownDistance: Int? get() = directIp?.let { ip -> hops?.indexOf(ip)?.takeIf { it >= 0 }?.plus(1) }

    val privateHop: Pair<Int, String>?
        get() = hops.orEmpty().withIndex().drop(1)
            .firstOrNull { (_, hop) -> hop != null && address(hop)?.let { it.isPrivate || it.isCgnat } == true }
            ?.let { (index, hop) -> index + 1 to hop!! }

    val ipClass: IpClass
        get() {
            val seen = observed
            if (seen.isEmpty()) return IpClass.UNKNOWN
            val router = router?.let(::address)
            return when {
                addresses.orEmpty().any { it in seen } -> IpClass.WHITE
                addresses.orEmpty().any { address(it)?.isCgnat == true } -> IpClass.GRAY
                router != null && router.isPublic -> IpClass.WHITE
                router != null -> IpClass.GRAY
                ownDistance == 1 -> IpClass.WHITE
                ownDistance != null -> IpClass.GRAY
                privateHop != null -> IpClass.GRAY_LIKELY
                hops != null && hops.drop(1).take(2).any { hop -> hop != null && address(hop)?.isPublic == true } -> IpClass.WHITE_LIKELY
                else -> IpClass.UNKNOWN
            }
        }

    val done: Boolean get() = http != null && stun != null && (!local || (routerDone && hops != null && addresses != null))

    fun pathsFinding(): Finding? {
        if (http == null || stun == null) return null
        val ips = observed
        return when {
            ips.isEmpty() -> Finding(Verdict.FAIL, Res.string.nat_public_failed)
            ips.size == 1 -> Finding(Verdict.OK, Res.string.nat_paths_same, ips.single())
            else -> Finding(Verdict.WARN, Res.string.nat_paths_split, ips.joinToString(", "), directIp.orEmpty())
        }
    }

    fun routerFinding(): Finding? {
        if (!routerDone) return null
        val wan = router ?: return Finding(Verdict.INFO, Res.string.nat_router_silent)
        val subnet = address(wan)
        return when {
            wan in observed -> Finding(Verdict.OK, Res.string.nat_router_same, wan, routerSource)
            subnet?.isCgnat == true -> Finding(Verdict.WARN, Res.string.nat_router_cgnat, wan, directIp.orEmpty())
            subnet?.isPrivate == true -> Finding(Verdict.WARN, Res.string.nat_router_private, wan, directIp.orEmpty())
            observed.isEmpty() -> Finding(Verdict.INFO, Res.string.nat_router_address, wan, routerSource)
            else -> Finding(Verdict.INFO, Res.string.nat_router_other, wan, directIp.orEmpty())
        }
    }

    fun pathFinding(): Finding? {
        val list = hops ?: return null
        val hop = privateHop
        val distance = ownDistance
        return when {
            distance == 1 -> Finding(Verdict.OK, Res.string.nat_path_own, directIp.orEmpty())
            distance != null -> Finding(Verdict.WARN, Res.string.nat_path_beyond, directIp.orEmpty(), distance.toString())
            hop != null -> Finding(Verdict.WARN, Res.string.nat_path_private, hop.first.toString(), hop.second)
            list.all { it == null } -> Finding(Verdict.INFO, Res.string.nat_path_silent)
            else -> Finding(Verdict.OK, Res.string.nat_path_public)
        }
    }

    fun natFinding(): Finding? {
        stun ?: return null
        return when (natKind) {
            null -> Finding(Verdict.INFO, Res.string.nat_type_unknown)
            NatKind.NONE -> Finding(Verdict.OK, Res.string.nat_type_none)
            NatKind.CONE -> Finding(Verdict.OK, Res.string.nat_type_cone)
            NatKind.SYMMETRIC -> Finding(Verdict.WARN, Res.string.nat_type_symmetric)
        }
    }
}
