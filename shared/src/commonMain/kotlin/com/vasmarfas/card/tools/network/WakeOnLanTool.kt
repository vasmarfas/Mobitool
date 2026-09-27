package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.wakeOnLan
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.resources.getString

val wakeOnLanTool = Tool(
    id = "wake-on-lan",
    category = ToolCategory.NETWORK,
    title = Res.string.wake_on_lan,
    description = Res.string.wake_on_lan_description,
    icon = Icons.Filled.PowerSettingsNew,
    keywords = listOf("wol", "magic packet", "wake", "включить", "пробуждение"),
    platforms = PlatformKind.jvm,
) { WakeOnLanScreen() }

@Serializable
data class WolDevice(val name: String, val mac: String, val broadcast: String, val port: Int)

private object WolStore {
    private const val KEY = "wol.devices"
    private val serializer = ListSerializer(WolDevice.serializer())

    fun load(): List<WolDevice> = runCatching { Prefs.store.get(KEY)?.let { Net.json.decodeFromString(serializer, it) } }.getOrNull() ?: emptyList()

    fun save(list: List<WolDevice>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, list))
}

@Composable
private fun WakeOnLanScreen() {
    val failedToSendThePacketText = Res.string.failed_to_send_the_packet.str()
    var name by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf("") }
    var broadcast by rememberSaveable { mutableStateOf("255.255.255.255") }
    var port by rememberSaveable { mutableStateOf("9") }
    var devices by remember { mutableStateOf(WolStore.load()) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val normalized = MacAddress.normalize(mac)

    fun send(device: WolDevice) {
        status = null; error = null
        scope.launch {
            val ok = wakeOnLan(device.mac, device.broadcast, device.port)
            if (ok) status = getString(Res.string.magic_packet_sent_to, device.mac, "${device.broadcast}:${device.port}")
            else error = failedToSendThePacketText
        }
    }

    ToolInputField(value = name, onValueChange = { name = it }, label = Res.string.device_name_optional.str())
    ToolInputField(value = mac, onValueChange = { mac = it }, label = Res.string.mac_address.str(), isError = mac.isNotBlank() && normalized == null, monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolInputField(value = broadcast, onValueChange = { broadcast = it }, label = Res.string.broadcast_address.str(), modifier = Modifier.weight(2f), keyboardType = KeyboardType.Uri, monospace = true)
        NumberField(value = port, onValueChange = { port = it }, label = Res.string.port.str(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.wake.str(), onClick = { send(WolDevice(name, MacAddress.colon(normalized!!), broadcast, port.toIntOrNull() ?: 9)) }, enabled = normalized != null)
        TextButton(
            onClick = {
                val device = WolDevice(name.ifBlank { mac }, MacAddress.colon(normalized!!), broadcast, port.toIntOrNull() ?: 9)
                devices = devices.filter { it.mac != device.mac } + device
                WolStore.save(devices)
            },
            enabled = normalized != null,
        ) { Text(Res.string.save.str()) }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    error?.let { ErrorText(it) }
    if (devices.isNotEmpty()) {
        ResultCard(title = Res.string.saved_devices.str()) {
            devices.forEach { device ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        KeyValueRow(device.name, "${device.mac} · ${device.broadcast}:${device.port}", copyable = false)
                    }
                    TextButton(onClick = { send(device) }) { Text(Res.string.wake.str()) }
                    TextButton(onClick = { devices = devices - device; WolStore.save(devices) }) { Text("✕") }
                }
            }
        }
    }
}
