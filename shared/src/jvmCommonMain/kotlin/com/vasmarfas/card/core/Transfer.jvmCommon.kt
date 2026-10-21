package com.vasmarfas.card.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private fun Certificate.sha256() = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

private class PinnedCertificate(private val fingerprint: String, private val onCertificate: (String) -> Unit) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val seen = chain?.firstOrNull()?.sha256()?.also(onCertificate)
        if (!seen.equals(fingerprint, ignoreCase = true)) throw CertificateException("fingerprint mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

actual fun lanTlsClient(fingerprint: String, onCertificate: (String) -> Unit): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    engine {
        config {
            val trust = PinnedCertificate(fingerprint, onCertificate)
            sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory, trust)
            hostnameVerifier { _, session -> session.peerCertificates.firstOrNull()?.sha256().equals(fingerprint, ignoreCase = true) }
            readTimeout(Duration.ZERO)
            writeTimeout(Duration.ZERO)
        }
    }
}
