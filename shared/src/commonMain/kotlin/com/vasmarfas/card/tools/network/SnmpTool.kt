package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

val snmpTool = Tool(
    id = "snmp-client",
    category = ToolCategory.NETWORK,
    title = Res.string.snmp_client,
    description = Res.string.snmp_client_description,
    icon = Icons.Filled.DeviceHub,
    keywords = listOf("snmp", "mib", "oid", "walk", "snmpwalk", "community", "ifTable", "sysDescr", "счётчики", "интерфейсы", "коммутатор", "роутер"),
    platforms = PlatformKind.native,
    expandable = true,
) { SnmpScreen() }

private enum class SnmpMode { GET, WALK, INTERFACES }

@Composable
private fun bindingText(binding: SnmpVarBind): String {
    if (binding.tag != Snmp.TIMETICKS) return binding.text
    val seconds = binding.number.toLong() / 100
    val days = seconds / 86_400
    val clock = listOf(seconds / 3600 % 24, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
    return pluralStringResource(Res.plurals.snmp_uptime, pluralQuantity(days), days.toString(), clock)
}

@Composable
private fun speedText(bits: ULong): String =
    if (bits >= 1_000_000_000uL) "${(bits.toDouble() / 1e9).fmt(2)} ${Res.string.unit_gbit_s.str()}" else "${(bits.toDouble() / 1e6).fmt(2)} ${Res.string.unit_mbit_s.str()}"

@Composable
private fun SnmpScreen() {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("161") }
    // plain remember: the community must not reach saved state
    var community by remember { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf(SnmpVersion.V2C) }
    var mode by rememberSaveable { mutableStateOf(SnmpMode.GET) }
    var getOid by rememberSaveable { mutableStateOf("sysDescr.0") }
    var walkOid by rememberSaveable { mutableStateOf("system") }
    var timeout by rememberSaveable { mutableStateOf("2000") }
    var limit by rememberSaveable { mutableStateOf("500") }
    val rows = remember { mutableStateListOf<SnmpVarBind>() }
    var interfaces by remember { mutableStateOf(emptyList<SnmpInterface>()) }
    var end by remember { mutableStateOf<SnmpWalkEnd?>(null) }
    var shownLimit by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val oid = if (mode == SnmpMode.GET) getOid else walkOid
    val resolved = Snmp.resolve(oid)
    val number = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = host.isNotBlank() && community.isNotEmpty() && number != null && (mode == SnmpMode.INTERFACES || resolved != null)

    fun start() {
        val target = hostFrom(host)
        val wait = timeout.toIntOrNull()?.coerceIn(200, 30_000) ?: 2_000
        val most = limit.toIntOrNull()?.coerceIn(1, 2_000) ?: 500
        val oids = Snmp.resolve(if (mode == SnmpMode.GET) getOid else walkOid).orEmpty()
        val session = SnmpSession(version, community) { udpQuery(target, port.toIntOrNull() ?: 161, it, wait) }
        error = null; end = null; rows.clear(); interfaces = emptyList(); shownLimit = most; running = true
        job = scope.launch {
            try {
                when (mode) {
                    SnmpMode.GET -> rows += session.get(oids)
                    SnmpMode.WALK -> end = session.walk(oids, most) { rows += it }
                    SnmpMode.INTERFACES -> {
                        interfaces = session.interfaces(most)
                        end = if (interfaces.size >= most) SnmpWalkEnd.LIMIT else SnmpWalkEnd.DONE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = networkErrorText(e)
            }
            running = false
        }
    }

    ToolInputField(host, { host = it }, Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(port, { port = it }, Res.string.port.str(), modifier = Modifier.weight(1f), isError = number == null)
        ToolInputField(community, { community = it }, Res.string.snmp_community.str(), modifier = Modifier.weight(2f), placeholder = "public", monospace = true)
    }
    SegmentedChoice(SnmpVersion.entries, version, { version = it }, { it.label })
    SegmentedChoice(
        SnmpMode.entries,
        mode,
        { mode = it },
        {
            when (it) {
                SnmpMode.GET -> "Get"
                SnmpMode.WALK -> "Walk"
                SnmpMode.INTERFACES -> Res.string.snmp_interfaces.str()
            }
        },
    )
    if (mode != SnmpMode.INTERFACES) {
        ToolInputField(
            oid,
            { if (mode == SnmpMode.GET) getOid = it else walkOid = it },
            Res.string.snmp_oid.str(),
            monospace = true,
            isError = oid.isNotBlank() && resolved == null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == SnmpMode.GET) {
                listOf("sysDescr.0", "sysUpTime.0", "sysName.0").forEach { TextButton(onClick = { getOid = it }) { Text(it) } }
            } else {
                listOf("system", "ifTable", "ifXTable").forEach { TextButton(onClick = { walkOid = it }) { Text(it) } }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(timeout, { timeout = it }, Res.string.timeout_ms.str(), modifier = Modifier.weight(1f))
        if (mode != SnmpMode.GET) NumberField(limit, { limit = it }, Res.string.snmp_limit.str(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(Res.string.start.str(), onClick = ::start, enabled = ready && !running)
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(Res.string.stop_short.str()) }
    }
    Hint(Res.string.snmp_hint.str())
    if (running) LoadingRow()
    error?.let { ErrorText(it) }
    if (rows.isNotEmpty()) {
        ResultCard(stringResource(Res.string.snmp_rows, rows.size)) {
            rows.forEach { KeyValueRow(Snmp.name(it.oid), bindingText(it)) }
        }
    }
    if (interfaces.isNotEmpty()) {
        ResultCard(stringResource(Res.string.snmp_rows, interfaces.size)) {
            SimpleTable(
                header = listOf(
                    Res.string.snmp_if_name.str(),
                    "ifIndex",
                    Res.string.snmp_if_type.str(),
                    Res.string.snmp_if_status.str(),
                    Res.string.snmp_if_speed.str(),
                    "MAC",
                    Res.string.snmp_if_in.str(),
                    Res.string.snmp_if_out.str(),
                    Res.string.snmp_if_errors.str(),
                ),
                rows = interfaces.map {
                    listOf(
                        it.name,
                        it.index,
                        it.type,
                        "${it.admin} / ${it.oper}",
                        speedText(it.speedBits),
                        it.mac,
                        formatBytes(it.inOctets.toLong()),
                        formatBytes(it.outOctets.toLong()),
                        "${it.inErrors} / ${it.outErrors}",
                    )
                },
            )
        }
    }
    when (end) {
        SnmpWalkEnd.DONE -> if (rows.isEmpty() && interfaces.isEmpty()) Hint(Res.string.snmp_no_rows.str())
        SnmpWalkEnd.LIMIT -> Hint(stringResource(Res.string.snmp_walk_limit, shownLimit))
        SnmpWalkEnd.LOOP -> ErrorText(Res.string.snmp_walk_loop.str())
        SnmpWalkEnd.SILENCE -> ErrorText(Res.string.snmp_walk_silence.str())
        null -> Unit
    }
}
