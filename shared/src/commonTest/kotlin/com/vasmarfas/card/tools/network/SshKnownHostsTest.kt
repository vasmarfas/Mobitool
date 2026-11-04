package com.vasmarfas.card.tools.network

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class SshKnownHostsTest {
    private val entry = SshHostEntry("ssh-ed25519", "SHA256:saved")

    // key and fingerprint as GitHub publishes them in its SSH key fingerprints page and /meta API
    @Test
    fun fingerprintMatchesOpenSshFormat() {
        val blob = Base64.Default.decode("AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")
        assertEquals("SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU", SshKnownHosts.fingerprint(blob))
    }

    @Test
    fun hostNameIsCaseInsensitiveAndPortMatters() {
        assertEquals(SshKnownHosts.id("example.com", 22), SshKnownHosts.id("EXAMPLE.com", 22))
        assertEquals("example.com:2222", SshKnownHosts.id("example.com", 2222))
    }

    @Test
    fun unknownHostIsNew() {
        assertEquals(SshHostStatus.NEW, SshKnownHosts.status(emptyMap(), "example.com:22", "SHA256:saved"))
    }

    @Test
    fun sameFingerprintIsTrusted() {
        assertEquals(SshHostStatus.TRUSTED, SshKnownHosts.status(mapOf("example.com:22" to entry), "example.com:22", "SHA256:saved"))
    }

    @Test
    fun otherFingerprintIsChanged() {
        assertEquals(SshHostStatus.CHANGED, SshKnownHosts.status(mapOf("example.com:22" to entry), "example.com:22", "SHA256:other"))
    }

    @Test
    fun otherPortOfAKnownHostIsNew() {
        assertEquals(SshHostStatus.NEW, SshKnownHosts.status(mapOf("example.com:22" to entry), "example.com:2222", "SHA256:saved"))
    }
}
