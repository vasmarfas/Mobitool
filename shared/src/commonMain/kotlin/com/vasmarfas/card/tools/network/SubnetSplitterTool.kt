package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

val subnetSplitterTool = Tool(
    id = "subnet-splitter",
    category = ToolCategory.NETWORK,
    title = Res.string.subnet_splitter_and_vlsm,
    description = Res.string.subnet_splitter_description,
    icon = Icons.AutoMirrored.Filled.CallSplit,
    keywords = listOf("vlsm", "cidr", "supernet", "aggregate", "range", "разбить", "суммаризация"),
) { SubnetSplitterScreen() }

private enum class SplitMode(val title: StringResource) {
    EQUAL(Res.string.equal_split),
    VLSM(Res.string.vlsm_by_hosts),
    CIDR(Res.string.cidr_to_range),
    RANGE(Res.string.range_to_cidr),
    SUMMARIZE(Res.string.summarize),
}

private const val MAX_SPLIT_ROWS = 256

object SubnetMath {
    fun split(network: Ipv4Subnet, count: Int): Sequence<Ipv4Subnet> {
        if (count.toLong() > network.totalAddresses) return emptySequence()
        var bits = 0
        while ((1L shl bits) < count) bits++
        val prefix = network.prefix + bits
        val size = 1L shl (32 - prefix)
        return (0L until (1L shl bits)).asSequence().map { i -> Ipv4Subnet(network.network + i * size, prefix) }
    }

    fun vlsm(network: Ipv4Subnet, hosts: List<Int>): List<Pair<Int, Ipv4Subnet>>? {
        var cursor = network.network
        val end = network.broadcast + 1
        val result = mutableListOf<Pair<Int, Ipv4Subnet>>()
        for (need in hosts.sortedDescending()) {
            var hostBits = 0
            while ((1L shl hostBits) - 2 < need) hostBits++
            if (hostBits < 2) hostBits = 2
            val prefix = 32 - hostBits
            val size = 1L shl hostBits
            if (cursor + size > end) return null
            result += need to Ipv4Subnet(cursor, prefix)
            cursor += size
        }
        return result
    }

    fun rangeToCidrs(start: Long, end: Long): List<Ipv4Subnet> {
        val out = mutableListOf<Ipv4Subnet>()
        var cur = start
        while (cur <= end) {
            var prefix = 32
            while (prefix > 0) {
                val size = 1L shl (32 - (prefix - 1))
                if (cur % size != 0L || cur + size - 1 > end) break
                prefix--
            }
            out += Ipv4Subnet(cur, prefix)
            cur += 1L shl (32 - prefix)
        }
        return out
    }

    fun summarize(subnets: List<Ipv4Subnet>): List<Ipv4Subnet> {
        if (subnets.isEmpty()) return emptyList()
        val ranges = subnets.map { it.network to it.broadcast }.sortedBy { it.first }
        val merged = mutableListOf(ranges.first())
        for ((s, e) in ranges.drop(1)) {
            val last = merged.last()
            if (s <= last.second + 1) merged[merged.lastIndex] = last.first to maxOf(last.second, e)
            else merged += s to e
        }
        return merged.flatMap { (s, e) -> rangeToCidrs(s, e) }
    }
}

@Composable
private fun SubnetSplitterScreen() {
    var mode by rememberSaveable { mutableStateOf(SplitMode.EQUAL) }
    var network by rememberSaveable { mutableStateOf("192.168.0.0/24") }
    var count by rememberSaveable { mutableStateOf("4") }
    var hosts by rememberSaveable { mutableStateOf("100, 50, 20, 2") }
    var list by rememberSaveable { mutableStateOf("10.0.0.0/24\n10.0.1.0/24\n10.0.2.0/23") }
    var rangeStart by rememberSaveable { mutableStateOf("192.168.1.10") }
    var rangeEnd by rememberSaveable { mutableStateOf("192.168.1.250") }
    var blocks by rememberSaveable { mutableStateOf("10.0.0.0/22\n192.168.1.64/26\n2a02:6b8::/32") }

    ChoiceChips(options = SplitMode.entries, selected = mode, onSelect = { mode = it }, label = { it.title.str() })
    when (mode) {
        SplitMode.EQUAL -> {
            ToolInputField(value = network, onValueChange = { network = it }, label = Res.string.network.str(), keyboardType = KeyboardType.Uri, monospace = true)
            NumberField(value = count, onValueChange = { count = it }, label = Res.string.number_of_subnets.str())
            val net = remember(network) { Ipv4.parseSubnet(network) }
            val n = count.toIntOrNull()
            if (net == null || n == null || n < 1) ErrorText(Res.string.enter_a_network_and_a_count.str())
            else {
                val parts = remember(net, n) { SubnetMath.split(net, n).take(MAX_SPLIT_ROWS).toList() }
                if (parts.isEmpty()) ErrorText(Res.string.too_many_subnets_for_this_prefix.str())
                else {
                    val first = parts.first()
                    val total = 1L shl (first.prefix - net.prefix)
                    val hosts = pluralStringResource(Res.plurals.subnet_hosts_each, pluralQuantity(first.usableHosts), first.usableHosts.fmtGrouped())
                    ResultCard(title = "${total.fmtGrouped()} × /${first.prefix} · $hosts") {
                        SimpleTable(
                            header = listOf(Res.string.network.str(), Res.string.host_addresses.str(), Res.string.broadcast.str()),
                            rows = parts.map { s -> listOf("${Ipv4.format(s.network)}/${s.prefix}", "${Ipv4.format(s.firstHost)} – ${Ipv4.format(s.lastHost)}", Ipv4.format(s.broadcast)) },
                            weights = listOf(1.5f, 2.6f, 1.4f),
                        )
                        if (total > parts.size) Hint(stringResource(Res.string.subnet_rows_capped, parts.size, total.fmtGrouped()))
                    }
                }
            }
        }

        SplitMode.VLSM -> {
            ToolInputField(value = network, onValueChange = { network = it }, label = Res.string.network.str(), keyboardType = KeyboardType.Uri, monospace = true)
            ToolInputField(value = hosts, onValueChange = { hosts = it }, label = Res.string.hosts_per_subnet_comma_separated.str())
            val net = remember(network) { Ipv4.parseSubnet(network) }
            val needs = hosts.split(Regex("[,;\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it > 0 }
            if (net == null || needs.isEmpty()) ErrorText(Res.string.enter_a_network_and_host_counts.str())
            else {
                val plan = SubnetMath.vlsm(net, needs)
                if (plan == null) ErrorText(Res.string.subnet_network_is_too_small.str())
                else ResultCard {
                    SimpleTable(
                        header = listOf(Res.string.hosts_needed.str(), Res.string.network.str(), Res.string.hosts_usable.str(), Res.string.range.str()),
                        rows = plan.map { (need, s) -> listOf(need.toString(), "${Ipv4.format(s.network)}/${s.prefix}", s.usableHosts.toString(), "${Ipv4.format(s.firstHost)} – ${Ipv4.format(s.lastHost)}") },
                        weights = listOf(0.6f, 1.6f, 0.8f, 2.6f),
                    )
                    val used = plan.sumOf { it.second.totalAddresses }
                    KeyValueRow(Res.string.used_total_addresses.str(), "${used.fmtGrouped()} / ${net.totalAddresses.fmtGrouped()}", copyable = false)
                }
            }
        }

        SplitMode.SUMMARIZE -> {
            ToolInputField(value = list, onValueChange = { list = it }, label = Res.string.networks_one_per_line.str(), singleLine = false, minLines = 3, monospace = true)
            val subnets = list.lines().mapNotNull { Ipv4.parseBlock(it) }
            if (subnets.isEmpty()) ErrorText(Res.string.enter_at_least_one_network.str())
            else {
                val result = SubnetMath.summarize(subnets)
                ResultCard(title = "${subnets.size} → ${result.size}") {
                    result.forEach { s -> KeyValueRow("${Ipv4.format(s.network)}/${s.prefix}", "${Ipv4.format(s.network)} – ${Ipv4.format(s.broadcast)} · ${s.totalAddresses.fmtGrouped()}") }
                }
            }
        }

        SplitMode.CIDR -> {
            ToolInputField(value = blocks, onValueChange = { blocks = it }, label = Res.string.networks_one_per_line.str(), singleLine = false, minLines = 3, monospace = true)
            val lines = blocks.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) ErrorText(Res.string.enter_at_least_one_network.str())
            lines.forEach { CidrRange(it) }
        }

        SplitMode.RANGE -> {
            ToolInputField(value = rangeStart, onValueChange = { rangeStart = it }, label = Res.string.first_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
            ToolInputField(value = rangeEnd, onValueChange = { rangeEnd = it }, label = Res.string.last_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
            val s = Ipv4.parse(rangeStart)
            val e = Ipv4.parse(rangeEnd)
            if (s == null || e == null || e < s) ErrorText(Res.string.enter_a_valid_range.str())
            else {
                val cidrs = SubnetMath.rangeToCidrs(s, e)
                val addresses = pluralStringResource(Res.plurals.subnet_address_count, pluralQuantity(e - s + 1), (e - s + 1).fmtGrouped())
                ResultCard(title = "$addresses · ${cidrs.size} CIDR") {
                    cidrs.forEach { c -> KeyValueRow("${Ipv4.format(c.network)}/${c.prefix}", "${Ipv4.format(c.network)} – ${Ipv4.format(c.broadcast)}") }
                }
            }
        }
    }
}

@Composable
private fun CidrRange(text: String) {
    val v4 = Ipv4.parseBlock(text)
    val v6 = if (v4 == null) Ipv6Address.parseWithPrefix(text) else null
    when {
        v4 != null -> ResultCard("${Ipv4.format(v4.network)}/${v4.prefix}") {
            KeyValueRow(Res.string.range.str(), "${Ipv4.format(v4.network)} – ${Ipv4.format(v4.broadcast)}")
            KeyValueRow(Res.string.total_addresses.str(), v4.totalAddresses.fmtGrouped(), copyable = false)
            KeyValueRow(Res.string.host_range.str(), "${Ipv4.format(v4.firstHost)} – ${Ipv4.format(v4.lastHost)} · ${v4.usableHosts.fmtGrouped()}")
            KeyValueRow(Res.string.netmask.str(), Ipv4.format(v4.mask))
        }

        v6 != null -> {
            val (address, prefix) = v6
            val hostBits = 128 - prefix
            ResultCard("${address.withPrefix(prefix).compressed()}/$prefix") {
                KeyValueRow(Res.string.range.str(), "${address.withPrefix(prefix).compressed()} – ${address.lastInPrefix(prefix).compressed()}")
                KeyValueRow(Res.string.total_addresses.str(), if (hostBits >= 63) "2^$hostBits" else (1L shl hostBits).fmtGrouped(), copyable = false)
            }
        }

        else -> ErrorText(stringResource(Res.string.cidr_not_recognized, text))
    }
}
