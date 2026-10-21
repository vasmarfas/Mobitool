package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.CertTrust
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class SiteCheckTest {
    private val world = List(4) { WorldProbe("Helsinki, FI, Hetzner", true, 200, 70, "") }

    private val working = SiteFacts(
        host = "example.com",
        local = true,
        tls = true,
        system = listOf("93.184.215.14"),
        cloudflare = DnsView(listOf("93.184.215.14"), false),
        google = DnsView(listOf("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c"), false),
        connect = mapOf("93.184.215.14" to 40L),
        handshake = TlsView("TLSv1.3", CertTrust.TRUSTED),
        http = HttpView(200, 120),
        world = world,
    )

    @Test
    fun workingSite() {
        assertEquals(Res.string.site_summary_ok, working.summary()?.headline)
        assertNull(working.copy(world = null).summary())
    }

    @Test
    fun missingDomain() {
        val facts = SiteFacts("exampel.invalid", local = true, tls = true, system = emptyList(), cloudflare = DnsView(emptyList(), true), google = DnsView(emptyList(), true), world = emptyList())
        assertEquals(listOf(SiteStep.DNS, SiteStep.WORLD), facts.visibleSteps())
        assertEquals(Res.string.site_summary_no_domain, facts.summary()?.headline)
        assertEquals(Res.string.site_dns_nx, facts.summary()?.finding?.text)
    }

    @Test
    fun blockedOnlyHere() {
        val stub = working.copy(system = listOf("127.0.0.1"))
        assertEquals(Res.string.site_dns_stub, stub.finding(SiteStep.DNS)?.text)
        assertEquals(Res.string.site_summary_here, stub.summary()?.headline)
        val sni = working.copy(handshake = TlsView(error = "timeout", otherName = true))
        assertEquals(Res.string.site_tls_sni, sni.summary()?.finding?.text)
        val local = working.copy(system = emptyList())
        assertEquals(Res.string.site_dns_local_fail, local.summary()?.finding?.text)
        assertEquals(Verdict.OK, working.copy(system = listOf("104.16.1.1")).finding(SiteStep.DNS)?.verdict)
    }

    @Test
    fun downEverywhere() {
        val down = working.copy(
            connect = mapOf("93.184.215.14" to null),
            handshake = TlsView(skipped = true),
            http = HttpView(error = "timeout"),
            world = List(6) { WorldProbe("x", false, null, null, "connect ETIMEDOUT") },
        )
        assertEquals(Res.string.site_summary_down, down.summary()?.headline)
        assertEquals(Res.string.site_connect_fail, down.summary()?.finding?.text)
        val serverError = working.copy(http = HttpView(502, 300))
        assertEquals(Res.string.site_http_server, serverError.summary()?.finding?.text)
        assertEquals(Res.string.site_world_unavailable, working.copy(world = null, worldFailed = true).finding(SiteStep.WORLD)?.text)
    }

    @Test
    fun helpers() {
        assertTrue(SiteFacts.isStub("0.0.0.0"))
        assertTrue(SiteFacts.isStub("10.1.2.3"))
        assertFalse(SiteFacts.isStub("93.184.215.14"))
        assertTrue(SiteFacts.sameSite("example.com", "www.example.com"))
        assertFalse(SiteFacts.sameSite("example.com", "blocked.provider.ru"))
        assertTrue("\"country\":\"RU\"" in Globalping.request("example.com", "RU"))
        val body = """
            {"status":"finished","results":[
              {"probe":{"country":"FI","city":"Helsinki","network":"Hetzner Online"},"result":{"status":"finished","statusCode":200,"timings":{"total":69}}},
              {"probe":{"country":"DE","city":"Falkenstein","network":"Hetzner Online"},"result":{"status":"failed","rawOutput":"queryA ENOTFOUND x.com\nmore","timings":{"total":null}}}
            ]}
        """.trimIndent()
        val probes = Globalping.results(Net.json.parseToJsonElement(body).jsonObject)!!
        assertTrue(probes[0].ok)
        assertEquals(69, probes[0].ms)
        assertEquals("Helsinki, FI, Hetzner Online", probes[0].place)
        assertFalse(probes[1].ok)
        assertEquals("queryA ENOTFOUND x.com", probes[1].error)
        assertNull(Globalping.results(Net.json.parseToJsonElement("""{"status":"in-progress","results":[]}""").jsonObject))
    }
}
