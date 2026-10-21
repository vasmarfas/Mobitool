package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.CertTrust
import com.vasmarfas.card.resources.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.StringResource

enum class SiteStep(val title: StringResource) {
    DNS(Res.string.site_step_dns),
    CONNECT(Res.string.site_step_connect),
    TLS(Res.string.site_step_tls),
    HTTP(Res.string.site_step_http),
    WORLD(Res.string.site_step_world),
}

class DnsView(val addresses: List<String>, val nxdomain: Boolean)

class TlsView(val protocol: String = "", val trust: CertTrust? = null, val error: String? = null, val otherName: Boolean? = null, val skipped: Boolean = false)

class HttpView(val status: Int? = null, val ms: Long? = null, val server: String? = null, val error: String? = null, val redirect: String? = null)

class WorldProbe(val place: String, val ok: Boolean, val status: Int?, val ms: Int?, val error: String)

class SiteSummary(val headline: StringResource, val finding: Finding)

data class SiteFacts(
    val host: String,
    val local: Boolean,
    val tls: Boolean,
    val system: List<String>? = null,
    val cloudflare: DnsView? = null,
    val google: DnsView? = null,
    val connect: Map<String, Long?>? = null,
    val handshake: TlsView? = null,
    val http: HttpView? = null,
    val world: List<WorldProbe>? = null,
    val worldFailed: Boolean = false,
) {
    val publicAddresses: List<String> get() = (cloudflare?.addresses.orEmpty() + google?.addresses.orEmpty()).distinct()

    fun visibleSteps(): List<SiteStep> = SiteStep.entries.filter {
        when (it) {
            SiteStep.CONNECT -> local && dnsResolved != false
            SiteStep.TLS -> tls && dnsResolved != false
            SiteStep.HTTP -> dnsResolved != false
            else -> true
        }
    }

    private val dnsResolved: Boolean?
        get() = if (cloudflare == null || google == null || (local && system == null)) null else publicAddresses.isNotEmpty() || !system.isNullOrEmpty()

    val done: Boolean get() = visibleSteps().all { finding(it) != null }

    fun finding(step: SiteStep): Finding? = when (step) {
        SiteStep.DNS -> dns()
        SiteStep.CONNECT -> connect()
        SiteStep.TLS -> tlsFinding()
        SiteStep.HTTP -> httpFinding()
        SiteStep.WORLD -> worldFinding()
    }

    private fun dns(): Finding? {
        val cloudflare = cloudflare ?: return null
        val google = google ?: return null
        if (local && system == null) return null
        val public = publicAddresses
        val own = system.orEmpty()
        return when {
            public.isEmpty() && own.isEmpty() && (cloudflare.nxdomain || google.nxdomain) -> Finding(Verdict.FAIL, Res.string.site_dns_nx, host)
            public.isEmpty() && own.isEmpty() -> Finding(Verdict.FAIL, Res.string.site_dns_empty, host)
            local && own.isEmpty() -> Finding(Verdict.FAIL, Res.string.site_dns_local_fail, public.take(3).joinToString(", "))
            own.isNotEmpty() && public.isNotEmpty() && own.none { it in public } && own.any(::isStub) ->
                Finding(Verdict.FAIL, Res.string.site_dns_stub, own.joinToString(", "), public.take(3).joinToString(", "))
            else -> Finding(Verdict.OK, Res.string.site_dns_ok, (own.ifEmpty { public }).take(4).joinToString(", "))
        }
    }

    private fun connect(): Finding? {
        val results = connect ?: return null
        val good = results.values.filterNotNull()
        return when {
            results.isEmpty() || good.isEmpty() -> Finding(Verdict.FAIL, Res.string.site_connect_fail, results.keys.joinToString(", "))
            good.size < results.size -> Finding(Verdict.WARN, Res.string.site_connect_partial, results.filterValues { it == null }.keys.joinToString(", "))
            else -> Finding(Verdict.OK, Res.string.site_connect_ok, good.min().toString())
        }
    }

    private fun tlsFinding(): Finding? {
        val view = handshake ?: return null
        return when {
            view.skipped -> Finding(Verdict.INFO, Res.string.site_tls_skipped)
            view.error != null && view.otherName == true -> Finding(Verdict.FAIL, Res.string.site_tls_sni)
            view.error != null -> Finding(Verdict.FAIL, Res.string.site_tls_fail, view.error)
            view.trust == CertTrust.EXPIRED -> Finding(Verdict.FAIL, Res.string.site_tls_expired)
            view.trust == CertTrust.NOT_YET_VALID -> Finding(Verdict.FAIL, Res.string.site_tls_not_yet)
            view.trust == CertTrust.UNTRUSTED -> Finding(Verdict.FAIL, Res.string.site_tls_untrusted)
            else -> Finding(Verdict.OK, Res.string.site_tls_ok, view.protocol)
        }
    }

    private fun httpFinding(): Finding? {
        val view = http ?: return null
        val status = view.status
        return when {
            view.redirect != null -> Finding(Verdict.WARN, Res.string.site_http_redirect, view.redirect)
            view.error != null -> Finding(Verdict.FAIL, Res.string.site_http_fail, view.error)
            status == null -> Finding(Verdict.OK, Res.string.site_http_reachable)
            status == 451 -> Finding(Verdict.FAIL, Res.string.site_http_451)
            status == 401 || status == 403 -> Finding(Verdict.WARN, Res.string.site_http_forbidden, status.toString())
            status >= 500 -> Finding(Verdict.FAIL, Res.string.site_http_server, status.toString())
            status >= 400 -> Finding(Verdict.WARN, Res.string.site_http_status, status.toString())
            else -> Finding(Verdict.OK, Res.string.site_http_ok, status.toString(), (view.ms ?: 0).toString())
        }
    }

    private fun worldFinding(): Finding? {
        if (worldFailed) return Finding(Verdict.INFO, Res.string.site_world_unavailable)
        val probes = world ?: return null
        val ok = probes.count { it.ok }
        return when {
            probes.isEmpty() -> Finding(Verdict.INFO, Res.string.site_world_unavailable)
            ok == probes.size -> Finding(Verdict.OK, Res.string.site_world_ok, ok.toString(), probes.size.toString())
            ok == 0 -> Finding(Verdict.FAIL, Res.string.site_world_down, probes.size.toString())
            else -> Finding(Verdict.WARN, Res.string.site_world_partial, ok.toString(), probes.size.toString())
        }
    }

    fun summary(): SiteSummary? {
        if (!done) return null
        val world = worldFinding()
        val local = listOf(SiteStep.DNS, SiteStep.CONNECT, SiteStep.TLS, SiteStep.HTTP).filter { it in visibleSteps() }.mapNotNull { finding(it) }
        val failure = local.firstOrNull { it.verdict == Verdict.FAIL }
        val warning = local.firstOrNull { it.verdict == Verdict.WARN }
        return when {
            failure?.text == Res.string.site_dns_nx || failure?.text == Res.string.site_dns_empty -> SiteSummary(Res.string.site_summary_no_domain, failure)
            world?.text == Res.string.site_world_down -> SiteSummary(Res.string.site_summary_down, failure ?: world)
            failure != null && world?.verdict == Verdict.OK -> SiteSummary(Res.string.site_summary_here, failure)
            failure != null -> SiteSummary(Res.string.conn_summary_fail, failure)
            warning != null -> SiteSummary(Res.string.site_summary_warn, warning)
            else -> SiteSummary(Res.string.site_summary_ok, Finding(Verdict.OK, Res.string.site_summary_ok))
        }
    }

    companion object {
        fun isStub(address: String): Boolean = address == "::" || address == "::1" || Ipv4.parse(address)?.let { !Ipv4Subnet(it, 32).isPublic } == true

        fun sameSite(host: String, other: String): Boolean {
            fun base(name: String) = name.lowercase().removePrefix("www.").split('.').takeLast(2).joinToString(".")
            return base(host) == base(other)
        }
    }
}

object Globalping {
    const val API = "https://api.globalping.io/v1/measurements"

    fun request(host: String, country: String?): String = buildJsonObject {
        put("type", "http")
        put("target", host)
        putJsonArray("locations") {
            add(buildJsonObject { put("magic", "world"); put("limit", 4) })
            if (country != null) add(buildJsonObject { put("country", country); put("limit", 2) })
        }
        putJsonObject("measurementOptions") {
            put("protocol", "HTTPS")
            putJsonObject("request") {
                put("method", "HEAD")
                put("path", "/")
            }
        }
    }.toString()

    fun results(body: JsonObject): List<WorldProbe>? {
        if ((body["status"] as? JsonPrimitive)?.content != "finished") return null
        return (body["results"] as? JsonArray).orEmpty().mapNotNull { item ->
            val probe = (item as? JsonObject)?.get("probe") as? JsonObject ?: return@mapNotNull null
            val result = item["result"] as? JsonObject ?: return@mapNotNull null
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
            val status = result.text("statusCode").toIntOrNull()
            WorldProbe(
                place = listOf(probe.text("city"), probe.text("country"), probe.text("network")).filter { it.isNotEmpty() }.joinToString(", "),
                ok = result.text("status") == "finished" && status != null && status < 500,
                status = status,
                ms = ((result["timings"] as? JsonObject)?.text("total"))?.toDoubleOrNull()?.toInt(),
                error = result.text("rawOutput").lineSequence().firstOrNull().orEmpty().take(120),
            )
        }
    }
}
