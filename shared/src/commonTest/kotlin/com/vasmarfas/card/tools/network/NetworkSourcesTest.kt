package com.vasmarfas.card.tools.network

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DohRequestTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun normalizesEndpoint() {
        assertEquals("https://dns.example.com/dns-query", DohRequest.normalize("  dns.example.com/dns-query/ "))
        assertEquals("https://dns.comss.one/dns-query", DohRequest.normalize("https://dns.comss.one/dns-query"))
        assertEquals("http://10.0.0.1/dns-query", DohRequest.normalize("http://10.0.0.1/dns-query"))
    }

    @Test
    fun buildsJsonUrl() {
        assertEquals(
            "https://dns.google/resolve?name=example.com&type=28",
            DohRequest.jsonUrl("https://dns.google/resolve", "example.com.", 28),
        )
        assertEquals(
            "https://freedns.controld.com/p0?x=1&name=example.com&type=1",
            DohRequest.jsonUrl("https://freedns.controld.com/p0?x=1", "example.com", 1),
        )
        val unicode = DohRequest.jsonUrl("https://dns.example.com/q", "пример.рф", 1)
        assertTrue(unicode.startsWith("https://dns.example.com/q?name=%"))
        assertFalse(unicode.contains('п'))
    }

    @Test
    fun buildsWireUrl() {
        val query = DnsMessage.buildQuery("example.com", 1, 0x1234)
        val url = DohRequest.wireUrl("https://dns.mullvad.net/dns-query", query)
        assertTrue(url.startsWith("https://dns.mullvad.net/dns-query?dns="))
        val encoded = url.substringAfter("dns=")
        assertFalse(encoded.contains('='))
        assertContentEquals(query, base64.decode(encoded))
    }

    @Test
    fun wireRoundTrip() {
        val query = DnsMessage.buildQuery("example.com", 1, 0x1234)
        val encoded = DohRequest.wireUrl("https://dns.example.com/dns-query", query).substringAfter("dns=")
        val decoded = base64.decode(encoded)
        val message = decoded.copyOf(decoded.size - 11)
        val opt = decoded.copyOfRange(decoded.size - 11, decoded.size)
        message[2] = 0x81.toByte()
        message[3] = 0xA0.toByte()
        message[7] = 1
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0x0E, 0x10, 0, 4, 93, 184.toByte(), 216.toByte(), 34)
        val response = DnsMessage.parse(message + answer + opt)
        assertEquals(0x1234, response.id)
        assertEquals("NOERROR", response.rcodeName)
        assertEquals(1, response.answers.size)
        assertEquals("example.com", response.answers[0].name)
        assertEquals("93.184.216.34", response.answers[0].data)
        assertEquals(3600L, response.answers[0].ttl)
        assertTrue(response.authenticData)
        assertFalse(response.authoritative)
        assertTrue(response.additional.isEmpty())
    }

    @Test
    fun customResolversSurviveJson() {
        val serializer = ListSerializer(CustomResolver.serializer())
        val list = listOf(
            CustomResolver("Home", "https://dns.home.lan/dns-query", DohFlavour.WIRE),
            CustomResolver("Work", "https://dns.work.example/resolve"),
        )
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }
}

class SpeedSourceTest {
    @Test
    fun cloudflareEndpoints() {
        assertEquals("https://speed.cloudflare.com/__down?bytes=0", SpeedEndpoints.latency(cloudflareSpeedSource))
        assertEquals("https://speed.cloudflare.com/__down?bytes=25000000", SpeedEndpoints.download(cloudflareSpeedSource, 25_000_000, 7))
        assertEquals("https://speed.cloudflare.com/__up", SpeedEndpoints.upload(cloudflareSpeedSource))
        assertFalse(SpeedEndpoints.headLatency(cloudflareSpeedSource))
    }

    @Test
    fun selfHostedAndFileEndpoints() {
        val server = SpeedSource("Home", SpeedSourceKind.OPENSPEEDTEST, "http://192.168.1.10:3000/")
        assertEquals("http://192.168.1.10:3000/downloading?r=42", SpeedEndpoints.download(server, 10_000_000, 42))
        assertEquals("http://192.168.1.10:3000/upload", SpeedEndpoints.upload(server))
        assertTrue(SpeedEndpoints.headLatency(server))

        val file = SpeedSource("Mirror", SpeedSourceKind.FILE, "mirror.example.com/100mb.bin")
        assertEquals("https://mirror.example.com/100mb.bin", SpeedEndpoints.download(file, 10_000_000, 42))
        assertNull(SpeedEndpoints.upload(file))

        val browser = SpeedSource("Page", SpeedSourceKind.BROWSER, "https://example.com/speed")
        assertNull(SpeedEndpoints.latency(browser))
        assertNull(SpeedEndpoints.download(browser, 10_000_000, 42))

        val yandex = builtInSpeedSources.first { it.kind == SpeedSourceKind.YANDEX }
        assertNull(SpeedEndpoints.latency(yandex))
        assertNull(SpeedEndpoints.download(yandex, 10_000_000, 42))
        assertNull(SpeedEndpoints.upload(yandex))
    }

    @Test
    fun latencyMedianAndJitter() {
        val stats = latencyStats(listOf(20.0, 30.0, 10.0))
        assertEquals(20.0, stats.medianMs, 1e-9)
        assertEquals(15.0, stats.jitterMs, 1e-9)
        assertEquals(25.0, latencyStats(listOf(10.0, 20.0, 30.0, 40.0)).medianMs, 1e-9)
        assertEquals(0.0, latencyStats(listOf(12.0)).jitterMs, 1e-9)
        assertEquals(0.0, latencyStats(emptyList()).medianMs, 1e-9)
    }

    @Test
    fun customSourcesSurviveJson() {
        val serializer = ListSerializer(SpeedSource.serializer())
        val list = listOf(SpeedSource("Office", SpeedSourceKind.OPENSPEEDTEST, "http://10.0.0.5:3000"))
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }

    @Test
    fun speedFromBytesAndTime() {
        assertEquals(100.0, speedMbps(12_500_000, 1000), 1e-9)
        assertEquals(8.0, speedMbps(1_000_000, 1000), 1e-9)
        assertEquals(16.0, speedMbps(1_000_000, 500), 1e-9)
        assertEquals(0.0, speedMbps(0, 1000), 1e-9)
        assertEquals(0.0, speedMbps(1_000_000, 0), 1e-9)
    }
}

class YandexProbesTest {
    private val body = """
        {
          "mid": "8g7n2aixiafvkyd2x1789569319",
          "lid": ["1736", "1696", "1623"],
          "latency": {"probes": [{"url": "https://ext-cloudcdn.cdn.yandex.net/h1/ping?mid=8g7&lid=1736"}]},
          "download": {"probes": [
            {"url": "https://ext-cloudcdn.cdn.yandex.net/h1/probes/50mb?lid=1736&mid=8g7"},
            {"url": "https://ext-cloudcdn.cdn.yandex.net/h1/probes/100kb?lid=1736&mid=8g7&timeout=100", "timeout": 100}
          ]},
          "upload": {"probes": [
            {
              "size": 30720,
              "url": "https://ext-cloudcdn.cdn.yandex.net/h1/upload?mid=8g7&size=30720",
              "websocketUrl": "wss://ext-cloudcdn.cdn.yandex.net/h1/upload-ws?mid=8g7",
              "websocketConnectionTimeout": 2000,
              "postUrl": "https://ext-cloudcdn.cdn.yandex.net/h1/upload-http?type=upload&sid=1&mid=8g7",
              "statsUrl": "https://ext-cloudcdn.cdn.yandex.net/h1/upload-http?type=stats&sid=1&mid=8g7",
              "timeout": 100
            },
            {
              "size": 8000000,
              "url": "https://ext-cloudcdn.cdn.yandex.net/h1/upload?mid=8g7&size=8000000",
              "postUrl": "https://ext-cloudcdn.cdn.yandex.net/h1/upload-http?type=upload&sid=2&mid=8g7",
              "statsUrl": "https://ext-cloudcdn.cdn.yandex.net/h1/upload-http?type=stats&sid=2&mid=8g7"
            }
          ]},
          "perfLog": {"enabled": true, "entries": []}
        }
    """.trimIndent()

    @Test
    fun parsesGetProbes() {
        val probes = YandexSpeedTest.parse(body)
        assertEquals("8g7n2aixiafvkyd2x1789569319", probes.mid)
        assertEquals(listOf("1736", "1696", "1623"), probes.lid)
        assertEquals(1, probes.latency.probes.size)
        assertEquals(2, probes.download.probes.size)
        assertEquals(2, probes.upload.probes.size)
        assertEquals("https://ext-cloudcdn.cdn.yandex.net/h1/ping?mid=8g7&lid=1736", YandexSpeedTest.latencyUrl(probes))
        assertEquals(30720, probes.upload.probes[0].size)
    }

    @Test
    fun dropsWarmupProbes() {
        val probes = YandexSpeedTest.parse(body)
        assertEquals(
            listOf("https://ext-cloudcdn.cdn.yandex.net/h1/probes/50mb?lid=1736&mid=8g7"),
            YandexSpeedTest.downloadUrls(probes),
        )
        assertEquals(1, YandexSpeedTest.uploadProbes(probes).size)
        val upload = YandexSpeedTest.uploadProbe(probes)
        assertEquals(8_000_000, upload?.size)
        assertEquals("https://ext-cloudcdn.cdn.yandex.net/h1/upload-http?type=upload&sid=2&mid=8g7", upload?.postUrl)
    }

    @Test
    fun fallsBackWhenOnlyWarmupProbesArrive() {
        val probes = YandexSpeedTest.parse(
            """{"upload": {"probes": [{"size": 30720, "postUrl": "https://cdn.example/upload-http?type=upload", "timeout": 100}]}}""",
        )
        assertTrue(YandexSpeedTest.uploadProbes(probes).isEmpty())
        assertEquals(30720, YandexSpeedTest.uploadProbe(probes)?.size)
        assertNull(YandexSpeedTest.latencyUrl(probes))
        assertTrue(YandexSpeedTest.downloadUrls(probes).isEmpty())
    }
}
