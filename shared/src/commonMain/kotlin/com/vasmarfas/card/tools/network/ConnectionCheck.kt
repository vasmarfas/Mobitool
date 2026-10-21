package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.InterfaceInfo
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.resources.*
import kotlin.math.abs
import org.jetbrains.compose.resources.StringResource

enum class Verdict { OK, INFO, WARN, FAIL }

class Finding(val verdict: Verdict, val text: StringResource, vararg val args: String)

enum class CheckStep(val title: StringResource) {
    NETWORK(Res.string.conn_step_network),
    WIFI(Res.string.conn_step_wifi),
    GATEWAY(Res.string.conn_step_gateway),
    INTERNET(Res.string.conn_step_internet),
    DNS(Res.string.conn_step_dns),
    PORTAL(Res.string.conn_step_portal),
    HTTPS(Res.string.conn_step_https),
    CLOCK(Res.string.conn_step_clock),
    IPV6(Res.string.conn_step_ipv6),
    VPN(Res.string.conn_step_vpn),
}

data class PingStats(val target: String, val sent: Int, val times: List<Double>) {
    val received: Int get() = times.size
    val loss: Int get() = if (sent == 0) 100 else (sent - times.size) * 100 / sent
    val average: Double get() = if (times.isEmpty()) 0.0 else times.average()
}

enum class Portal { NONE, FOUND, UNKNOWN }

data class Trace(val ip: String = "", val country: String = "", val warp: Boolean = false, val error: String? = null)

data class ConnectionFacts(
    val local: Boolean,
    val interfaces: List<InterfaceInfo>? = null,
    val link: Map<String, String>? = null,
    val gateway: PingStats? = null,
    val router: String? = null,
    val internet: PingStats? = null,
    val systemDns: List<String>? = null,
    val doh: List<String>? = null,
    val portal: Portal? = null,
    val https: Trace? = null,
    val clockSkew: Long? = null,
    val ipv6: String? = null,
) {
    val gatewayAddress: String? get() = link?.get("Gateway")?.let { if (it == "0.0.0.0" || it == "::") "" else it }

    val vpn: String?
        get() = interfaces.orEmpty()
            .filter { nif -> nif.isUp && nif.addresses.any { '.' in it } && (vpnName.matches(nif.name) || vpnLabels.any { it in nif.displayName.lowercase() }) }
            .joinToString(", ") { it.name }
            .ifEmpty { if (link?.get("Transport") == "VPN") "VPN" else if (https?.warp == true) "Cloudflare WARP" else null }

    val done: Boolean
        get() = visibleSteps().none { finding(it) == null }

    fun visibleSteps(): List<CheckStep> = CheckStep.entries.filter { step ->
        when (step) {
            CheckStep.NETWORK, CheckStep.PORTAL -> local
            CheckStep.WIFI -> link?.get("RSSI")?.toIntOrNull()?.takeIf { it > -127 } != null
            CheckStep.GATEWAY -> local && gatewayAddress != "" && (link == null || gatewayAddress != null)
            CheckStep.CLOCK -> clockSkew != null || https == null || (local && portal == null)
            CheckStep.VPN -> local || https?.warp == true
            else -> true
        }
    }

    fun finding(step: CheckStep): Finding? = when (step) {
        CheckStep.NETWORK -> network()
        CheckStep.WIFI -> wifi()
        CheckStep.GATEWAY -> gateway()
        CheckStep.INTERNET -> internet()
        CheckStep.DNS -> dns()
        CheckStep.PORTAL -> portal()
        CheckStep.HTTPS -> https()
        CheckStep.CLOCK -> clock()
        CheckStep.IPV6 -> ipv6()
        CheckStep.VPN -> vpnFinding()
    }

    fun summary(): Finding? {
        if (!done) return null
        val findings = listOf(
            CheckStep.NETWORK, CheckStep.PORTAL, CheckStep.GATEWAY, CheckStep.INTERNET, CheckStep.DNS,
            CheckStep.CLOCK, CheckStep.HTTPS, CheckStep.IPV6, CheckStep.WIFI, CheckStep.VPN,
        ).filter { it in visibleSteps() }.mapNotNull { finding(it) }
        return findings.firstOrNull { it.verdict == Verdict.FAIL }
            ?: findings.firstOrNull { it.verdict == Verdict.WARN }
            ?: Finding(Verdict.OK, Res.string.conn_summary_ok)
    }

    private fun network(): Finding? {
        val all = interfaces ?: return null
        link ?: return null
        val addresses = all.filter { it.isUp && !it.isLoopback }.flatMap { it.addresses }.map { it.substringBefore('/') }
        val usable = addresses.filter { !it.startsWith("169.254.") && !it.lowercase().startsWith("fe80") && it != "::1" }
        val gateway = gatewayAddress
        return when {
            usable.isEmpty() && addresses.any { it.startsWith("169.254.") } -> Finding(Verdict.FAIL, Res.string.conn_no_dhcp)
            usable.isEmpty() -> Finding(Verdict.FAIL, Res.string.conn_no_address)
            gateway == "" && link["Transport"] != "Cellular" && link["Transport"] != "VPN" -> Finding(Verdict.FAIL, Res.string.conn_no_route)
            else -> {
                val v4 = usable.filter { '.' in it }
                val prefix = gateway?.substringBeforeLast('.', "")
                val own = v4.firstOrNull { !prefix.isNullOrEmpty() && it.startsWith("$prefix.") } ?: v4.firstOrNull() ?: usable.first()
                Finding(Verdict.OK, Res.string.conn_network_ok, own)
            }
        }
    }

    private fun wifi(): Finding? {
        val rssi = link?.get("RSSI")?.toIntOrNull() ?: return null
        return when {
            rssi >= -67 -> Finding(Verdict.OK, Res.string.conn_wifi_good, rssi.toString())
            rssi >= -75 -> Finding(Verdict.INFO, Res.string.conn_wifi_fair, rssi.toString())
            else -> Finding(Verdict.WARN, Res.string.conn_wifi_weak, rssi.toString())
        }
    }

    private fun gateway(): Finding? {
        val address = gatewayAddress ?: return null
        val stats = gateway ?: return null
        return when {
            stats.received == 0 -> when {
                internet == null -> null
                internet.received > 0 -> Finding(Verdict.INFO, Res.string.conn_gateway_silent, address)
                else -> Finding(Verdict.FAIL, Res.string.conn_gateway_down, address)
            }
            stats.loss >= 10 || stats.average > 50 ->
                Finding(Verdict.WARN, Res.string.conn_gateway_unstable, address, stats.average.fmt(1), stats.loss.toString())
            else -> Finding(Verdict.OK, Res.string.conn_gateway_ok, address, stats.average.fmt(1))
        }
    }

    private fun internet(): Finding? {
        val stats = internet ?: return null
        return when {
            stats.received == 0 -> Finding(Verdict.FAIL, Res.string.conn_internet_down, stats.target)
            stats.loss >= 10 || stats.average > 150 ->
                Finding(Verdict.WARN, Res.string.conn_internet_unstable, stats.target, stats.average.fmt(0), stats.loss.toString())
            else -> Finding(Verdict.OK, Res.string.conn_internet_ok, stats.target, stats.average.fmt(0))
        }
    }

    private fun dns(): Finding? {
        if (!local) {
            val doh = doh ?: return null
            return if (doh.isNotEmpty()) Finding(Verdict.OK, Res.string.conn_dns_doh_ok) else Finding(Verdict.FAIL, Res.string.conn_dns_none)
        }
        val system = systemDns ?: return null
        val v4 = system.filter { '.' in it }
        return when {
            v4.isNotEmpty() && v4.none { it in cloudflareAddresses } -> Finding(Verdict.WARN, Res.string.conn_dns_rewritten, v4.joinToString(", "))
            system.isNotEmpty() -> link?.get("DNS")?.takeIf { it.isNotBlank() }
                ?.let { Finding(Verdict.OK, Res.string.conn_dns_ok_servers, it) } ?: Finding(Verdict.OK, Res.string.conn_dns_ok)
            doh == null -> null
            doh.isNotEmpty() -> Finding(Verdict.FAIL, Res.string.conn_dns_broken)
            else -> Finding(Verdict.FAIL, Res.string.conn_dns_none)
        }
    }

    private fun portal(): Finding? = when {
        link?.get("Captive portal") == "true" -> Finding(Verdict.FAIL, Res.string.conn_portal_found)
        portal == null -> null
        portal == Portal.FOUND -> Finding(Verdict.FAIL, Res.string.conn_portal_found)
        portal == Portal.NONE -> Finding(Verdict.OK, Res.string.conn_portal_none)
        else -> Finding(Verdict.INFO, Res.string.conn_portal_unknown)
    }

    private fun https(): Finding? {
        val trace = https ?: return null
        return when {
            trace.error != null -> Finding(Verdict.FAIL, Res.string.conn_https_fail, trace.error)
            trace.country.isEmpty() -> Finding(Verdict.OK, Res.string.conn_https_ok_ip, trace.ip)
            else -> Finding(Verdict.OK, Res.string.conn_https_ok, trace.ip, trace.country)
        }
    }

    private fun clock(): Finding? {
        val skew = abs(clockSkew ?: return null)
        return when {
            skew <= 2 -> Finding(Verdict.OK, Res.string.conn_clock_ok)
            skew < 120 -> Finding(Verdict.INFO, Res.string.conn_clock_seconds, skew.toString())
            skew < 2 * 86_400 -> Finding(Verdict.WARN, Res.string.conn_clock_minutes, (skew / 60).toString())
            else -> Finding(Verdict.FAIL, Res.string.conn_clock_days, (skew / 86_400).toString())
        }
    }

    private fun ipv6(): Finding? {
        val address = ipv6 ?: return null
        val assigned = interfaces.orEmpty().filter { it.isUp }.flatMap { it.addresses }.map { it.substringBefore('/') }
            .any { ':' in it && (it.startsWith('2') || it.startsWith('3')) }
        return when {
            address.isNotEmpty() -> Finding(Verdict.OK, Res.string.conn_ipv6_ok, address)
            assigned -> Finding(Verdict.WARN, Res.string.conn_ipv6_broken)
            else -> Finding(Verdict.INFO, Res.string.conn_ipv6_none)
        }
    }

    private fun vpnFinding(): Finding? {
        if (local && interfaces == null) return null
        return vpn?.let { Finding(Verdict.INFO, Res.string.conn_vpn_on, it) } ?: Finding(Verdict.OK, Res.string.conn_vpn_off)
    }

    companion object {
        private val vpnName = Regex("(tun|tap|wg|ppp|ipsec|utun|nordlynx)\\d*", RegexOption.IGNORE_CASE)
        private val vpnLabels = listOf("vpn", "wireguard", "wintun", "tap-windows", "openvpn", "anyconnect")
        private val cloudflareAddresses = setOf("1.1.1.1", "1.0.0.1")
    }
}

object ConnectionProbe {
    const val CHECK_HOST = "one.one.one.one"
    const val PORTAL_URL = "http://connectivitycheck.gstatic.com/generate_204"

    fun plainRequest(url: String, headers: List<String> = emptyList()): Triple<String, Int, ByteArray> {
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "80").toIntOrNull() ?: 80
        val path = "/" + rest.substringAfter('/', "")
        val extra = headers.joinToString("") { "$it\r\n" }
        return Triple(host, port, "GET $path HTTP/1.0\r\nHost: $authority\r\n${extra}Connection: close\r\n\r\n".encodeToByteArray())
    }

    fun status(response: String): Int? = response.substringBefore('\n').split(' ').getOrNull(1)?.toIntOrNull()

    fun header(response: String, name: String): String? =
        response.substringBefore("\r\n\r\n").lines().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    fun body(response: String): String = response.substringAfter("\r\n\r\n", "")

    fun trace(text: String): Map<String, String> =
        text.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}
