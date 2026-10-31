package com.vasmarfas.card.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.milliseconds

private val osName = (System.getProperty("os.name") ?: "").lowercase()
private val isWindows = osName.contains("win")
private val isMac = osName.contains("mac")
private val isAndroid = (System.getProperty("java.vendor") ?: "").lowercase().contains("android") ||
    (System.getProperty("java.vm.vendor") ?: "").lowercase().contains("android") ||
    System.getProperty("java.runtime.name")?.lowercase()?.contains("android") == true ||
    System.getProperty("os.name") == "Linux" && File("/system/bin/ping").exists()

private val pingBinary: String = when {
    isAndroid -> "/system/bin/ping"
    else -> "ping"
}

private val ipv4Regex = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")

private val ttlRegex = Regex("""\bttl\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
private val windowsTimeRegex = Regex("""[=<]\s*(\d+)\s*[^\s=<]*\s+TTL=""")
private val unixTimeRegex = Regex("""time[=<]\s*([\d.]+)""")
private val windowsV6TimeRegex = Regex("""[=<]\s*(\d+)\s*[^\s=<]*\s*$""")

actual fun platformNetCapabilities() = PlatformNetCapabilities(
    icmpPing = true,
    tcp = true,
    udp = true,
    interfaces = true,
    dnsOverTls = true,
    dnsOverQuic = true,
)

private fun pingCommand(host: String, timeoutMs: Int, ttl: Int?, size: Int?): List<String> = buildList {
    add(pingBinary)
    when {
        isWindows -> {
            if (!host.contains(':')) add("-4")
            add("-n"); add("1"); add("-w"); add(timeoutMs.toString())
            if (ttl != null) { add("-i"); add(ttl.toString()) }
            if (size != null) { add("-f"); add("-l"); add(size.toString()) }
        }
        isMac -> {
            add("-c"); add("1"); add("-W"); add(timeoutMs.toString())
            if (ttl != null) { add("-m"); add(ttl.toString()) }
            if (size != null) { add("-D"); add("-s"); add(size.toString()) }
        }
        else -> {
            add("-c"); add("1"); add("-W"); add(((timeoutMs + 999) / 1000).coerceAtLeast(1).toString())
            if (ttl != null) { add("-t"); add(ttl.toString()) }
            if (size != null) { add("-M"); add("do"); add("-s"); add(size.toString()) }
        }
    }
    add(host)
}

internal fun parsePing(output: String, sequence: Int, ttl: Int?, ipv6: Boolean, elapsedMs: Double): PingReply {
    val lines = output.lines()
    val reply = lines.firstOrNull { ttlRegex.containsMatchIn(it) }
    if (reply != null) {
        val time = (windowsTimeRegex.find(reply) ?: unixTimeRegex.find(reply))?.groupValues?.get(1)?.toDoubleOrNull()
        return PingReply(sequence, time ?: elapsedMs, ttlRegex.find(reply)?.groupValues?.get(1)?.toIntOrNull(), ipv4Regex.find(reply)?.value, null)
    }
    if (ipv6) {
        val time = lines.firstNotNullOfOrNull { windowsV6TimeRegex.find(it) }?.groupValues?.get(1)?.toDoubleOrNull()
        if (time != null) return PingReply(sequence, time, null, null, null)
    }
    val addresses = ipv4Regex.findAll(output).map { it.value }.toList()
    val hop = addresses.firstOrNull { it != addresses.first() }
    return if (ttl != null && hop != null) PingReply(sequence, elapsedMs, null, hop, "ttl-expired") else PingReply(sequence, null, null, null, null)
}

actual suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int?, size: Int?): PingReply = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    val output = runCatching {
        val process = ProcessBuilder(pingCommand(host, timeoutMs, ttl, size)).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader(if (isWindows) Charsets.ISO_8859_1 else Charsets.UTF_8).use(BufferedReader::readText)
        withTimeoutOrNull((timeoutMs + 2000).toLong().milliseconds) {
            while (process.isAlive) delay(20.milliseconds)
        } ?: process.destroy()
        text
    }.getOrElse { return@withContext PingReply(sequence, null, null, null, it.message ?: "ping failed") }
    parsePing(output, sequence, ttl, isWindows && host.contains(':'), (System.nanoTime() - started) / 1_000_000.0)
}

actual suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
    runCatching {
        Socket().use { socket ->
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - started) / 1_000_000
        }
    }.getOrNull()
}

actual suspend fun resolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
    runCatching { InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
}

actual suspend fun reverseLookup(address: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val inet = InetAddress.getByName(address)
        val name = inet.canonicalHostName
        if (name == address || name == inet.hostAddress) null else name
    }.getOrNull()
}

actual suspend fun wakeOnLan(mac: String, broadcast: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = mac.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }
        require(bytes.size == 6)
        val packet = ByteArray(6 + 16 * 6) { i -> if (i < 6) 0xFF.toByte() else bytes[(i - 6) % 6] }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port))
        }
        true
    }.getOrDefault(false)
}

actual suspend fun networkInterfaces(): List<InterfaceInfo> = withContext(Dispatchers.IO) {
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { nif ->
            InterfaceInfo(
                name = nif.name,
                displayName = nif.displayName,
                mac = runCatching { nif.hardwareAddress?.hexColon() }.getOrNull(),
                addresses = nif.interfaceAddresses.map { ia -> "${ia.address.hostAddress?.substringBefore('%') ?: ""}/${ia.networkPrefixLength}" },
                isUp = runCatching { nif.isUp }.getOrDefault(false),
                isLoopback = runCatching { nif.isLoopback }.getOrDefault(false),
                mtu = runCatching { nif.mtu }.getOrDefault(0),
            )
        }
    }.getOrDefault(emptyList())
}

private fun ByteArray.hexColon(): String = joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

actual suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int, serverName: String): TlsInfo = withContext(Dispatchers.IO) {
    val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()
    var trust = CertTrust.UNTRUSTED
    val inspecting = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            trust = try {
                system.checkServerTrusted(chain, authType)
                CertTrust.TRUSTED
            } catch (e: CertificateException) {
                val causes = generateSequence<Throwable>(e) { it.cause }.toList()
                when {
                    causes.any { it is CertificateExpiredException } -> CertTrust.EXPIRED
                    causes.any { it is CertificateNotYetValidException } -> CertTrust.NOT_YET_VALID
                    else -> CertTrust.UNTRUSTED
                }
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = Unit

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(inspecting), null) }
    Socket().use { raw ->
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        (context.socketFactory.createSocket(raw, serverName, port, true) as SSLSocket).use {
            runCatching {
                val params = it.sslParameters
                params.serverNames = listOf(SNIHostName(serverName))
                it.sslParameters = params
            }
            it.startHandshake()
            val session = it.session
            val now = Instant.now()
            val chain = session.peerCertificates.filterIsInstance<X509Certificate>().map { cert ->
                val notAfter = cert.notAfter.toInstant()
                CertificateInfo(
                    subject = cert.subjectX500Principal.name,
                    issuer = cert.issuerX500Principal.name,
                    notBefore = cert.notBefore.toInstant().toString(),
                    notAfter = notAfter.toString(),
                    serial = cert.serialNumber.toString(16).uppercase(),
                    signatureAlgorithm = cert.sigAlgName,
                    publicKey = cert.publicKey.algorithm + " " + runCatching {
                        when (val key = cert.publicKey) {
                            is RSAPublicKey -> "${key.modulus.bitLength()} bit"
                            is ECPublicKey -> "${key.params.curve.field.fieldSize} bit"
                            else -> ""
                        }
                    }.getOrDefault(""),
                    subjectAltNames = runCatching { cert.subjectAlternativeNames?.mapNotNull { san -> san.getOrNull(1)?.toString() } }.getOrNull() ?: emptyList(),
                    sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).hexColon(),
                    sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded).hexColon(),
                    version = cert.version,
                    expired = notAfter.isBefore(now),
                    daysLeft = ChronoUnit.DAYS.between(now, notAfter),
                )
            }
            TlsInfo(session.protocol, session.cipherSuite, chain, trust)
        }
    }
}

actual suspend fun tcpExchange(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (out.size() < MAX_EXCHANGE_BYTES) {
                val read = socket.getInputStream().read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        } catch (e: SocketTimeoutException) {
            if (out.size() == 0) throw e
        }
        out.toByteArray()
    }
}

actual suspend fun udpQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length)
        }
    }.getOrNull()
}

actual suspend fun udpExchanges(targets: List<Pair<String, Int>>, payload: ByteArray, timeoutMs: Int): List<ByteArray?> = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        socket.soTimeout = timeoutMs
        targets.map { (host, port) ->
            runCatching {
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(payload, payload.size, address, port))
                val buffer = ByteArray(2048)
                var reply: ByteArray? = null
                while (reply == null) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.address == address) reply = buffer.copyOf(packet.length)
                }
                reply
            }.getOrNull()
        }
    }
}

actual suspend fun browserIceCandidates(stunServers: List<String>, timeoutMs: Int): List<String> = emptyList()

actual suspend fun browserJsonp(url: String, timeoutMs: Int): String? = null

actual suspend fun tcpDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            exchangeFramed(socket, payload)
        }
    }.getOrNull()
}

actual suspend fun tlsDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
    Socket().use { raw ->
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        (SSLContext.getDefault().socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { socket ->
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                runCatching { serverNames = listOf(SNIHostName(host)) }
            }
            socket.startHandshake()
            if (isAndroid && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
                throw SSLPeerUnverifiedException(host)
            }
            exchangeFramed(socket, payload)
        }
    }
}

actual suspend fun quicDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
    QuicDnsExchange(host, port, timeoutMs).run(payload)
}

// length and message in one write (RFC 7766, 8): on Android writeShort sends two one-byte TLS records,
// and dns.comss.one closes the connection on them
private fun exchangeFramed(socket: Socket, payload: ByteArray): ByteArray {
    socket.getOutputStream().apply {
        write(byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload)
        flush()
    }
    val input = DataInputStream(socket.getInputStream())
    return ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
}

actual suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
    val request = SSDP_SEARCH.toByteArray()
    val found = LinkedHashMap<String, DiscoveredDevice>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 800
            val target = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(request, request.size, target, 1900)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val headers = ssdpHeaders(String(buffer, 0, packet.length, Charsets.ISO_8859_1))
                val address = packet.address.hostAddress ?: continue
                val key = address + "|" + (headers["USN"] ?: headers["LOCATION"] ?: "")
                if (key !in found) found[key] = DiscoveredDevice(address, headers)
            }
        }
    }
    found.values.toList()
}

actual suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray> = withContext(Dispatchers.IO) {
    val responses = mutableListOf<ByteArray>()
    runCatching {
        val group = InetAddress.getByName("224.0.0.251")
        MulticastSocket(5353).use { socket ->
            socket.soTimeout = 700
            runCatching { socket.joinGroup(InetSocketAddress(group, 5353), null) }
            val query = mdnsQuestion(serviceName, 12)
            socket.send(DatagramPacket(query, query.size, group, 5353))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                responses.add(buffer.copyOf(packet.length))
            }
            runCatching { socket.leaveGroup(InetSocketAddress(group, 5353), null) }
        }
    }
    responses
}
