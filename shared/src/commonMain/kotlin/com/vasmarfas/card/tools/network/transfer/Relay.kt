package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.AppConfig
import com.vasmarfas.card.core.IncomingFile
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.incomingFile
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.developer.Crc32
import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.toHex
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
class RelayFile(val name: String, val size: Long)

@Serializable
class RelayManifest(val sender: String, val files: List<RelayFile> = emptyList(), val text: String? = null)

class RelayException(val corrupt: Boolean) : Exception(if (corrupt) "corrupt stream" else "relay refused")

internal val STREAM_MAGIC = "MTX1".encodeToByteArray()

private const val MANIFEST_LIMIT = 1 shl 20

private fun nonce(counter: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (56 - 8 * i)).toByte() }

private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

internal suspend fun writeFrames(key: ByteArray, manifest: ByteArray, files: List<PlatformFile>, job: TransferJob, write: suspend (ByteArray, Int) -> Unit) {
    write(STREAM_MAGIC, STREAM_MAGIC.size)
    var counter = 0L
    suspend fun frame(plain: ByteArray, final: Boolean = false) {
        val sealed = withContext(Dispatchers.Default) { ChaCha20Poly1305.seal(key, nonce(counter++), plain, byteArrayOf(if (final) 1 else 0)) }
        write(be32(sealed.size), 4)
        write(sealed, sealed.size)
    }
    frame(manifest)
    files.forEach { file -> pump(file, job) { bytes, count -> frame(bytes.copyOf(count)) } }
    frame(ByteArray(0), final = true)
}

internal suspend fun readFrames(
    key: ByteArray,
    readFully: suspend (ByteArray) -> Unit,
    start: (RelayManifest) -> TransferJob,
    save: (String) -> IncomingFile = ::incomingFile,
): TransferJob {
    var counter = 0L
    suspend fun frame(): Pair<ByteArray, Boolean> {
        val head = ByteArray(4).also { readFully(it) }
        val length = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        if (length !in 16..MANIFEST_LIMIT + 16) throw RelayException(corrupt = true)
        val sealed = ByteArray(length).also { readFully(it) }
        val n = nonce(counter++)
        return withContext(Dispatchers.Default) {
            ChaCha20Poly1305.open(key, n, sealed, byteArrayOf(0))?.let { it to false }
                ?: ChaCha20Poly1305.open(key, n, sealed, byteArrayOf(1))?.let { it to true }
                ?: throw RelayException(corrupt = true)
        }
    }
    val manifest = runCatching { Net.json.decodeFromString(RelayManifest.serializer(), frame().first.decodeToString()) }
        .getOrElse { throw RelayException(corrupt = true) }
    val job = start(manifest)
    job.run()
    manifest.files.forEachIndexed { index, meta ->
        if (job.state != JobState.RUNNING) return job
        val target = save(meta.name)
        try {
            var left = meta.size
            val buffer = Buffer()
            while (left > 0) {
                val (data, final) = frame()
                if (final || data.size > left) throw RelayException(corrupt = true)
                withContext(Dispatchers.Default) {
                    buffer.write(data)
                    target.sink.write(buffer, data.size.toLong())
                }
                left -= data.size
                job.done += data.size
            }
            target.sink.flush()
            target.sink.close()
            job.items[index].file = target.keep()
        } catch (e: Exception) {
            runCatching { target.sink.close() }
            target.drop()
            throw e
        }
    }
    val (rest, final) = frame()
    if (!final || rest.isNotEmpty()) throw RelayException(corrupt = true)
    job.finish(JobState.DONE)
    return job
}

object Relay {
    const val DEFAULT = "https://ppng.io"
    private const val BASE_KEY = "transfer_relay"
    private const val ITERATIONS = 100_000
    private val salt = "mobitool-relay-1".encodeToByteArray()

    // the receiver waits a little longer, so a connection the sender picked at the last moment still reaches it
    private const val SENDER_WAIT = 8_000L
    private const val RECEIVER_WAIT = 10_000L

    var base: String
        get() = Prefs.store.get(BASE_KEY) ?: DEFAULT
        set(value) {
            val clean = value.trim().trimEnd('/')
            if (clean.isEmpty() || clean == DEFAULT) Prefs.store.remove(BASE_KEY) else Prefs.store.put(BASE_KEY, clean)
        }

    val host: String get() = base.substringAfter("://").substringBefore('/')

    // twelve digits; bytes of 250 and up are skipped so every digit is equally likely
    fun newCode(): String = buildString {
        while (length < 12) secureRandomBytes(16).forEach { val v = it.toInt() and 0xFF; if (v < 250 && length < 12) append(v % 10) }
    }

    fun pretty(code: String) = code.chunked(4).joinToString("-")

    fun parse(text: String): String? = text.substringAfter("code=", text).filter { it.isDigit() }.takeIf { it.length == 12 }

    fun link(code: String) = "${AppConfig.site()}/#tools/file-transfer?code=$code"

    class Keys(val path: String, val key: ByteArray)

    suspend fun keys(code: String): Keys = withContext(Dispatchers.Default) {
        val derived = pbkdf2Sha256(code.encodeToByteArray(), salt, ITERATIONS, 48)
        Keys(derived.copyOf(16).toHex(), derived.copyOfRange(16, 48))
    }

    private fun HttpRequestBuilder.waitForPeer() = timeout {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        if (currentPlatform != PlatformKind.WEB) socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }

    private fun body(type: ContentType, length: Long?, write: suspend (suspend (ByteArray, Int) -> Unit) -> Unit) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = type
        override val contentLength = length
        override suspend fun writeTo(channel: ByteWriteChannel) = write { bytes, count -> channel.writeFully(bytes, 0, count) }
    }

    private suspend fun exchange(keys: Keys, sender: Boolean, own: Offer): Offer = coroutineScope {
        val mine = if (sender) "s" else "r"
        val theirs = if (sender) "r" else "s"
        val sealed = Direct.seal(keys.key, Net.json.encodeToString(Offer.serializer(), own).encodeToByteArray(), "o$mine".encodeToByteArray())
        val put = async {
            Net.client.put("$base/${keys.path}-$mine") {
                waitForPeer()
                setBody(sealed)
            }.status.isSuccess()
        }
        val response = Net.client.get("$base/${keys.path}-$theirs") { waitForPeer() }
        if (!response.status.isSuccess() || !put.await()) throw RelayException(corrupt = false)
        Direct.open(keys.key, response.readRawBytes(), "o$theirs".encodeToByteArray())
            ?.let { runCatching { Net.json.decodeFromString(Offer.serializer(), it.decodeToString()) }.getOrNull() }
            ?: throw RelayException(corrupt = true)
    }

    // addresses from the DHT can be wrong, so that side wins only with a punched link whose key checked out
    private suspend fun meet(keys: Keys, sender: Boolean, alias: String, onOffer: (Offer) -> Unit): Pair<Reach?, TcpStream?> {
        val reach = Direct.reach()
        try {
            val own = Offer(alias, reach != null, reach?.endpoints.orEmpty(), reach?.punch?.endpoints.orEmpty())
            val punch = reach?.punch
            if (punch != null) {
                fun target(side: String) = Sha256.digest("mobitool-dht-${keys.path}-$side".encodeToByteArray()).copyOf(20)
                punch.meet(target(if (sender) "s" else "r"), target(if (sender) "r" else "s"))
            }
            return reach to coroutineScope {
                val punched = async { punch?.let { Direct.punched(keys.key, sender, it) } ?: awaitCancellation() }
                val offered = async { runCatching { exchange(keys, sender, own) } }
                select {
                    punched.onAwait { stream -> stream.also { offered.cancel() } }
                    offered.onAwait { result ->
                        val peer = result.getOrElse { if (punch == null) throw it else return@onAwait punched.await() }
                        punched.cancelAndJoin()
                        onOffer(peer)
                        if (reach == null || !peer.direct) null
                        else Direct.link(keys.key, sender, reach, peer, if (sender) SENDER_WAIT else RECEIVER_WAIT)
                    }
                }
            }
        } catch (e: Exception) {
            reach?.close()
            throw e
        }
    }

    suspend fun send(code: String, sender: String, files: List<PlatformFile>, text: String?, job: TransferJob) {
        val keys = keys(code)
        val manifest = Net.json.encodeToString(RelayManifest.serializer(), RelayManifest(sender, files.map { RelayFile(it.name, it.size()) }, text)).encodeToByteArray()
        val (reach, stream) = meet(keys, sender = true, sender) { peer -> peer.alias.takeIf { it.isNotBlank() }?.let { job.peer = it } }
        try {
            if (stream != null) {
                job.direct = true
                job.run()
                try {
                    writeFrames(keys.key, manifest, files, job) { bytes, count -> stream.write(bytes, 0, count) }
                    withTimeout(30_000.milliseconds) { ByteArray(1).also { stream.readFully(it) } }
                } finally {
                    stream.close()
                }
                return
            }
        } finally {
            reach?.close()
        }
        job.relay = host
        val response = Net.client.put("$base/${keys.path}") {
            waitForPeer()
            setBody(body(ContentType.Application.OctetStream, null) { write ->
                job.run()
                writeFrames(keys.key, manifest, files, job, write)
            })
        }
        if (!response.status.isSuccess()) throw RelayException(corrupt = false)
    }

    fun plainPath(files: List<PlatformFile>): String = "${secureRandomBytes(8).toHex()}/${percentEncode(files.singleOrNull()?.name ?: "files.zip")}"

    suspend fun sendPlain(path: String, files: List<PlatformFile>, job: TransferJob) {
        val single = files.singleOrNull()
        val name = single?.name ?: "files.zip"
        job.relay = host
        val response = Net.client.put("$base/$path") {
            waitForPeer()
            header("Content-Disposition", "attachment; filename*=UTF-8''${percentEncode(name)}")
            setBody(body(ContentType.parse(mimeOf(name)), single?.size()) { write ->
                job.run()
                if (single != null) {
                    pump(single, job, write)
                } else {
                    val zip = ZipStream(write)
                    files.forEach { file -> zip.add(file.name) { put -> pump(file, job, put) } }
                    zip.finish()
                }
            })
        }
        if (!response.status.isSuccess()) throw RelayException(corrupt = false)
    }

    suspend fun receive(code: String, alias: String, start: (RelayManifest) -> TransferJob) {
        val keys = keys(code)
        val (reach, stream) = meet(keys, sender = false, alias) {}
        try {
            if (stream != null) {
                try {
                    readFrames(keys.key, { stream.readFully(it) }, { manifest -> start(manifest).also { it.direct = true } })
                    stream.write(byteArrayOf(1))
                    // the sender closes once it has the confirmation; waiting for that keeps the byte from being cut off
                    withTimeoutOrNull(3_000.milliseconds) { stream.read(ByteArray(1)) }
                } finally {
                    stream.close()
                }
                return
            }
        } finally {
            reach?.close()
        }
        Net.client.prepareGet("$base/${keys.path}") { waitForPeer() }.execute { response ->
            if (!response.status.isSuccess()) throw RelayException(corrupt = false)
            val channel = response.bodyAsChannel()
            val head = ByteArray(4).also { channel.readFully(it) }
            if (!head.contentEquals(STREAM_MAGIC)) throw RelayException(corrupt = true)
            readFrames(keys.key, { channel.readFully(it) }, { manifest -> start(manifest).also { it.relay = host } })
        }
    }
}

// shortcut: no ZIP64 records, so the archive has to stay under 4 GiB; larger sets need them
internal class ZipStream(private val write: suspend (ByteArray, Int) -> Unit) {
    private class Entry(val name: ByteArray, val offset: Long, var crc: Int = 0, var size: Long = 0)

    private val entries = mutableListOf<Entry>()
    private var offset = 0L

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }

    private fun le(value: Int, bytes: Int) = le(value.toLong() and 0xFFFFFFFFL, bytes)

    private suspend fun put(bytes: ByteArray) {
        write(bytes, bytes.size)
        offset += bytes.size
    }

    // general purpose flags: bit 3 for the data descriptor, bit 11 for UTF-8 names; date 1980-01-01
    private val flags = 0x0808
    private val dosTime = 0x21 shl 16

    suspend fun add(name: String, body: suspend (suspend (ByteArray, Int) -> Unit) -> Unit) {
        val entry = Entry(name.encodeToByteArray(), offset)
        put(le(0x04034b50, 4) + le(20, 2) + le(flags, 2) + le(0, 2) + le(dosTime, 4) + le(0, 4) + le(0, 4) + le(0, 4) + le(entry.name.size, 2) + le(0, 2) + entry.name)
        var crc = -1
        body { bytes, count ->
            crc = Crc32.update(crc, bytes, 0, count)
            entry.size += count
            write(bytes, count)
            offset += count
        }
        entry.crc = crc.inv()
        put(le(0x08074b50, 4) + le(entry.crc, 4) + le(entry.size, 4) + le(entry.size, 4))
        entries += entry
    }

    suspend fun finish() {
        val start = offset
        entries.forEach { e ->
            put(
                le(0x02014b50, 4) + le(20, 2) + le(20, 2) + le(flags, 2) + le(0, 2) + le(dosTime, 4) + le(e.crc, 4) + le(e.size, 4) + le(e.size, 4) +
                    le(e.name.size, 2) + le(0, 2) + le(0, 2) + le(0, 2) + le(0, 2) + le(0, 4) + le(e.offset, 4) + e.name,
            )
        }
        put(le(0x06054b50, 4) + le(0, 2) + le(0, 2) + le(entries.size, 2) + le(entries.size, 2) + le(offset - start, 4) + le(start, 4) + le(0, 2))
    }
}
