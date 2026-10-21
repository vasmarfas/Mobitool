package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Deflate
import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmarcTest {
    private val report = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <feedback>
          <report_metadata>
            <org_name>google.com</org_name>
            <email>noreply-dmarc-support@google.com</email>
            <report_id>4235432178943</report_id>
            <date_range><begin>1760918400</begin><end>1761004799</end></date_range>
          </report_metadata>
          <policy_published>
            <domain>example.com</domain><adkim>r</adkim><aspf>r</aspf><p>reject</p><sp>reject</sp><pct>100</pct>
          </policy_published>
          <record>
            <row>
              <source_ip>209.85.220.41</source_ip><count>12</count>
              <policy_evaluated><disposition>none</disposition><dkim>pass</dkim><spf>pass</spf></policy_evaluated>
            </row>
            <identifiers><header_from>example.com</header_from></identifiers>
            <auth_results>
              <dkim><domain>example.com</domain><selector>google</selector><result>pass</result></dkim>
              <spf><domain>example.com</domain><result>pass</result></spf>
            </auth_results>
          </record>
          <record>
            <row>
              <source_ip>198.51.100.7</source_ip><count>3</count>
              <policy_evaluated><disposition>reject</disposition><dkim>fail</dkim><spf>fail</spf></policy_evaluated>
            </row>
            <identifiers><header_from>example.com</header_from></identifiers>
            <auth_results>
              <dkim><domain>sendgrid.net</domain><result>pass</result></dkim>
              <spf><domain>bounces.sendgrid.net</domain><result>pass</result></spf>
            </auth_results>
          </record>
        </feedback>
    """.trimIndent()

    @Test
    fun strictRecordHasNoIssues() {
        val record = Dmarc.parse("v=DMARC1; p=reject; sp=reject; adkim=s; aspf=s; rua=mailto:dmarc@example.com!10m; fo=1:d; ri=86400")
        assertEquals(listOf("v", "p", "sp", "adkim", "aspf", "rua", "fo", "ri"), record.tags.map { it.name })
        assertTrue(record.issues.isEmpty(), record.issues.map { it.text.key }.toString())
    }

    @Test
    fun weakRecordGetsWarnings() {
        val record = Dmarc.parse("v=DMARC1;p=none;pct=50")
        assertEquals(
            listOf(Res.string.dmarc_policy_none, Res.string.dmarc_pct_partial, Res.string.dmarc_no_rua),
            record.issues.map { it.text },
        )
        assertTrue(record.issues.none { it.error })
        assertEquals(Res.string.dmarc_sp_none, Dmarc.parse("v=DMARC1; p=quarantine; sp=none; rua=mailto:a@example.com").issues.single().text)
    }

    @Test
    fun brokenRecordGetsErrors() {
        val record = Dmarc.parse("p=block; v=DMARC1; pct=150; rua=https://example.com/dmarc; p=reject")
        val errors = record.issues.filter { it.error }
        assertEquals(Res.string.dmarc_version_first, errors.first().text)
        assertTrue(errors.any { it.text == Res.string.dmarc_repeated_tag && it.args.single() == "p" })
        assertEquals(listOf("p", "pct", "rua"), errors.filter { it.text == Res.string.dmarc_bad_value }.map { it.args.first() })
        assertEquals(Res.string.dmarc_no_policy, Dmarc.parse("v=DMARC1; rua=mailto:a@example.com").issues.single().text)
    }

    @Test
    fun onlyDmarcTextCountsAsRecord() {
        assertTrue(Dmarc.isRecord("v=DMARC1; p=none"))
        assertTrue(Dmarc.isRecord("V = DMARC1;p=none"))
        assertFalse(Dmarc.isRecord("v=dmarc1; p=none"))
        assertFalse(Dmarc.isRecord("v=spf1 include:_spf.google.com ~all"))
    }

    @Test
    fun aggregateReportIsSummed() {
        val parsed = assertNotNull(Dmarc.parseReport(report))
        assertEquals("google.com", parsed.org)
        assertEquals("example.com", parsed.domain)
        assertEquals(1760918400L, parsed.begin)
        assertEquals("adkim=r aspf=r p=reject sp=reject pct=100", parsed.policy)
        assertEquals(15L, parsed.total)
        assertEquals(12L, parsed.passed)
        assertEquals(3L, parsed.count("reject"))
        val spoofed = parsed.rows.last()
        assertEquals("198.51.100.7", spoofed.sourceIp)
        assertFalse(spoofed.passed)
        assertEquals(listOf("sendgrid.net" to "pass"), spoofed.dkimAuth)
        assertNull(Dmarc.parseReport("<html><body/></html>"))
    }

    @Test
    fun reportOpensFromGzipAndZip() {
        val xml = report.encodeToByteArray()
        fun le32(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }
        val name = "google.com!example.com!1760918400!1761004799.xml".encodeToByteArray()
        val gzip = byteArrayOf(0x1F, 0x8B.toByte(), 8, 8, 0, 0, 0, 0, 0, 3) + name + byteArrayOf(0) + Deflate.deflate(xml) + le32(Crc32.compute(xml)) + le32(xml.size)
        assertEquals(15L, Dmarc.readReports(gzip).single().total)
        assertEquals(15L, Dmarc.readReports(xml).single().total)
        val zip = ZipWriter().add("a.xml", xml).add("b.xml", report.replace("4235432178943", "77").encodeToByteArray()).toByteArray()
        assertEquals(2, Dmarc.readReports(zip).size)

        fun reason(bytes: ByteArray) = assertFailsWith<DmarcException> { Dmarc.readReports(bytes) }.reason
        assertEquals(Res.string.dmarc_not_a_report, reason(ZipWriter().add("readme.txt", xml).toByteArray()))
        assertEquals(Res.string.dmarc_not_a_report, reason("<html/>".encodeToByteArray()))
        assertEquals(Res.string.dmarc_damaged_archive, reason(gzip.copyOf(30)))
        val corrupted = gzip.copyOf().also { it[it.size - 8] = (it[it.size - 8] + 1).toByte() }
        assertEquals(Res.string.dmarc_damaged_archive, reason(corrupted))
    }

    @Test
    fun reportsMergeWithoutDuplicates() {
        val first = assertNotNull(Dmarc.parseReport(report))
        val second = assertNotNull(Dmarc.parseReport(report.replace("4235432178943", "77").replace("1761004799", "1761091199")))
        val merged = Dmarc.merge(listOf(first, second, assertNotNull(Dmarc.parseReport(report))))
        assertEquals(2, merged.reports)
        assertEquals(30L, merged.total)
        assertEquals(2, merged.rows.size)
        assertEquals(24L, merged.rows.first().count)
        assertEquals(1761091199L, merged.end)
        assertEquals("", merged.reportId)
        assertEquals(first, Dmarc.merge(listOf(first)))
    }

    @Test
    fun foreignReportAddressesNeedAuthorization() {
        val record = "v=DMARC1; p=none; rua=mailto:a@example.com,mailto:x@rua.agari.com!10m; ruf=mailto:f@dmarc.example.com,mailto:X@Rua.Agari.com"
        assertEquals(listOf("rua.agari.com"), Dmarc.externalReportHosts(record, "mail.example.com"))
        assertTrue(Dmarc.externalReportHosts("v=DMARC1; p=none", "example.com").isEmpty())
    }
}
