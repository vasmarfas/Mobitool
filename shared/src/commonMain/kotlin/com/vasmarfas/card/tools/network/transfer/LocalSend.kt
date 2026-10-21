package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.connectTcp
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.deviceName
import com.vasmarfas.card.core.lanTlsClient
import com.vasmarfas.card.core.platformInfo
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.developer.toHex
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object LocalSend {
    const val PORT = 53317
    const val GROUP = "224.0.0.167"
    const val API = "/api/localsend/v2"

    val json = Json(Net.json) { encodeDefaults = true }

    private const val ALIAS_KEY = "transfer_alias"
    private const val FINGERPRINT_KEY = "transfer_fingerprint"

    var alias: String
        get() = Prefs.store.get(ALIAS_KEY)?.takeIf { it.isNotBlank() } ?: deviceName()
        set(value) = if (value.isBlank()) Prefs.store.remove(ALIAS_KEY) else Prefs.store.put(ALIAS_KEY, value.trim())

    val fingerprint: String by lazy {
        Prefs.store.get(FINGERPRINT_KEY) ?: secureRandomBytes(16).toHex().also { Prefs.store.put(FINGERPRINT_KEY, it) }
    }

    fun me(port: Int, announce: Boolean? = null) = DeviceInfo(
        alias = alias,
        deviceModel = platformInfo().let { if (currentPlatform == PlatformKind.DESKTOP) it.osName else it.deviceModel }.ifBlank { null },
        deviceType = when (currentPlatform) {
            PlatformKind.ANDROID, PlatformKind.IOS -> "mobile"
            PlatformKind.DESKTOP -> "desktop"
            PlatformKind.WEB -> "web"
        },
        fingerprint = fingerprint,
        port = port,
        announce = announce,
    )
}

@Serializable
data class DeviceInfo(
    val alias: String,
    val version: String = "2.1",
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = LocalSend.PORT,
    val protocol: String = "http",
    val download: Boolean = false,
    val announce: Boolean? = null,
)

@Serializable
data class FileMeta(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
)

@Serializable
data class PrepareUpload(val info: DeviceInfo, val files: Map<String, FileMeta>)

@Serializable
data class UploadSession(val sessionId: String, val files: Map<String, String> = emptyMap())

class Peer(val host: String, val info: DeviceInfo, val seen: Long) {
    val https get() = info.protocol == "https"
}

class PeerReply(val status: Int, val body: String)

suspend fun Peer.call(path: String, body: ByteArray, length: Long = body.size.toLong(), stream: (suspend (suspend (ByteArray, Int) -> Unit) -> Unit)? = null): PeerReply {
    val port = info.port
    if (https) {
        val client = lanTlsClient(info.fingerprint)
        try {
            val response = client.post("https://$host:$port$path") {
                if (stream == null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                } else {
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength = length
                        override val contentType = ContentType.Application.OctetStream
                        override suspend fun writeTo(channel: ByteWriteChannel) = stream { bytes, count -> channel.writeFully(bytes, 0, count) }
                    })
                }
            }
            return PeerReply(response.status.value, response.bodyAsText())
        } finally {
            client.close()
        }
    }
    val socket = connectTcp(host, port, 3_000)
    try {
        val type = if (stream == null) "application/json" else "application/octet-stream"
        socket.writeRequest("POST", "$host:$port", path, mapOf("Content-Type" to type, "Content-Length" to length.toString(), "Connection" to "close"))
        if (stream == null) socket.write(body) else stream { bytes, count -> socket.write(bytes, 0, count) }
        val reply = WireReader(socket).response()
        return PeerReply(reply.status, reply.body.bytes(1 shl 20).decodeToString())
    } finally {
        socket.close()
    }
}
