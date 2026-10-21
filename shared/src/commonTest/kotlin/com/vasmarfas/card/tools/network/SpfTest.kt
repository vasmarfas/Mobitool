package com.vasmarfas.card.tools.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpfTest {
    private fun node(domain: String, record: String, children: Map<String, SpfNode> = emptyMap(), addresses: Map<String, List<String>> = emptyMap()) =
        SpfNode(domain, listOf(record), Spf.parse(record).map { term -> term.copy(child = children[term.raw], addresses = addresses[term.raw].orEmpty()) })

    private val google = node(
        "_spf.google.com",
        "v=spf1 include:_netblocks.google.com include:_netblocks2.google.com ~all",
        mapOf(
            "include:_netblocks.google.com" to node("_netblocks.google.com", "v=spf1 ip4:209.85.128.0/17 ip4:35.190.247.0/24 ~all"),
            "include:_netblocks2.google.com" to node("_netblocks2.google.com", "v=spf1 ip6:2001:4860:4000::/36 ~all"),
        ),
    )

    private val check = SpfCheck(
        node(
            "example.com",
            "v=spf1 mx a:relay.example.com/30 -ip4:203.0.113.66 include:_spf.google.com ?ip4:198.51.100.0/24 -all",
            mapOf("include:_spf.google.com" to google),
            mapOf("mx" to listOf("203.0.113.10", "2001:db8::25"), "a:relay.example.com/30" to listOf("203.0.113.64/30")),
        ),
        lookups = 5,
        voids = 0,
        issues = emptyList(),
    )

    @Test
    fun termsKeepQualifiersValuesAndModifiers() {
        val terms = Spf.parse("v=spf1 -ip6:2001:db8::/32 ~a/24//64 Include:_spf.example.com redirect=_spf.example.net exp=explain.%{d}")
        assertEquals(listOf('-', '~', '+', '+', '+'), terms.map { it.qualifier })
        assertEquals(listOf("ip6", "a", "include", "redirect", "exp"), terms.map { it.name })
        assertEquals(listOf("2001:db8::/32", "/24//64", "_spf.example.com", "_spf.example.net", "explain.%{d}"), terms.map { it.value })
        assertEquals(listOf(false, false, false, true, true), terms.map { it.modifier })
        assertEquals("", terms[1].target)
        assertEquals("/24//64", terms[1].cidr)
        assertTrue(Spf.isRecord("V=SPF1 -all"))
        assertFalse(Spf.isRecord("v=spf10 -all"))
    }

    @Test
    fun subnetsOfBothFamilies() {
        assertTrue(Spf.contains("209.85.128.0/17", "209.85.220.41"))
        assertFalse(Spf.contains("209.85.128.0/17", "209.86.0.1"))
        assertTrue(Spf.contains("203.0.113.10", "203.0.113.10"))
        assertTrue(Spf.contains("2001:4860:4000::/36", "2001:4860:4864:20::2b"))
        assertFalse(Spf.contains("2001:4860:4000::/36", "209.85.220.41"))
        assertFalse(Spf.contains("209.85.128.0/17", "2001:4860:4864:20::2b"))
    }

    @Test
    fun firstMatchingMechanismDecides() = runTest {
        assertEquals("pass", Spf.evaluate(check, "203.0.113.10").result)
        assertEquals("pass", Spf.evaluate(check, "203.0.113.66").result)
        val google = Spf.evaluate(check, "209.85.220.41")
        assertEquals("pass", google.result)
        assertEquals("ip4:209.85.128.0/17", google.term?.raw)
        assertEquals("_netblocks.google.com", google.domain)
        assertEquals("neutral", Spf.evaluate(check, "198.51.100.9").result)
        assertEquals("fail", Spf.evaluate(check, "192.0.2.1").result)
        assertEquals("permerror", Spf.evaluate(SpfCheck(check.root, 11, 0, emptyList()), "203.0.113.10").result)
    }

    @Test
    fun rangesFollowIncludesAndSkipFailingTerms() {
        assertEquals(
            listOf("203.0.113.10", "2001:db8::25", "203.0.113.64/30", "209.85.128.0/17", "35.190.247.0/24", "2001:4860:4000::/36"),
            check.ranges,
        )
    }

    @Test
    fun macrosExpandForBothFamilies() {
        assertEquals("1.2.0.192._spf.example.com", Spf.expand("%{ir}._spf.%{d}", "192.0.2.1", "example.com"))
        assertEquals("192.0.2.1.in-addr.example.com", Spf.expand("%{i}.%{v}.%{d2}", "192.0.2.1", "mail.example.com"))
        assertEquals("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.x", Spf.expand("%{ir}.%{v}.x", "2001:db8::1", "x"))
        assertEquals("postmaster.example.com%", Spf.expand("%{l}.%{o}%%", "192.0.2.1", "example.com"))
    }
}
