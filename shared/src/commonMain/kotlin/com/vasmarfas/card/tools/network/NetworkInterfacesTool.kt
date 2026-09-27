package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.InterfaceInfo
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.hasPermission
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.device.fieldName
import com.vasmarfas.card.tools.device.fieldValue
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow

val networkInterfacesTool = Tool(
    id = "network-interfaces",
    category = ToolCategory.NETWORK,
    title = Res.string.network_interfaces,
    description = Res.string.network_interfaces_description,
    icon = Icons.Filled.SettingsEthernet,
    keywords = listOf("ifconfig", "ipconfig", "wifi", "ssid", "gateway", "dns", "mac", "интерфейсы", "адреса"),
    platforms = PlatformKind.native,
) { NetworkInterfacesScreen() }

@Composable
private fun NetworkInterfacesScreen() {
    var showAll by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var permission by remember { mutableStateOf(hasPermission(AppPermission.LOCATION)) }
    val interfaces by produceState(emptyList<InterfaceInfo>(), refresh) { value = networkInterfaces() }
    val wifi by produceState(emptyMap<String, String>(), refresh, permission) { value = wifiDetails() }

    if (currentPlatform == PlatformKind.ANDROID) {
        ResultCard(title = Res.string.connection.str()) {
            if (!permission) {
                PermissionPrompt(AppPermission.LOCATION, Res.string.iface_ssid_and_bssid_require.str()) {
                    permission = true
                    refresh++
                }
            }
            wifi.forEach { (k, v) ->
                if (v.isNotBlank()) {
                    val unit = when (k) {
                        "Downstream", "Upstream", "Link speed" -> Res.string.unit_mbit_s.str()
                        "Frequency" -> Res.string.unit_mhz.str()
                        "RSSI" -> Res.string.unit_dbm.str()
                        else -> null
                    }
                    KeyValueRow(fieldName(k), if (unit != null) "$v $unit" else fieldValue(v), mono = k in setOf("BSSID", "Addresses", "DNS", "Gateway"))
                }
            }
        }
    }
    SwitchRow(Res.string.show_inactive_interfaces.str(), showAll, { showAll = it })
    ActionButton(text = Res.string.refresh.str(), onClick = { refresh++ })
    interfaces.filter { showAll || (it.isUp && it.addresses.isNotEmpty()) }.forEach { nif ->
        ResultCard(title = "${nif.name}" + (if (nif.displayName != nif.name) " · ${nif.displayName}" else "")) {
            nif.mac?.let { KeyValueRow("MAC", it) }
            nif.addresses.forEach { KeyValueRow(if (it.contains(':')) "IPv6" else "IPv4", it) }
            KeyValueRow("MTU", nif.mtu.toString(), copyable = false)
            val state = (if (nif.isUp) Res.string.iface_up.str() else Res.string.iface_down.str()) + (if (nif.isLoopback) " · " + Res.string.iface_loopback.str() else "")
            KeyValueRow(Res.string.state.str(), state, mono = false, copyable = false)
        }
    }
}
