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

    // the examples of RFC 7208 section 7.4
    @Test
    fun macrosFollowTheRfcExamples() {
        val sender = "strong-bad@email.example.com"
        fun expand(spec: String) = Spf.expand(spec, "192.0.2.3", "email.example.com", sender)
        assertEquals("strong-bad@email.example.com", expand("%{s}"))
        assertEquals("email.example.com", expand("%{o}"))
        assertEquals("email.example.com", expand("%{d}"))
        assertEquals("email.example.com", expand("%{d4}"))
        assertEquals("email.example.com", expand("%{d3}"))
        assertEquals("example.com", expand("%{d2}"))
        assertEquals("com", expand("%{d1}"))
        assertEquals("com.example.email", expand("%{dr}"))
        assertEquals("example.email", expand("%{d2r}"))
        assertEquals("strong-bad", expand("%{l}"))
        assertEquals("strong.bad", expand("%{l-}"))
        assertEquals("strong-bad", expand("%{lr}"))
        assertEquals("bad.strong", expand("%{lr-}"))
        assertEquals("strong", expand("%{l1r-}"))
        assertEquals("3.2.0.192.in-addr._spf.example.com", Spf.expand("%{ir}.%{v}._spf.%{d2}", "192.0.2.3", "email.example.com", sender))
        assertEquals("bad.strong.lp._spf.example.com", expand("%{lr-}.lp._spf.%{d2}"))
        assertEquals("bad.strong.lp.3.2.0.192.in-addr._spf.example.com", expand("%{lr-}.lp.%{ir}.%{v}._spf.%{d2}"))
        assertEquals(
            "1.0.b.c.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6._spf.example.com",
            Spf.expand("%{ir}.%{v}._spf.%{d2}", "2001:db8::cb01", "email.example.com", sender),
        )
    }

    @Test
    fun longExpansionLosesLabelsFromTheLeft() {
        val label = "a".repeat(60)
        val name = Spf.expand("$label.$label.$label.$label.$label.%{d}", "192.0.2.1", "example.com")
        assertEquals("$label.$label.$label.example.com", name)
        assertTrue(name.length <= 253)
    }

    @Test
    fun macroTargetKeepsItsCidrAndDelimiters() {
        val term = Spf.parse("v=spf1 a:%{l/}.example.com/24//64").single()
        assertEquals("%{l/}.example.com", term.target)
        assertEquals("/24//64", term.cidr)
    }

    private val macroZone = mapOf(
        "example.com" to 16 to listOf("v=spf1 include:%{l}._spf.%{d} a:%{ir}.hosts.%{d} -all"),
        "ann._spf.example.com" to 16 to listOf("v=spf1 ip4:198.51.100.0/24 -all"),
        "postmaster._spf.example.com" to 16 to listOf("v=spf1 -all"),
        "bob._spf.example.com" to 16 to listOf("v=spf1 -all"),
        "7.113.0.203.hosts.example.com" to 1 to listOf("203.0.113.7"),
    )

    private val zone: suspend (String, Int) -> List<String> = { name, type -> macroZone[name to type].orEmpty() }

    @Test
    fun includeAndAWithMacrosResolvePerSenderAndAddress() = runTest {
        val check = Spf.check("example.com", query = zone)
        assertTrue(check.hasLookupMacros)
        assertEquals("postmaster._spf.example.com", check.root.terms[0].resolved)
        assertEquals("", check.root.terms[1].resolved)
        assertEquals("pass", Spf.evaluate(check, "198.51.100.9", "ann@example.com", zone).result)
        assertEquals("fail", Spf.evaluate(check, "198.51.100.9", "bob@example.com", zone).result)
        assertEquals("fail", Spf.evaluate(check, "198.51.100.9", query = zone).result)
        assertEquals("pass", Spf.evaluate(check, "203.0.113.7", query = zone).result)
        assertEquals("permerror", Spf.evaluate(check, "198.51.100.9", "nobody@example.com", zone).result)
    }

    @Test
    fun ptrIsReportedAsNotChecked() {
        assertTrue(SpfCheck(node("example.com", "v=spf1 ptr -all"), 1, 0, emptyList()).usesPtr)
        assertFalse(check.usesPtr)
        assertFalse(check.hasLookupMacros)
    }
}
