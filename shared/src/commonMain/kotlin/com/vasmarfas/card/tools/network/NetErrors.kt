package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import org.jetbrains.compose.resources.getString

suspend fun networkErrorText(e: Throwable): String {
    val names = generateSequence(e) { it.cause }.take(5).map { it::class.simpleName.orEmpty() }.toList()
    val reason = when {
        e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException -> Res.string.net_error_timeout
        "UnknownHostException" in names -> Res.string.net_error_unknown_host
        "ConnectException" in names || "NoRouteToHostException" in names -> Res.string.net_error_refused
        names.any { it.startsWith("CertPath") || it.startsWith("SunCertPath") || it.startsWith("Certificate") || it == "SSLPeerUnverifiedException" } ->
            Res.string.net_error_certificate
        names.any { it.startsWith("SSL") } -> Res.string.net_error_tls
        else -> null
    }
    return if (reason != null) getString(reason) else e.message ?: getString(Res.string.request_failed)
}
