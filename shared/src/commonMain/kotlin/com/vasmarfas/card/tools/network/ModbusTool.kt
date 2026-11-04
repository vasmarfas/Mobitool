package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material3.AlertDialog
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
import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val modbusTool = Tool(
    id = "modbus-client",
    category = ToolCategory.NETWORK,
    title = Res.string.modbus_client,
    description = Res.string.modbus_client_description,
    icon = Icons.Filled.PrecisionManufacturing,
    keywords = listOf("modbus", "plc", "scada", "coil", "holding register", "502", "плк", "контроллер", "регистры", "автоматика"),
    platforms = PlatformKind.native,
) { ModbusScreen() }

private class ModbusRead(val address: Int, val bits: List<Boolean>, val words: List<Int>)

private class ModbusWrite(val target: String, val port: Int, val unit: Int, val coil: Boolean, val address: Int, val value: Int)

private suspend fun modbusExchange(target: String, port: Int, unit: Int, pdu: ByteArray, wait: Int): ByteArray {
    val transaction = Random.nextInt(1, 0x10000)
    return Modbus.answer(tcpExchange(target, port, Modbus.frame(transaction, unit, pdu), wait), transaction, pdu[0].toInt())
}

private fun hexWord(word: Int): String = word.toString(16).padStart(4, '0').uppercase()

@Composable
private fun ModbusScreen() {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("502") }
    var unit by rememberSaveable { mutableStateOf("1") }
    var table by rememberSaveable { mutableStateOf(ModbusTable.HOLDING_REGISTERS) }
    var address by rememberSaveable { mutableStateOf("0") }
    var quantity by rememberSaveable { mutableStateOf("10") }
    var timeout by rememberSaveable { mutableStateOf("1500") }
    var format by rememberSaveable { mutableStateOf(ModbusFormat.UINT16) }
    var swapWords by rememberSaveable { mutableStateOf(false) }
    var writeCoil by rememberSaveable { mutableStateOf(false) }
    var writeAddress by rememberSaveable { mutableStateOf("0") }
    var writeValue by rememberSaveable { mutableStateOf("0") }
    var coilOn by rememberSaveable { mutableStateOf(false) }
    var read by remember { mutableStateOf<ModbusRead?>(null) }
    var pending by remember { mutableStateOf<ModbusWrite?>(null) }
    var written by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var writeFailed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val unitId = unit.toIntOrNull()?.takeIf { it in 0..255 }
    val from = address.toIntOrNull()?.takeIf { it in 0..65535 }
    val count = quantity.toIntOrNull()?.takeIf { it in 1..table.maxQuantity && (from == null || from + it <= 0x10000) }
    val toAddress = writeAddress.toIntOrNull()?.takeIf { it in 0..65535 }
    val toValue = if (writeCoil) 0 else writeValue.toIntOrNull()?.takeIf { it in -32768..65535 }
    val connectable = host.isNotBlank() && portNumber != null && unitId != null && !running

    fun waitMs() = timeout.toIntOrNull()?.coerceIn(200, 30_000) ?: 1_500

    fun request(block: suspend () -> Unit) {
        error = null; written = null; writeFailed = false; running = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = networkErrorText(e)
            }
            running = false
        }
    }

    fun startRead() {
        val target = hostFrom(host)
        val chosen = table
        val first = from ?: return
        val size = count ?: return
        val station = unitId ?: return
        val number = portNumber ?: return
        val wait = waitMs()
        read = null
        request {
            val answer = modbusExchange(target, number, station, Modbus.readRequest(chosen, first, size), wait)
            read = if (chosen.bits) ModbusRead(first, Modbus.bits(answer, size), emptyList()) else ModbusRead(first, emptyList(), Modbus.registers(answer, size))
        }
    }

    fun confirmWrite(write: ModbusWrite, shown: String) {
        val wait = waitMs()
        val pdu = if (write.coil) Modbus.writeCoil(write.address, write.value != 0) else Modbus.writeRegister(write.address, write.value and 0xFFFF)
        request {
            writeFailed = true
            val answer = modbusExchange(write.target, write.port, write.unit, pdu, wait)
            if (!answer.contentEquals(pdu)) throw NetException(Res.string.modbus_bad_reply)
            writeFailed = false
            written = shown
        }
    }

    ToolInputField(host, { host = it }, Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(port, { port = it }, Res.string.port.str(), modifier = Modifier.weight(1f), isError = portNumber == null)
        NumberField(unit, { unit = it }, Res.string.modbus_unit.str(), modifier = Modifier.weight(1f), isError = unitId == null)
        NumberField(timeout, { timeout = it }, Res.string.timeout_ms.str(), modifier = Modifier.weight(1f))
    }
    ChoiceChips(ModbusTable.entries, table, { table = it }, { it.title.str() })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(address, { address = it }, Res.string.modbus_address.str(), modifier = Modifier.weight(1f), isError = from == null)
        NumberField(quantity, { quantity = it }, stringResource(Res.string.modbus_quantity, table.maxQuantity), modifier = Modifier.weight(1f), isError = count == null)
    }
    ActionButton(Res.string.modbus_read.str(), onClick = ::startRead, enabled = connectable && from != null && count != null)
    Hint(Res.string.modbus_hint.str())
    if (running) LoadingRow()
    error?.let { ErrorText(it) }
    if (writeFailed && !running) Hint(Res.string.modbus_write_check.str())
    read?.let { result ->
        ResultCard {
            if (result.bits.isNotEmpty()) {
                SimpleTable(
                    header = listOf(Res.string.modbus_address.str(), Res.string.modbus_bits.str()),
                    rows = result.bits.chunked(8).mapIndexed { index, chunk ->
                        listOf((result.address + index * 8).toString(), chunk.joinToString(" ") { if (it) "1" else "0" })
                    },
                    weights = listOf(1f, 3f),
                )
            } else {
                SegmentedChoice(ModbusFormat.entries, format, { format = it }, { it.label })
                if (format.words == 2) SwitchRow(Res.string.modbus_swap_words.str(), swapWords, { swapWords = it })
                SimpleTable(
                    header = listOf(Res.string.modbus_address.str(), "hex", Res.string.modbus_value.str()),
                    rows = result.words.chunked(format.words).mapIndexed { index, chunk ->
                        listOf(
                            (result.address + index * format.words).toString(),
                            chunk.joinToString(" ", transform = ::hexWord),
                            if (chunk.size == format.words) Modbus.decode(chunk, format, swapWords) else "",
                        )
                    },
                )
            }
        }
    }
    ToolSection(Res.string.modbus_write_section.str()) {
        SegmentedChoice(listOf(false, true), writeCoil, { writeCoil = it }, { if (it) Res.string.modbus_write_coil.str() else Res.string.modbus_write_register.str() })
        NumberField(writeAddress, { writeAddress = it }, Res.string.modbus_address.str(), isError = toAddress == null)
        if (writeCoil) {
            SwitchRow(Res.string.modbus_coil_on.str(), coilOn, { coilOn = it })
        } else {
            NumberField(writeValue, { writeValue = it }, Res.string.modbus_value.str(), isError = toValue == null)
        }
        ActionButton(
            Res.string.modbus_write.str(),
            onClick = {
                pending = ModbusWrite(hostFrom(host), portNumber ?: 502, unitId ?: 1, writeCoil, toAddress ?: 0, if (writeCoil) (if (coilOn) 1 else 0) else toValue ?: 0)
            },
            enabled = connectable && toAddress != null && toValue != null,
        )
        written?.let { Hint(stringResource(Res.string.modbus_written, it)) }
    }
    pending?.let { write ->
        val kind = if (write.coil) Res.string.modbus_write_coil.str() else Res.string.modbus_write_register.str()
        val shown = if (write.coil) (if (write.value != 0) "ON" else "OFF") else "${write.value} (0x${hexWord(write.value and 0xFFFF)})"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(Res.string.modbus_write_title.str()) },
            text = { Text(stringResource(Res.string.modbus_write_confirm, kind, write.address.toString(), "${write.target}:${write.port}", write.unit.toString(), shown)) },
            confirmButton = { TextButton(onClick = { pending = null; confirmWrite(write, "$kind ${write.address} = $shown") }) { Text(Res.string.modbus_write.str()) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(Res.string.cancel.str()) } },
        )
    }
}
