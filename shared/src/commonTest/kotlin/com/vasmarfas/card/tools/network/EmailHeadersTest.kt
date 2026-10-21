package com.vasmarfas.card.tools.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmailHeadersTest {
    private val message = """
        Delivered-To: owner@example.org
        Received: by 2002:a05:6a10:1234 with SMTP id abc;
                Wed, 21 Oct 2026 00:00:07 -0700 (PDT)
        ARC-Authentication-Results: i=1; mx.google.com;
               dkim=pass header.i=@example.com header.s=google header.b=abc;
               spf=pass (google.com: domain of bounce@mail.example.com designates 203.0.113.5 as permitted sender) smtp.mailfrom=bounce@mail.example.com;
               dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=example.com
        Received: from mail.example.com (mail.example.com. [203.0.113.5])
                by mx.google.com with ESMTPS id x;
                Wed, 21 Oct 2026 07:00:05 +0000
        Authentication-Results: mx.google.com;
               dkim=pass header.i=@example.com header.s=google header.b=abc;
               spf=softfail (google.com: domain of transitioning bounce@mail.example.com does not designate 198.51.100.7 as permitted sender) smtp.mailfrom=bounce@mail.example.com;
               dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=example.com
        Received: from [192.168.1.20] (unknown [198.51.100.7])
                by mail.example.com (Postfix) with ESMTPSA id 4F2;
                Wed, 21 Oct 2026 10:00:02 +0300 (MSK)
        DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=example.com; s=google;
                h=from:to:subject:date; bh=abc=; b=def
        Return-Path: <bounce@mail.example.com>
        Reply-To: Support <help@example.net>
        From: =?UTF-8?B?0J/R?= =?UTF-8?B?gNC40LLQtdGC?= <news@example.com>
        To: owner@example.org
        Subject: =?KOI8-R?Q?=F4=C5=D3=D4?= report
        Date: Wed, 21 Oct 2026 10:00:00 +0300
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="outer"

        This is a multi-part message in MIME format.
        --outer
        Content-Type: multipart/alternative; boundary=inner

        --inner
        Content-Type: text/plain; charset=utf-8
        Content-Transfer-Encoding: quoted-printable

        =D0=A1=D1=82=D1=80=D0=BE=D0=BA=D0=B0 =D0=BE=
        =D0=B4=D0=B8=D0=BD
        --inner
        Content-Type: text/html; charset=utf-8

        <p>html</p>
        --inner--
        --outer
        Content-Type: application/pdf
        Content-Disposition: attachment; filename*=utf-8''%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf
        Content-Transfer-Encoding: base64

        JVBERi0xLjQg
        dGVzdA==
        --outer--
    """.trimIndent().replace("\n", "\r\n")

    private val parsed = EmailHeaders.parse(message.encodeToByteArray())

    @Test
    fun encodedWordsAreDecodedAcrossCharsets() {
        assertEquals("Привет <news@example.com>", parsed.header("From"))
        assertEquals("Тест report", parsed.header("Subject"))
        assertEquals("a b", EmailHeaders.decodeWords("=?utf-8?Q?a_b?="))
        assertEquals("plain =?broken", EmailHeaders.decodeWords("plain =?broken"))
    }

    @Test
    fun routeGoesBottomUpWithDelays() {
        val hops = parsed.hops
        assertEquals(listOf("mail.example.com", "mx.google.com", "2002:a05:6a10:1234"), hops.map { it.by })
        assertEquals(listOf("[192.168.1.20]", "mail.example.com", ""), hops.map { it.from })
        assertEquals(listOf("198.51.100.7", "203.0.113.5", ""), hops.map { it.ip })
        assertEquals(listOf("ESMTPSA", "ESMTPS", "SMTP"), hops.map { it.with })
        assertEquals(listOf(2L, 3L, 2L), hops.map { it.delay })
        assertEquals(1792566002L, hops.first().time)
        assertEquals(7L, parsed.totalDelay)
    }

    @Test
    fun authenticationResultsKeepTheirDomains() {
        val direct = parsed.auth.filter { it.checkedBy == "mx.google.com" }
        assertEquals(listOf("dkim", "spf", "dmarc"), direct.map { it.method })
        assertEquals(listOf("pass", "softfail", "pass"), direct.map { it.result })
        assertEquals(listOf("example.com", "mail.example.com", "example.com"), direct.map { it.domain })
        assertEquals(3, parsed.auth.count { it.checkedBy == "ARC i=1, mx.google.com" })
    }

    @Test
    fun microsoftResultsHaveNoServerName() {
        val outlook = EmailHeaders.parse(
            ("Authentication-Results: spf=pass (sender IP is 203.0.113.5) smtp.mailfrom=example.com; dkim=pass (signature was verified)\r\n" +
                " header.d=example.com;dmarc=pass action=none header.from=example.com;compauth=pass reason=100\r\n").encodeToByteArray(),
        )
        assertEquals(listOf("spf", "dkim", "dmarc", "compauth"), outlook.auth.map { it.method })
        assertEquals(listOf("example.com", "example.com", "example.com", ""), outlook.auth.map { it.domain })
        assertEquals(setOf(""), outlook.auth.map { it.checkedBy }.toSet())
    }

    @Test
    fun foreignReplyToIsFlaggedButBounceSubdomainIsNot() {
        assertEquals(listOf("Reply-To" to "example.net"), parsed.foreignDomains())
        assertEquals("example.com", parsed.signatures.single().domain)
        assertEquals("google", parsed.signatures.single().selector)
    }

    @Test
    fun bodyAndAttachmentsAreDecoded() {
        assertEquals("Строка один", parsed.text)
        val pdf = parsed.attachments.single()
        assertEquals("отчёт.pdf", pdf.name)
        assertEquals("application/pdf", pdf.type)
        assertEquals("%PDF-1.4 test", pdf.bytes.decodeToString())
    }

    @Test
    fun htmlOnlyBodyBecomesText() {
        val html = EmailHeaders.parse(
            "From: a@example.com\nContent-Type: text/html; charset=utf-8\n\n<html><body><p>Hello</p><p>World &amp; more</p></body></html>\n".encodeToByteArray(),
        )
        assertEquals("Hello\n\nWorld & more", html.text)
        assertNull(html.totalDelay)
    }

    @Test
    fun datesAcceptCommentsAndShortForms() {
        assertEquals(1792566000L, EmailHeaders.parseDate(" Wed, 21 Oct 2026 10:00:00 +0300 (MSK)"))
        assertEquals(1792566000L, EmailHeaders.parseDate("21 Oct 2026 07:00:00 GMT"))
        assertEquals(1792566000L, EmailHeaders.parseDate("Mon, 21 Oct 2026 07:00:00 +0000"))
        assertNull(EmailHeaders.parseDate("yesterday"))
    }
}
