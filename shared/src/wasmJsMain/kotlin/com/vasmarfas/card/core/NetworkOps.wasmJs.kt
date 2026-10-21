package com.vasmarfas.card.core

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.js.Promise

actual fun platformNetCapabilities() = PlatformNetCapabilities(
    icmpPing = false,
    tcp = false,
    udp = false,
    interfaces = false,
    dnsOverTls = false,
    dnsOverQuic = false,
)

actual suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int?, size: Int?): PingReply =
    PingReply(sequence, null, null, null, "unsupported")

actual suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? = null

private fun jsOpaqueHead(url: String, timeoutMs: Int): Promise<JsAny?> =
    js("fetch(url, { method: 'HEAD', mode: 'no-cors', cache: 'no-store', signal: AbortSignal.timeout(timeoutMs) })")

actual suspend fun httpPing(url: String, timeoutMs: Int): Long {
    val started = currentEpochMillis()
    jsOpaqueHead(url, timeoutMs).await<JsAny?>()
    return currentEpochMillis() - started
}

actual suspend fun resolveHost(host: String): List<String> = emptyList()

actual suspend fun reverseLookup(address: String): String? = null

actual suspend fun wakeOnLan(mac: String, broadcast: String, port: Int): Boolean = false

actual suspend fun networkInterfaces(): List<InterfaceInfo> = emptyList()

actual suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int, serverName: String): TlsInfo = throw UnsupportedOperationException("unsupported")

actual suspend fun tcpExchange(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = throw UnsupportedOperationException("unsupported")

actual suspend fun udpQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = null

actual suspend fun udpExchanges(targets: List<Pair<String, Int>>, payload: ByteArray, timeoutMs: Int): List<ByteArray?> = targets.map { null }

private fun jsIceCandidates(servers: String, timeoutMs: Int): Promise<JsString> = js("""
new Promise(function (resolve) {
    var found = [];
    var pc;
    try {
        pc = new RTCPeerConnection({ iceServers: JSON.parse(servers).map(function (url) { return { urls: url }; }) });
    } catch (e) {
        resolve('[]');
        return;
    }
    var finish = function () {
        try { pc.close(); } catch (e) {}
        resolve(JSON.stringify(found));
    };
    pc.onicecandidate = function (event) {
        if (event.candidate) found.push(event.candidate.candidate); else finish();
    };
    pc.createDataChannel('probe');
    pc.createOffer().then(function (offer) { return pc.setLocalDescription(offer); }).catch(finish);
    setTimeout(finish, timeoutMs);
})
""")

actual suspend fun browserIceCandidates(stunServers: List<String>, timeoutMs: Int): List<String> = runCatching {
    val json = jsIceCandidates(Net.json.encodeToString(ListSerializer(String.serializer()), stunServers), timeoutMs).await<JsString>().toString()
    Net.json.decodeFromString(ListSerializer(String.serializer()), json)
}.getOrDefault(emptyList())

private fun jsJsonp(url: String, timeoutMs: Int): Promise<JsString> = js("""
new Promise(function (resolve) {
    var name = '__mobitool' + Math.random().toString(36).slice(2);
    var script = document.createElement('script');
    var finished = false;
    var finish = function (value) {
        if (finished) return;
        finished = true;
        delete window[name];
        script.remove();
        resolve(value);
    };
    window[name] = function (data) { finish(JSON.stringify(data)); };
    script.onerror = function () { finish(''); };
    setTimeout(function () { finish(''); }, timeoutMs);
    script.src = url + (url.indexOf('?') < 0 ? '?' : '&') + 'callback=' + name;
    document.head.appendChild(script);
})
""")

actual suspend fun browserJsonp(url: String, timeoutMs: Int): String? = jsJsonp(url, timeoutMs).await<JsString>().toString().ifEmpty { null }

actual suspend fun tcpDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = null

actual suspend fun tlsDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = throw UnsupportedOperationException("unsupported")

actual suspend fun quicDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = throw UnsupportedOperationException("unsupported")

actual suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice> = emptyList()

actual suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray> = emptyList()

actual suspend fun wifiDetails(): Map<String, String> = emptyMap()

actual suspend fun scanWifi(): WifiScan = WifiScan(emptyList(), false, WifiProblem.UNSUPPORTED)

actual suspend fun cellReadings(): CellScan = CellScan(emptyList(), CellProblem.UNSUPPORTED)

actual fun bleAdverts(): Flow<BleAdvert> = flow { throw BleException(BleProblem.UNSUPPORTED) }

actual fun wifiScanLimit(): ScanLimit? = null

actual fun openScanLimitSettings() = Unit
