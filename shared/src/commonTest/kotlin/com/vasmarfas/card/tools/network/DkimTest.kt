package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DkimTest {
    private val rsa512 = "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAM1HjFbiTJN2vo6C2lU5bCCQ7WgeCR9zoYhuVhmyLMHZ6kwUthap7qGOxBKwsBeYjRI5Ku/k3/GKyR0LQIVo6lcCAwEAAQ=="
    private val rsa1024 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCjw1I6AtGptbAcHaVGP3OngldFqn/6fZUjvaBJo7avB3f8nETq78ALE9EV+fgOL+rJ8ydDd/0i7EwzZrThd5Hv" +
        "NhkRXJcRRIAeFQkrhVGUpTZTbPkwHVfJEm3/qtdyWqkDcCzDm7/vRNKXzSO3AZdEOirOjDEldX3kvSzHhLzjXwIDAQAB"
    private val rsa1024Pkcs1 = "MIGJAoGBAKPDUjoC0am1sBwdpUY/c6eCV0Wqf/p9lSO9oEmjtq8Hd/ycROrvwAsT0RX5+A4v6snzJ0N3/SLsTDNmtOF3ke82GRFclxFEgB4VCSuFUZSlNlNs+TAd" +
        "V8kSbf+q13JaqQNwLMObv+9E0pfNI7cBl0Q6Ks6MMSV1feS9LMeEvONfAgMBAAE="
    private val rsa2048 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAi5XMwHhEU7MsBWlF/zL5W8m5Ts8xLWBKlKANKgkMsdQTMCzk2vcCe5kTU6r5m+ti410bX8OsMkWmjfzHVVRI" +
        "Hmptah7KSpeqCY/b9RZV2cKcW/TbE/yhFnm41W5Ubt3AkmwV5QCcvtg36qG4prY/5rR7TGWTogwFtzO84f0xlh/5ppVXNMH/cyQq5GKmC06DpNmipXoZ198krmJW" +
        "/7Weyk13NLEdFhjn+1xIYpP8n+dr5+2OjELw5LDLoEHz2GVGkibuRaT3xw4xT/G+JozRRAIMhZ07M9LZkVRT295xWldGv8+0LslCMclhj6SSQbyLZlkHrruk1fA9" +
        "rmIpz7gfoQIDAQAB"
    private val ed25519 = "ec+r0PyaDE0XyW2qPiZ595m7Mlor31vJ7di5yZ/4GGI="

    private fun issues(record: String) = DkimKey("s", record).issues.map { it.text }

    @Test
    fun keyLengthComesFromTheModulus() {
        assertEquals(512, Dkim.keyBits("rsa", rsa512))
        assertEquals(1024, Dkim.keyBits("rsa", rsa1024))
        assertEquals(1024, Dkim.keyBits("rsa", rsa1024Pkcs1))
        assertEquals(2048, Dkim.keyBits("rsa", rsa2048))
        assertEquals(256, Dkim.keyBits("ed25519", ed25519))
        assertNull(Dkim.keyBits("rsa", "bm90IGEga2V5"))
        assertNull(Dkim.keyBits("rsa", rsa2048.take(100)))
    }

    @Test
    fun foldedRecordKeepsTagsAndKey() {
        val key = DkimKey("google", "v=DKIM1; k=rsa; t=s;\n h=sha256; p=${rsa2048.take(200)} ${rsa2048.drop(200)}")
        assertEquals(2048, key.bits)
        assertEquals("s", key.tags["t"])
        assertEquals("sha256", key.tags["h"])
        assertTrue(key.issues.isEmpty())
    }

    @Test
    fun problemsAreReported() {
        assertEquals(listOf(Res.string.dkim_revoked), issues("v=DKIM1; p="))
        assertEquals(listOf(Res.string.dkim_no_key), issues("v=DKIM1; k=rsa"))
        assertEquals(listOf(Res.string.dkim_short_key), issues("p=$rsa512"))
        assertEquals(listOf(Res.string.dkim_weak_key, Res.string.dkim_testing), issues("v=DKIM1; t=y; p=$rsa1024"))
        assertEquals(listOf(Res.string.dkim_bad_version, Res.string.dkim_bad_key), issues("v=DKIM2; p=AAAA"))
        assertEquals(listOf(Res.string.dkim_unknown_type), issues("k=dsa; p=$rsa1024"))
        assertTrue(issues("v=DKIM1; k=ed25519; p=$ed25519").isEmpty())
    }
}
