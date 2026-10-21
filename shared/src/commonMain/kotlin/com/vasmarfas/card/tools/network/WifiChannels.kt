package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Wifi
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.WifiBand
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ChannelLoad(val channel: Int, val networks: Int, val power: Double)

fun powerDbm(power: Double): Int? = if (power <= 0) null else (10 * log10(power)).roundToInt()

class ChannelChoice(val channels: List<Int>, val load: Double, val networks: Int, val dfs: Boolean) {
    val label: String get() = if (channels.size == 1) channels.single().toString() else "${channels.first()}–${channels.last()}"
}

object WifiChannels {
    private val channels5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)

    fun channels(band: WifiBand): List<Int> = when (band) {
        WifiBand.GHZ_2_4 -> (1..13).toList()
        WifiBand.GHZ_5 -> channels5
        WifiBand.GHZ_6 -> (1..233 step 4).toList()
    }

    fun isDfs(channel: Int): Boolean = channel in 52..144

    fun span(point: WifiAccessPoint): ClosedFloatingPointRange<Double> {
        val half = if (point.band == WifiBand.GHZ_2_4 && point.width == 20) 11.0 else point.width / 2.0
        return (point.centerFrequency - half)..(point.centerFrequency + half)
    }

    fun channelSpan(channel: Int, band: WifiBand): ClosedFloatingPointRange<Double> {
        val center = Wifi.frequency(channel, band).toDouble()
        val half = if (band == WifiBand.GHZ_2_4) 11.0 else 10.0
        return (center - half)..(center + half)
    }

    private fun overlap(a: ClosedFloatingPointRange<Double>, b: ClosedFloatingPointRange<Double>): Double =
        max(0.0, min(a.endInclusive, b.endInclusive) - max(a.start, b.start))

    private fun sameRouter(a: WifiAccessPoint, b: WifiAccessPoint): Boolean =
        a.ssid.isNotEmpty() && a.ssid == b.ssid || a.bssid.length == 17 && b.bssid.length == 17 && a.bssid.substring(3, 14) == b.bssid.substring(3, 14)

    fun load(points: List<WifiAccessPoint>, band: WifiBand, own: WifiAccessPoint?): List<ChannelLoad> {
        val others = points.filter { it.band == band && (own == null || !sameRouter(it, own)) }
        return channels(band).map { channel ->
            val range = channelSpan(channel, band)
            var power = 0.0
            var networks = 0
            for (point in others) {
                val span = span(point)
                val shared = overlap(range, span)
                if (shared <= 0) continue
                networks++
                power += 10.0.pow(point.rssi / 10.0) * shared / (span.endInclusive - span.start)
            }
            ChannelLoad(channel, networks, power)
        }
    }

    fun choices(points: List<WifiAccessPoint>, band: WifiBand, own: WifiAccessPoint?): List<ChannelChoice> {
        val load = load(points, band, own).associateBy { it.channel }
        val blocks = when (band) {
            WifiBand.GHZ_2_4 -> listOf(listOf(1), listOf(6), listOf(11))
            WifiBand.GHZ_5 -> channels5.dropLast(1).chunked(4)
            WifiBand.GHZ_6 -> channels(band).chunked(4).filter { it.size == 4 }
        }
        return blocks.map { block ->
            ChannelChoice(block, block.sumOf { load[it]?.power ?: 0.0 }, block.maxOf { load[it]?.networks ?: 0 }, block.any(::isDfs))
        }.sortedWith(compareBy<ChannelChoice> { it.load }.thenBy { it.dfs })
    }
}
