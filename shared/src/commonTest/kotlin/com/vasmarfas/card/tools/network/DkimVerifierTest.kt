package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.hexToBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DkimVerifierTest {
    private val now = 1_760_000_100L

    // RFC 8463 appendix A
    private val rfcEd25519 = "v=DKIM1; k=ed25519; p=11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
    private val rfcRsa = "v=DKIM1; k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDkHlOQoBTzWR" +
        "iGs5V6NpP3idY6Wk08a5qhdR6wy5bdOKb2jLQiY/J16JYi0Qvx/byYzCNb3W91y3FutAC" +
        "DfzwQ/BC/e/8uBsCR+yz1Lxj+PL6lHvqMKrM3rG4hstT5QjvHO9PzoxZyVYLzBfO2EeC3" +
        "Ip3G+2kryOTIKT+l/K4w3QIDAQAB"
    private val rfcMessage = "DKIM-Signature: v=1; a=ed25519-sha256; c=relaxed/relaxed;\r\n" +
        " d=football.example.com; i=@football.example.com;\r\n" +
        " q=dns/txt; s=brisbane; t=1528637909; h=from : to :\r\n" +
        " subject : date : message-id : from : subject : date;\r\n" +
        " bh=2jUSOH9NhtVGCQWNr9BrIAPreKQjO6Sn7XIkfJVOzv8=;\r\n" +
        " b=/gCrinpcQOoIfuHNQIbq4pgh9kyIK3AQUdt9OdqQehSwhEIug4D11Bus\r\n" +
        " Fa3bT3FY5OsU7ZbnKELq+eXdp1Q1Dw==\r\n" +
        "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;\r\n" +
        " d=football.example.com; i=@football.example.com;\r\n" +
        " q=dns/txt; s=test; t=1528637909; h=from : to : subject :\r\n" +
        " date : message-id : from : subject : date;\r\n" +
        " bh=2jUSOH9NhtVGCQWNr9BrIAPreKQjO6Sn7XIkfJVOzv8=;\r\n" +
        " b=F45dVWDfMbQDGHJFlXUNB2HKfbCeLRyhDXgFpEL8GwpsRe0IeIixNTe3\r\n" +
        " DhCVlUrSjV4BwcVcOF6+FF3Zo9Rpo1tFOeS9mPYQTnGdaSGsgeefOsk2Jz\r\n" +
        " dA+L10TeYt9BgDfQNZtKdN1WO//KgIqXP7OdEFE4LjFYNcUxZQ4FADY+8=\r\n" +
        "From: Joe SixPack <joe@football.example.com>\r\n" +
        "To: Suzie Q <suzie@shopping.example.net>\r\n" +
        "Subject: Is dinner ready?\r\n" +
        "Date: Fri, 11 Jul 2003 21:00:37 -0700 (PDT)\r\n" +
        "Message-ID: <20030712040037.46341.5F8J@football.example.com>\r\n" +
        "\r\n" +
        "Hi.\r\n" +
        "\r\n" +
        "We lost the game.  Are you hungry yet?\r\n" +
        "\r\n" +
        "Joe.\r\n" +
        "\r\n"

    // a throwaway key pair, the signatures below were made outside this code with the openssl binary
    private val key2048 = "p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnNlIF6qNaVYPMBttiuFE9Qz0YjpmiVbc7LXjsn3yFhAT4UizoCdpZKr4r/ZsZWkHRn" +
        "f5G3ijztm663yuL2LeXKHNdH2OtKzKkj5vgPfp5x7wJPOXgXZd8Rr9b9GSpNQt1hRC4ba/VPr8KgsVKUlKMiytckRpuM81AMq6GJtPKW/5AwMe" +
        "bheAPi2xWTRBNEI/Y4RpPfdkgw5m0GIEIDPN7RaG5wsZfaNmLNNTw/uuVeJzr+OH0epZfNZCd+xM5A88No4yT/vvryOy+Ba8+wZbaM8err2Dt5" +
        "4IgEJHfgR7JvvxO0KJDS1N3i5smXnPXLmFPo/ZyT5x4fKCsoNCwimjkwIDAQAB"
    private val key1024 = "p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC/gXVW+6K/E4l1iArwTIxsSR1b/bEb+7pC+ROTVIg0XtBCzaDFs2XWFEd3NnBafRVXTwQGoX" +
        "g2HFRR5X7sZtGwQZxncqQTV9VzgZq9TsCNmd0QOSbtj/UGDyRmWCbrJzfZ9HOU8Xom41K3RbIl0LuCVaVramkFGZPKwy/mAngCxQIDAQAB"
    private val headers = "Received: from relay.example.com (relay.example.com [192.0.2.25])\r\n" +
        "\tby mx.example.net with ESMTP; Thu, 09 Oct 2025 10:00:05 +0000\r\n" +
        "From: =?UTF-8?B?0JDQvdC90LA=?= <anna@example.com>\r\n" +
        "To:   team@example.net  \r\n" +
        "Subject: Status\r\n" +
        "    report   for\tOctober\r\n" +
        "Date: Thu, 09 Oct 2025 10:00:00 +0000\r\n" +
        "Message-ID: <fixture-1@example.com>\r\n" +
        "MIME-Version: 1.0\r\n" +
        "Content-Type: text/plain; charset=utf-8\r\n" +
        "Content-Transfer-Encoding: 8bit"
    private val body = "Привет,  команда.  \r\n\r\nОтчёт\tготов   \r\nСтрока с пробелами в конце \t \r\n\r\n\r\n"
    private val relaxedRelaxed = "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=example.com;\r\n" +
        " s=rsa2048; t=1760000000; h=from:to:subject:date:message-id:mime-version:content-type;\r\n" +
        " bh=ebYsShUCMRV1RsxFR4drUbjGfc1XKvo/142CEDyNHRI=;\r\n" +
        " b=g1UesnXpg12kvGmIySS69E8VYu1/xc4Mj5gMIv2CCmSiatHryC9L04cS1U8SFCmu\r\n" +
        " AS+cLNswT2J3MqPuMz0nUfWPwMsVIkfk2FPxqQd7ShgaP/S2gVUtdWRvkhpILbE3\r\n" +
        " p82F13eiNy9Q1kbhv6efF6V5tsR1cLyZTeXFN871Lou9HP4zGyD1MqMiO2m2QvHz\r\n" +
        " GDJ5kVk+9EHaR3wNWC7lwYjDUoqgL7XHZqQkuRJB7ho40YuBobG39mNjDS7W9xfh\r\n" +
        " 5JgZbze5ZpTRpsjWJ5qBCrt9JzouurvqKMMFcM1+ra2CoQfxUSF03LGhWJ7gvsJo\r\n" +
        " 7p7Szv/EEtK7B7LSKqOGow=="
    private val simpleSimple = "DKIM-Signature: v=1; a=rsa-sha256; c=simple/simple; d=example.com;\r\n" +
        " s=rsa1024; t=1760000000; h=from:to:subject:date:message-id:mime-version:content-type;\r\n" +
        " bh=+8Z4Z8GP0K2/VZFzSqyIUh55S2lQjbFMcjmQkIDHZkY=;\r\n" +
        " b=P3ildCflonFtXtLwxR3eriaNeE1DPoo0F0d09dYgVe8kXYUL03RN3Q08s5pRwbwg\r\n" +
        " x5vaxrRjqmcmnMV9SDee1m+4l54LCzXQ3HIZSlfYoql4QHkBrbdpvCWF9KLBuhH/\r\n" +
        " ewYkCDzh/8pO6xcgEPgWviVVZ0Lc6ChK8w4SFzXu4O4="
    private val relaxedSimple = "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/simple; d=example.com;\r\n" +
        " s=rsa2048; t=1760000000; h=from:to:subject:date:message-id:mime-version:content-type;\r\n" +
        " bh=+8Z4Z8GP0K2/VZFzSqyIUh55S2lQjbFMcjmQkIDHZkY=;\r\n" +
        " b=AhUyUEAPzIo44b5uRq1rKlvMitn9j+D1qo6aFTD8cFqze+TK60WXvNhySk1eV3f4\r\n" +
        " HZgSmAhLWTUHIvtpQIlgiaUVbPmWSluuKtQr6bSIlC4KYJxX/QHnd5EIkmYMrgNx\r\n" +
        " /qkwx1qHnQeM3mxvFnNRoEZHP4FPyMJJTx/9FvOhSzOHf0s8ozXUqqFA78nCADLH\r\n" +
        " OUnvY6moMvRKMObJSrMkqVwfBmwDx5nss254JvMJ5/+hgY89YUZ8J9nZh9ZaS19j\r\n" +
        " yOa9AQf1Tj7TI9vhjQ/cEifos2xSj9bev+OxiGmzN0IHQhgus2eSCywUgVp1VXC7\r\n" +
        " K009s54nzlp/MLM6vRaqcA=="

    private fun message(signature: String, head: String = headers, text: String = body) = "$signature\r\n$head\r\n\r\n$text".encodeToByteArray()

    private fun zone(vararg records: Pair<String, String>): suspend (String, String) -> List<DkimKey> = { domain, selector ->
        records.filter { it.first == "$selector._domainkey.$domain" }.map { DkimKey(selector, it.second) }
    }

    private val fixtureZone = zone("rsa2048._domainkey.example.com" to key2048, "rsa1024._domainkey.example.com" to key1024)

    private suspend fun verify(raw: ByteArray) = DkimVerifier.verify(raw, now, fixtureZone).single()

    private fun DkimVerdict.texts() = issues.map { it.text }

    // RFC 6376 section 3.4.5
    @Test
    fun canonicalizationExamplesOfTheRfc() {
        val a = "A:  X "
        val b = "B  :  Y\t\r\n\t Z  "
        val text = " C \r\nD \t E\r\n\r\n\r\n"
        assertEquals("a:X\r\n", DkimVerifier.canonicalHeader(a, relaxed = true))
        assertEquals("b:Y Z\r\n", DkimVerifier.canonicalHeader(b, relaxed = true))
        assertEquals(" C\r\nD E\r\n", DkimVerifier.canonicalBody(text, relaxed = true))
        assertEquals("$a\r\n", DkimVerifier.canonicalHeader(a, relaxed = false))
        assertEquals("$b\r\n", DkimVerifier.canonicalHeader(b, relaxed = false))
        assertEquals(" C \r\nD \t E\r\n", DkimVerifier.canonicalBody(text, relaxed = false))
    }

    // RFC 6376 section 3.4.3 and 3.4.4: an empty body is one CRLF in simple and nothing in relaxed
    @Test
    fun emptyAndUnterminatedBodies() {
        assertEquals("\r\n", DkimVerifier.canonicalBody("", relaxed = false))
        assertEquals("\r\n", DkimVerifier.canonicalBody("\r\n\r\n", relaxed = false))
        assertEquals("", DkimVerifier.canonicalBody("", relaxed = true))
        assertEquals("", DkimVerifier.canonicalBody(" \t\r\n\r\n", relaxed = true))
        assertEquals("text\r\n", DkimVerifier.canonicalBody("text", relaxed = false))
        assertEquals("text\r\n", DkimVerifier.canonicalBody("text", relaxed = true))
    }

    @Test
    fun rfcMessageVerifiesWithRsaAndSaysEd25519IsNotChecked() = runTest {
        val keys = zone("brisbane._domainkey.football.example.com" to rfcEd25519, "test._domainkey.football.example.com" to rfcRsa)
        val (ed25519, rsa) = DkimVerifier.verify(rfcMessage.encodeToByteArray(), now, keys)
        assertFalse(ed25519.verified)
        assertEquals(listOf(Res.string.dkim_verify_ed25519), ed25519.texts())
        assertTrue(rsa.verified, rsa.texts().toString())
        assertTrue(rsa.issues.none { it.error })
    }

    @Test
    fun ownSignaturesVerifyInEveryCanonicalization() = runTest {
        listOf(relaxedRelaxed, simpleSimple, relaxedSimple).forEach { signature ->
            val verdict = verify(message(signature))
            assertTrue(verdict.verified, verdict.texts().toString())
        }
    }

    @Test
    fun bareLfLineEndsAreRestored() = runTest {
        assertTrue(verify(message(relaxedRelaxed).decodeToString().replace("\r\n", "\n").encodeToByteArray()).verified)
    }

    @Test
    fun changedBodyIsCaughtWhileTheHeadersStayValid() = runTest {
        val verdict = verify(message(relaxedRelaxed, text = body.replace("готов", "сорван")))
        assertFalse(verdict.verified)
        assertEquals(listOf(Res.string.dkim_verify_body_differs, Res.string.dkim_verify_headers_ok), verdict.texts())
    }

    @Test
    fun relaxedBodyForgivesWhitespaceAndSimpleDoesNot() = runTest {
        val spaced = body.replace("Отчёт\tготов   ", "Отчёт   готов")
        assertTrue(verify(message(relaxedRelaxed, text = spaced)).verified)
        assertFalse(verify(message(simpleSimple, text = spaced)).verified)
    }

    @Test
    fun pastedHeadersWithoutBodyKeepTheHeaderCheck() = runTest {
        val verdict = verify("$relaxedRelaxed\r\n$headers\r\n".encodeToByteArray())
        assertEquals(listOf(Res.string.dkim_verify_body_differs, Res.string.dkim_verify_headers_ok), verdict.texts())
    }

    @Test
    fun changedSignedHeaderBreaksTheSignature() = runTest {
        val verdict = verify(message(relaxedRelaxed, head = headers.replace("Status", "Fraud")))
        assertFalse(verdict.verified)
        assertEquals(listOf(Res.string.dkim_verify_bad_signature), verdict.texts())
    }

    @Test
    fun unsignedHeaderAndRefoldingAreHarmlessInRelaxed() = runTest {
        val refolded = headers.replace("Subject: Status\r\n    report   for\tOctober", "Subject:   Status report\r\n for October") + "\r\nX-Spam-Score: 1"
        assertTrue(verify(message(relaxedRelaxed, head = refolded)).verified)
        assertFalse(verify(message(simpleSimple, head = refolded)).verified)
    }

    @Test
    fun anotherKeyDoesNotVerify() = runTest {
        val keys = zone("rsa2048._domainkey.example.com" to key1024)
        val verdict = DkimVerifier.verify(message(relaxedRelaxed), now, keys).single()
        assertEquals(listOf(Res.string.dkim_weak_key, Res.string.dkim_verify_bad_signature), verdict.texts())
    }

    @Test
    fun missingAndRevokedKeys() = runTest {
        assertEquals(listOf(Res.string.dkim_not_found), DkimVerifier.verify(message(relaxedRelaxed), now, zone()).single().texts())
        val revoked = DkimVerifier.verify(message(relaxedRelaxed), now, zone("rsa2048._domainkey.example.com" to "v=DKIM1; p=")).single()
        assertFalse(revoked.verified)
        assertEquals(listOf(Res.string.dkim_revoked), revoked.texts())
    }

    @Test
    fun signatureRulesOfTheRfc() = runTest {
        fun field(tags: String) = "DKIM-Signature: v=1; $tags; d=example.com; s=rsa2048; h=from; bh=AAAA; b=AAAA"
        val sha1 = verify(message(field("a=rsa-sha1")))
        assertEquals(listOf(Res.string.dkim_verify_sha1), sha1.texts())
        assertEquals(listOf(Res.string.dkim_verify_malformed), verify(message(field("a=dsa-sha256"))).texts())
        assertEquals(listOf(Res.string.dkim_verify_malformed), verify(message(field("a=rsa-sha256; c=odd/simple"))).texts())
        assertEquals(listOf(Res.string.dkim_verify_malformed), verify(message("DKIM-Signature: v=1; a=rsa-sha256; d=example.com")).texts())
        val foreign = verify(message(field("a=rsa-sha256; i=@example.net")))
        assertTrue(Res.string.dkim_verify_identity in foreign.texts())
        assertTrue(Res.string.dkim_verify_expired in verify(message(field("a=rsa-sha256; x=${now - 1}"))).texts())
        val unsigned = verify(message(field("a=rsa-sha256").replace("h=from", "h=subject")))
        assertTrue(Res.string.dkim_verify_from_unsigned in unsigned.texts())
    }

    @Test
    fun lengthTagHashesTheStartOfTheBody() = runTest {
        val field = "DKIM-Signature: v=1; a=rsa-sha256; l=6; d=example.com; s=rsa2048; h=from; bh=eDCyaw7m810RhFf0TmGWGkTYiT/1GwL4/PCOGIr3ew4=; b=AAAA"
        val verdict = verify(message(field, text = "text\r\nanything added later\r\n"))
        assertTrue(Res.string.dkim_verify_partial_body in verdict.texts())
        assertFalse(Res.string.dkim_verify_body_differs in verdict.texts())
    }

    @Test
    fun modularPowerMatchesKnownValues() {
        assertContentEquals(byteArrayOf(0x01, 0xBD.toByte()), Rsa.modPow(byteArrayOf(4), byteArrayOf(13), byteArrayOf(0x01, 0xF1.toByte())))
        val n = hexToBytes("eb4cb2424a23d5962217beaddbc496cb8e81973e0becd7b03898d190f9ebdacc0cb1e29c658cda1495e60af593bd04cf0fd630f1f29d0da9953f48f1a09f76b5a170b33839263059f28c105d1fb17c2390c192cfd3ac94af0f21ddb66cad4a268d116ece1738f7d93d9c172411e20b8f6b0d549b6f03675a1600a35a099950d836f675cc81e74ef5e8e25d940ed904759531985d5d9dc9f81818e811892f902bd23f0824128b2f330c5c7fd0a6a3a4506513270e269e0d37f2a74de452e6b439")!!
        val base = hexToBytes("0e00902c7ebff206867347214cdd2055930d6eaf14f4733f3e7d1bfbc7a2ea20b2f14c942e05319acb5c74273f98e2774cbd87ad5c90a9587403e430ec66a78795e761d17731af10506bf2efc6f877186d76b07e881ed162ae2eb1547f15052434b9b5df9e7769b10f4205b4907a70c31012f037b64ce4228c38fb2918f135d25f557203301850c5a38fd547923a736994e3bf911a61dbe22e44158bae97ba94d0eda82f8f6d05584ef8aa38922766581e27a1c08a6a63ec24ede6a4")!!
        val exponent = hexToBytes("b394fb36bb2d420f0f88080b10a3d6b2aa05e11ab2715945795e8229451abd81f1d69ed617f5e837d70820fe119a72d174c9df6acc011cdd9474031b7f26144b98289fcd59a54a7bb1fee08f571242425051c1ccd17f9acae01f5057ca02135e92b1d3f28ede0d7ac3baea9e13deef86ab1031d0f646e1f40a097c976bf46c697d2caf82eeeacbe226e875555790f82ec1d3fcff2a3af4d46b0a18e8830e07bc1e398f1012bd4acefaecbd389be4bcfc49b64a0872e6cc3ababced2057ee05cd")!!
        assertContentEquals(hexToBytes("e5e26505dd6ffe51ed99f68e579f8fd2b7df6de15c28209644cc177e59f1ec745db11a9fae059d85bb7b46895c962e9b9d42f738de121ec6cae62857094739dae21de2c340b8491a1b4a4ee46a8a65aa25c50fd9ff500f426b741398132b46cbdf48e64ab181ac00885691dd3e02d400aaae588c24273f626ddaa3575d2f275cea4739dadaa6179f11e1750aa8170b91bb91cbed58ca48d65d8d1a538418fcd669f5d0fd243fa391661aded3f0babcac075fd2ff1a7aaf56e035930216a27f4b"), Rsa.modPow(base, exponent, n))
        assertFailsWith<IllegalArgumentException> { Rsa.modPow(byteArrayOf(9), byteArrayOf(3), byteArrayOf(4)) }
        assertFailsWith<IllegalArgumentException> { Rsa.modPow(byteArrayOf(9), byteArrayOf(3), byteArrayOf(5)) }
    }

    @Test
    fun rsa2048ChecksWellUnderASecond() = runTest {
        val raw = message(relaxedRelaxed)
        verify(raw)
        assertTrue(measureTime { verify(raw) } < 1.seconds)
    }
}
