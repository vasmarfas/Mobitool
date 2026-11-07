package com.vasmarfas.card.tools.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Ipv4RangeTest {
    private fun range(text: String) = Ipv4.parseRange(text)?.let { (first, last) -> "${Ipv4.format(first)}-${Ipv4.format(last)}" }

    private fun cidr(s: Ipv4Subnet) = "${Ipv4.format(s.network)}/${s.prefix}"

    @Test
    fun rangeForms() {
        assertEquals("192.168.1.10-192.168.5.110", range("192.168.1.10-192.168.5.110"))
        assertEquals("192.168.1.10-192.168.1.100", range("192.168.1.10-100"))
        assertEquals("192.168.1.10-192.168.1.100", range(" 192.168.1.10 - 100 "))
        assertEquals("192.168.1.10-192.168.1.100", range("192.168.1.10 - 192.168.1.100"))
        assertEquals("10.0.0.5-10.0.0.5", range("10.0.0.5-5"))
    }

    @Test
    fun subnetFormsGiveTheirWholeNetwork() {
        assertEquals("192.168.1.0-192.168.1.255", range("192.168.1.77/24"))
        assertEquals("192.168.1.0-192.168.1.255", range("192.168.1.77 255.255.255.0"))
        assertEquals("192.168.1.0-192.168.1.255", range("192.168.1.77/255.255.255.0"))
        assertEquals("10.1.2.3-10.1.2.3", range("10.1.2.3"))
        assertEquals("0.0.0.0-255.255.255.255", range("0.0.0.0/0"))
    }

    @Test
    fun brokenRangesAreRefused() {
        val broken = listOf(
            "", "-", "10.0.0.1-", "-5", "10.0.0.9-10.0.0.1", "10.0.0.9-5", "10.0.0.1-256", "10.0.0.1-1000", "10.0.0.1--5", "10.0.0.1-5-9",
            "10.0.0.1/8-9", "10.0.0.1-+5", "10.0.0.1-5.5", "10.0.0.1-x", "10.0.0.256-10.0.0.9", "2001:db8::1-2001:db8::9",
        )
        broken.forEach { assertNull(Ipv4.parseRange(it), it) }
    }

    @Test
    fun rangeIsCoveredByTheFewestBlocks() {
        val (first, last) = Ipv4.parseRange("192.168.1.10-192.168.5.110")!!
        val blocks = SubnetMath.rangeToCidrs(first, last)
        assertEquals(
            listOf(
                "192.168.1.10/31", "192.168.1.12/30", "192.168.1.16/28", "192.168.1.32/27", "192.168.1.64/26", "192.168.1.128/25", "192.168.2.0/23",
                "192.168.4.0/24", "192.168.5.0/26", "192.168.5.64/27", "192.168.5.96/29", "192.168.5.104/30", "192.168.5.108/31", "192.168.5.110/32",
            ),
            blocks.map { cidr(it) },
        )
        assertEquals(last - first + 1, blocks.sumOf { it.totalAddresses })
    }

    @Test
    fun enclosingNetworkHoldsBothEnds() {
        fun enclosing(range: String) = Ipv4.parseRange(range)!!.let { (first, last) -> cidr(Ipv4.enclosing(first, last)) }
        assertEquals("192.168.0.0/21", enclosing("192.168.1.10-192.168.5.110"))
        assertEquals("192.168.1.0/25", enclosing("192.168.1.10-100"))
        assertEquals("10.0.0.0/23", enclosing("10.0.0.255-10.0.1.0"))
        assertEquals("10.0.0.5/32", enclosing("10.0.0.5-5"))
        assertEquals("0.0.0.0/0", enclosing("0.0.0.0-255.255.255.255"))
    }
}
