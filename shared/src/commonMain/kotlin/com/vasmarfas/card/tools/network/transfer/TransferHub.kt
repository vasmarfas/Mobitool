package com.vasmarfas.card.tools.network.transfer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.MulticastGroup
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.incomingFile
import com.vasmarfas.card.core.joinMulticast
import com.vasmarfas.card.core.lanTlsClient
import com.vasmarfas.card.core.listenTcp
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.openSource
import com.vasmarfas.card.core.safeFileName
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.tools.developer.MimeTypes
import com.vasmarfas.card.tools.developer.toHex
import com.vasmarfas.card.tools.network.Ipv4
import com.vasmarfas.card.tools.network.Ipv4Subnet
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readTo
import kotlin.time.Duration.Companion.milliseconds

enum class JobState { WAITING, RUNNING, DONE, PIN, DECLINED, BUSY, FAILED, CANCELLED }

class TransferItem(val name: String, val size: Long) {
    var file by mutableStateOf<PlatformFile?>(null)
}

class TransferJob(val outgoing: Boolean, peer: String, val items: List<TransferItem>) {
    var peer by mutableStateOf(peer)
    val total = items.sumOf { it.size }

    var direct by mutableStateOf(false)
    var relay by mutableStateOf<String?>(null)
    var done by mutableLongStateOf(0L)
    var state by mutableStateOf(JobState.WAITING)
    var startedAt by mutableLongStateOf(0L)
    var finishedAt by mutableLongStateOf(0L)
    internal var stop: () -> Unit = {}
    internal var resend: (String) -> Unit = {}

    fun cancel() = stop()

    fun retry(pin: String) = resend(pin)

    internal fun run() {
        state = JobState.RUNNING
        startedAt = currentEpochMillis()
    }

    internal fun finish(result: JobState) {
        if (state == JobState.RUNNING || state == JobState.WAITING) state = result
        finishedAt = currentEpochMillis()
    }
}

class IncomingOffer(val sender: DeviceInfo, val files: List<FileMeta>, internal val decision: CompletableDeferred<Boolean>) {
    fun answer(accept: Boolean) = decision.complete(accept)
}

class TextMessage(val sender: String, val text: String)

private class ReceiveSession(val id: String, val host: String, val files: Map<String, FileMeta>, val tokens: Map<String, String>, val job: TransferJob) {
    val left = files.keys.toMutableSet()
}

internal const val CHUNK = 64 * 1024

internal fun mimeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypes.all.firstOrNull { extension in it.extensions }?.type ?: when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}

internal suspend fun RawSource.readChunk(scratch: Buffer, into: ByteArray): Int = withContext(Dispatchers.Default) {
    val read = readAtMostTo(scratch, into.size.toLong())
    if (read <= 0L) -1 else read.toInt().also { scratch.readTo(into, 0, it) }
}

class TransferHub(private val scope: CoroutineScope, private val page: PageText) {
    var port by mutableStateOf<Int?>(null)
    var listening by mutableStateOf(true)
    var multicast by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    val peers = mutableStateMapOf<String, Peer>()
    var offer by mutableStateOf<IncomingOffer?>(null)
    val jobs = mutableStateListOf<TransferJob>()
    val messages = mutableStateListOf<TextMessage>()
    var autoAccept by mutableStateOf(false)
    var pin by mutableStateOf("")
    var shared by mutableStateOf<List<PlatformFile>>(emptyList())
    val token: String = secureRandomBytes(3).toHex()
    private var group: MulticastGroup? = null
    private var session: ReceiveSession? = null

    private val me get() = LocalSend.me(port ?: LocalSend.PORT)

    private fun encode(info: DeviceInfo) = LocalSend.json.encodeToString(DeviceInfo.serializer(), info).encodeToByteArray()

    fun start() {
        scope.launch {
            val server = withContext(Dispatchers.Default) {
                runCatching { listenTcp(LocalSend.PORT) }.recoverCatching { listenTcp(0) }.getOrNull()
            }
            if (server == null) {
                listening = false
                return@launch
            }
            port = server.port
            group = withContext(Dispatchers.Default) { joinMulticast(LocalSend.GROUP, LocalSend.PORT) }
            multicast = group != null
            group?.let { launch { listen(it) } }
            announce()
            try {
                while (true) {
                    val stream = server.accept()
                    launch { serve(stream) }
                }
            } finally {
                server.close()
                group?.close()
            }
        }
    }

    fun announce() {
        val group = group ?: return
        scope.launch {
            repeat(3) {
                runCatching { group.send(encode(LocalSend.me(port ?: return@launch, announce = true))) }
                delay(1_500.milliseconds)
            }
        }
    }

    private fun addPeer(host: String, info: DeviceInfo) {
        if (info.fingerprint == LocalSend.fingerprint) return
        peers[info.fingerprint] = Peer(host, info, currentEpochMillis())
    }

    private suspend fun listen(group: MulticastGroup) {
        while (true) {
            val packet = group.receive()
            val info = runCatching { LocalSend.json.decodeFromString(DeviceInfo.serializer(), packet.data.decodeToString()) }.getOrNull() ?: continue
            if (info.fingerprint == LocalSend.fingerprint) continue
            addPeer(packet.host, info)
            // the announcer learns about this device from a register call, or from a reply on the group when that fails
            if (info.announce == true) {
                scope.launch {
                    val answered = runCatching { Peer(packet.host, info, 0).call("${LocalSend.API}/register", encode(me)).status == 200 }.getOrDefault(false)
                    if (!answered) runCatching { group.send(encode(LocalSend.me(port ?: return@launch, announce = false))) }
                }
            }
        }
    }

    // for networks without multicast and iPhones without the multicast entitlement
    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true
            try {
                val own = networkInterfaces().filter { it.isUp && !it.isLoopback }.flatMap { it.addresses }.mapNotNull { Ipv4.parse(it.substringBefore('/')) }
                val subnets = own.map { Ipv4Subnet(it, 24) }.filter { it.isPrivate }.distinctBy { it.network }
                val gate = Semaphore(48)
                subnets.flatMap { net -> (net.firstHost..net.lastHost).filter { it !in own } }.map { address ->
                    launch { gate.withPermit { probe(Ipv4.format(address)) } }
                }.forEach { it.join() }
            } finally {
                scanning = false
            }
        }
    }

    fun find(host: String) {
        scope.launch { probe(host) }
    }

    private suspend fun probe(host: String) {
        if (tcpConnect(host, LocalSend.PORT, 600) == null) return
        val body = encode(me)
        val plain = Peer(host, DeviceInfo(alias = "", fingerprint = "", port = LocalSend.PORT), 0)
        val reply = runCatching { withTimeoutOrNull(2_000.milliseconds) { plain.call("${LocalSend.API}/register", body) } }.getOrNull()
        if (reply?.status == 200) {
            parseInfo(reply.body)?.let { addPeer(host, it.copy(port = LocalSend.PORT, protocol = "http")) }
            return
        }
        var seen: String? = null
        runCatching { withTimeoutOrNull(2_000.milliseconds) { lanTlsClient("") { seen = it }.use { it.post("https://$host:${LocalSend.PORT}${LocalSend.API}/info") } } }
        val fingerprint = seen ?: return
        val secure = Peer(host, DeviceInfo(alias = "", fingerprint = fingerprint, port = LocalSend.PORT, protocol = "https"), 0)
        val info = runCatching { withTimeoutOrNull(3_000.milliseconds) { secure.call("${LocalSend.API}/register", body) } }.getOrNull()?.body?.let(::parseInfo) ?: return
        if (info.fingerprint.equals(fingerprint, ignoreCase = true)) addPeer(host, info.copy(port = LocalSend.PORT, protocol = "https"))
    }

    private fun parseInfo(text: String) = runCatching { LocalSend.json.decodeFromString(DeviceInfo.serializer(), text) }.getOrNull()

    private suspend fun serve(stream: TcpStream) {
        try {
            val reader = WireReader(stream)
            while (true) {
                val request = reader.request() ?: break
                route(request, stream)
                request.body.drain()
                if (request.headers["connection"].equals("close", ignoreCase = true)) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            stream.close()
        }
    }

    private suspend fun route(request: WireRequest, stream: TcpStream) {
        val api = LocalSend.API
        val home = "/$token"
        when {
            request.path == "$api/register" && request.method == "POST" -> {
                val info = parseInfo(request.body.bytes(64 * 1024).decodeToString()) ?: return stream.reply(400)
                addPeer(stream.remoteHost, info)
                stream.reply(200, encode(me))
            }
            request.path == "$api/info" -> stream.reply(200, encode(me))
            request.path == "$api/prepare-upload" && request.method == "POST" -> prepare(request, stream)
            request.path == "$api/upload" && request.method == "POST" -> upload(request, stream)
            request.path == "$api/cancel" && request.method == "POST" -> {
                session?.let { it.job.finish(JobState.CANCELLED) }
                session = null
                stream.reply(200)
            }
            request.path == home || request.path == "$home/" -> stream.reply(200, transferPage(LocalSend.alias, shared.map { it.name to it.size() }, page).encodeToByteArray(), "text/html; charset=utf-8")
            request.path == "$home/upload" && request.method == "POST" -> browserUpload(request, stream)
            request.path.startsWith("$home/file/") && request.method == "GET" -> browserDownload(request, stream)
            else -> stream.reply(404)
        }
    }

    private suspend fun prepare(request: WireRequest, stream: TcpStream) {
        val upload = runCatching {
            LocalSend.json.decodeFromString(PrepareUpload.serializer(), request.body.bytes(4 shl 20).decodeToString())
        }.getOrNull() ?: return stream.reply(400)
        if (pin.isNotEmpty() && request.query["pin"] != pin) return stream.reply(401)
        if (session != null || offer != null) return stream.reply(409)
        val files = upload.files.values.toList()
        val text = files.singleOrNull()?.takeIf { it.fileType.startsWith("text/") }?.preview
        if (text != null) {
            messages.add(0, TextMessage(upload.info.alias, text))
            return stream.reply(204)
        }
        val accepted = autoAccept || run {
            val decision = CompletableDeferred<Boolean>()
            offer = IncomingOffer(upload.info, files, decision)
            try {
                withTimeoutOrNull((5 * 60_000).milliseconds) { decision.await() } == true
            } finally {
                offer = null
            }
        }
        if (!accepted) return stream.reply(403)
        val job = TransferJob(outgoing = false, peer = upload.info.alias, items = files.map { TransferItem(safeFileName(it.fileName), it.size) })
        val tokens = files.associate { it.id to secureRandomBytes(8).toHex() }
        val created = ReceiveSession(secureRandomBytes(8).toHex(), stream.remoteHost, files.associateBy { it.id }, tokens, job)
        session = created
        jobs.add(0, job)
        job.run()
        job.stop = {
            job.finish(JobState.CANCELLED)
            if (session === created) session = null
        }
        stream.reply(200, LocalSend.json.encodeToString(UploadSession.serializer(), UploadSession(created.id, tokens)).encodeToByteArray())
    }

    private suspend fun upload(request: WireRequest, stream: TcpStream) {
        val current = session ?: return stream.reply(409)
        val query = request.query
        val meta = current.files[query["fileId"]]
        if (query["sessionId"] != current.id || meta == null || current.tokens[meta.id] != query["token"] || stream.remoteHost != current.host) {
            return stream.reply(403)
        }
        val item = current.job.items[current.files.keys.indexOf(meta.id)]
        val saved = receive(request.body, item, current.job)
        current.left.remove(meta.id)
        if (!saved) {
            current.job.finish(JobState.FAILED)
            session = null
            return stream.reply(500)
        }
        stream.reply(200)
        if (current.left.isEmpty()) {
            current.job.finish(JobState.DONE)
            session = null
        }
    }

    private suspend fun receive(body: WireBody, item: TransferItem, job: TransferJob): Boolean {
        val target = runCatching { incomingFile(item.name) }.getOrNull() ?: return false
        var received = 0L
        val ok = runCatching {
            withContext(Dispatchers.Default) {
                val chunk = ByteArray(CHUNK)
                val buffer = Buffer()
                while (job.state == JobState.RUNNING) {
                    val read = body.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    target.sink.write(buffer, read.toLong())
                    received += read
                    job.done += read
                }
                target.sink.flush()
                target.sink.close()
            }
            received == item.size
        }.getOrDefault(false)
        if (ok) {
            item.file = runCatching { target.keep() }.getOrNull()
        } else {
            runCatching { target.sink.close() }
            target.drop()
        }
        return ok
    }

    private suspend fun browserUpload(request: WireRequest, stream: TcpStream) {
        val name = request.query["name"]?.let(::safeFileName) ?: return stream.reply(400)
        val size = request.body.length ?: return stream.reply(400)
        val job = TransferJob(outgoing = false, peer = stream.remoteHost, items = listOf(TransferItem(name, size)))
        jobs.add(0, job)
        job.run()
        val saved = receive(request.body, job.items[0], job)
        job.finish(if (saved) JobState.DONE else JobState.FAILED)
        stream.reply(if (saved) 200 else 500)
    }

    private suspend fun browserDownload(request: WireRequest, stream: TcpStream) {
        val file = request.path.substringAfter("/file/").substringBefore('/').toIntOrNull()?.let { shared.getOrNull(it) } ?: return stream.reply(404)
        val size = file.size()
        val job = TransferJob(outgoing = true, peer = stream.remoteHost, items = listOf(TransferItem(file.name, size)))
        jobs.add(0, job)
        job.run()
        stream.writeHead(
            200,
            mapOf(
                "Content-Type" to mimeOf(file.name),
                "Content-Length" to size.toString(),
                "Content-Disposition" to "attachment; filename*=UTF-8''${percentEncode(file.name)}",
            ),
        )
        val sent = runCatching { pump(file, job) { bytes, count -> stream.write(bytes, 0, count) } }.isSuccess
        job.finish(if (sent && job.done == size) JobState.DONE else JobState.FAILED)
    }

    fun send(peer: Peer, files: List<PlatformFile>, text: String?, pin: String? = null): TransferJob {
        val textBytes = text?.encodeToByteArray()
        val metas = if (textBytes != null) {
            listOf(FileMeta("text", "${secureRandomBytes(4).toHex()}.txt", textBytes.size.toLong(), "text/plain", preview = text))
        } else {
            files.mapIndexed { index, file -> FileMeta("file$index", file.name, file.size(), mimeOf(file.name)) }
        }
        val job = TransferJob(outgoing = true, peer = peer.info.alias, items = metas.map { TransferItem(it.fileName, it.size) })
        jobs.add(0, job)
        val running = scope.launch {
            var sessionId: String? = null
            try {
                val request = LocalSend.json.encodeToString(PrepareUpload.serializer(), PrepareUpload(me, metas.associateBy { it.id }))
                val path = "${LocalSend.API}/prepare-upload" + pin?.let { "?pin=${percentEncode(it)}" }.orEmpty()
                val reply = peer.call(path, request.encodeToByteArray())
                when (reply.status) {
                    200 -> Unit
                    204 -> return@launch job.finish(JobState.DONE)
                    401 -> return@launch job.finish(JobState.PIN)
                    403 -> return@launch job.finish(JobState.DECLINED)
                    409, 429 -> return@launch job.finish(JobState.BUSY)
                    else -> return@launch job.finish(JobState.FAILED)
                }
                val upload = LocalSend.json.decodeFromString(UploadSession.serializer(), reply.body)
                sessionId = upload.sessionId
                job.run()
                metas.forEachIndexed { index, meta ->
                    val token = upload.files[meta.id] ?: return@forEachIndexed
                    val path = "${LocalSend.API}/upload?sessionId=${percentEncode(upload.sessionId)}&fileId=${percentEncode(meta.id)}&token=${percentEncode(token)}"
                    val status = if (textBytes != null) {
                        peer.call(path, ByteArray(0), meta.size) { write ->
                            write(textBytes, textBytes.size)
                            job.done += textBytes.size
                        }.status
                    } else {
                        peer.call(path, ByteArray(0), meta.size) { write -> pump(files[index], job, write) }.status
                    }
                    if (status != 200) return@launch job.finish(JobState.FAILED)
                }
                job.finish(JobState.DONE)
            } catch (e: CancellationException) {
                job.finish(JobState.CANCELLED)
                sessionId?.let { id ->
                    withContext(NonCancellable) { runCatching { withTimeoutOrNull(2_000.milliseconds) { peer.call("${LocalSend.API}/cancel?sessionId=${percentEncode(id)}", ByteArray(0)) } } }
                }
                throw e
            } catch (_: Exception) {
                job.finish(JobState.FAILED)
            }
        }
        job.stop = { running.cancel() }
        job.resend = { code ->
            jobs.remove(job)
            send(peer, files, text, code)
        }
        return job
    }
}

internal suspend fun pump(file: PlatformFile, job: TransferJob, write: suspend (ByteArray, Int) -> Unit) {
    val source = file.openSource()
    try {
        val chunk = ByteArray(CHUNK)
        val scratch = Buffer()
        while (true) {
            val read = source.readChunk(scratch, chunk)
            if (read < 0) break
            write(chunk, read)
            job.done += read
        }
    } finally {
        source.close()
    }
}
