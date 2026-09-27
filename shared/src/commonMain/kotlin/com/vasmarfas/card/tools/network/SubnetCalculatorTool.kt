package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
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
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField

private enum class IpVersion { V4, V6 }

val subnetCalculatorTool = Tool(
    id = "subnet-calculator",
    category = ToolCategory.NETWORK,
    title = Res.string.subnet_calculator,
    description = Res.string.subnet_calculator_description,
    icon = Icons.Filled.AccountTree,
    keywords = listOf("cidr", "ip", "mask", "netmask", "vlsm", "маска", "подсеть", "ipv6"),
) { SubnetCalculatorScreen() }

@Composable
private fun SubnetCalculatorScreen() {
    var version by rememberSaveable { mutableStateOf(IpVersion.V4) }
    var input by rememberSaveable { mutableStateOf("192.168.1.10/24") }
    var input6 by rememberSaveable { mutableStateOf("2001:db8::1/64") }
    SegmentedChoice(
        options = IpVersion.entries,
        selected = version,
        onSelect = { version = it },
        label = { if (it == IpVersion.V4) "IPv4" else "IPv6" },
    )
    when (version) {
        IpVersion.V4 -> {
            val subnet = remember(input) { Ipv4.parseSubnet(input) }
            ToolInputField(
                value = input,
                onValueChange = { input = it },
                label = Res.string.address_prefix_or_mask.str(),
                placeholder = "10.0.0.1/8 · 172.16.5.4 255.255.240.0",
                keyboardType = KeyboardType.Uri,
                isError = input.isNotBlank() && subnet == null,
                monospace = true,
            )
            if (subnet != null) Ipv4Result(subnet)
        }

        IpVersion.V6 -> {
            val parsed = remember(input6) { Ipv6Address.parseWithPrefix(input6) }
            ToolInputField(
                value = input6,
                onValueChange = { input6 = it },
                label = Res.string.address_prefix.str(),
                placeholder = "fe80::1/64",
                keyboardType = KeyboardType.Uri,
                isError = input6.isNotBlank() && parsed == null,
                monospace = true,
            )
            if (parsed != null) Ipv6Result(parsed.first, parsed.second)
        }
    }
}

@Composable
private fun Ipv4Result(s: Ipv4Subnet) {
    ResultCard {
        KeyValueRow(Res.string.network.str(), "${Ipv4.format(s.network)}/${s.prefix}")
        KeyValueRow(Res.string.netmask.str(), Ipv4.format(s.mask))
        KeyValueRow(Res.string.wildcard.str(), Ipv4.format(s.wildcard))
        KeyValueRow(Res.string.broadcast.str(), if (s.prefix >= 31) "—" else Ipv4.format(s.broadcast))
        KeyValueRow(Res.string.host_range.str(), "${Ipv4.format(s.firstHost)} — ${Ipv4.format(s.lastHost)}")
        KeyValueRow(Res.string.usable_hosts.str(), s.usableHosts.fmtGrouped())
        KeyValueRow(Res.string.total_addresses.str(), s.totalAddresses.fmtGrouped())
    }
    ResultCard(Res.string.address.str()) {
        KeyValueRow(Res.string.decimal_format.str(), s.address.toString())
        KeyValueRow(Res.string.hex.str(), Ipv4.hex(s.address))
        KeyValueRow(Res.string.binary.str(), Ipv4.binary(s.address))
        KeyValueRow(Res.string.mask_binary.str(), Ipv4.binary(s.mask))
        KeyValueRow(Res.string.reverse_dns.str(), Ipv4.ptrName(s.address))
        val type = buildList {
            add(Res.string.class_.str() + " " + s.ipClass)
            if (s.isPrivate) add(Res.string.private_rfc_1918.str())
            if (s.isLoopback) add("loopback")
            if (s.isLinkLocal) add("link-local (APIPA)")
            if (s.isCgnat) add("CGNAT (RFC 6598)")
            if (s.isMulticast) add("multicast")
            if (s.isThisNetwork) add(Res.string.ip_this_network.str())
            if (s.isReserved) add(Res.string.ip_reserved.str())
            if (s.isBroadcast) add(Res.string.ip_limited_broadcast.str())
            if (s.isPublic) add(Res.string.public.str())
        }.joinToString(", ")
        KeyValueRow(Res.string.type.str(), type, mono = false, copyable = false)
    }
}

@Composable
private fun Ipv6Result(address: Ipv6Address, prefix: Int) {
    val network = address.withPrefix(prefix)
    val last = address.lastInPrefix(prefix)
    ResultCard {
        KeyValueRow(Res.string.compressed.str(), address.compressed())
        KeyValueRow(Res.string.expanded.str(), address.expanded())
        KeyValueRow(Res.string.network.str(), "${network.compressed()}/$prefix")
        KeyValueRow(Res.string.first_address.str(), network.expanded())
        KeyValueRow(Res.string.last_address.str(), last.expanded())
        val hostBits = 128 - prefix
        val count = if (hostBits >= 63) "2^$hostBits" else (1L shl hostBits).fmtGrouped()
        KeyValueRow(Res.string.addresses.str(), count)
        KeyValueRow(Res.string.type.str(), address.type, mono = false, copyable = false)
    }
}
