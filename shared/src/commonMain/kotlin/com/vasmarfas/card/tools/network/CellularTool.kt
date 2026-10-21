package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.CellProblem
import com.vasmarfas.card.core.CellReading
import com.vasmarfas.card.core.CellScan
import com.vasmarfas.card.core.CellTech
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.Waveform
import com.vasmarfas.card.core.cellReadings
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.startTone
import com.vasmarfas.card.core.stopTone
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val cellularTool = Tool(
    id = "cellular",
    category = ToolCategory.NETWORK,
    title = Res.string.cellular,
    description = Res.string.cellular_description,
    icon = Icons.Filled.CellTower,
    keywords = listOf(
        "lte", "4g", "5g", "nr", "3g", "rsrp", "rsrq", "sinr", "earfcn", "arfcn", "band", "cell id", "enb", "pci", "antenna", "modem",
        "hilink", "field test", "сотовая связь", "мобильный интернет", "базовая станция", "вышка", "антенна", "модем", "частоты",
    ),
) { CellularScreen() }

private enum class CellTab(val title: StringResource) {
    CELLS(Res.string.cell_tab_cells),
    MANUAL(Res.string.cell_tab_manual),
    MODEM(Res.string.cell_tab_modem),
    CALCULATOR(Res.string.cell_tab_calculator),
}

private const val HISTORY = 300

@Composable
private fun CellularScreen() {
    val tabs = buildList {
        add(if (currentPlatform == PlatformKind.ANDROID) CellTab.CELLS else CellTab.MANUAL)
        if (NetCapabilities.tcp) add(CellTab.MODEM)
        add(CellTab.CALCULATOR)
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    SegmentedChoice(tabs, tab, { tab = it }, { it.title.str() })
    when (tab) {
        CellTab.CELLS -> PhoneCells()
        CellTab.MANUAL -> ManualValues()
        CellTab.MODEM -> ModemCells()
        CellTab.CALCULATOR -> ChannelCalculator()
    }
}

private fun generation(cell: CellReading, all: List<CellReading>): String = when (cell.tech) {
    CellTech.NR -> "5G SA"
    CellTech.LTE -> if (all.any { it.tech == CellTech.NR }) "5G NSA" else "4G LTE"
    CellTech.UMTS -> "3G"
    CellTech.GSM -> "2G"
    else -> cell.tech.name
}

@Composable
private fun PhoneCells() {
    var granted by remember { mutableStateOf(0) }
    val scan by produceState<CellScan?>(null, granted) {
        while (true) {
            value = cellReadings()
            delay(2_000)
        }
    }
    var history by remember { mutableStateOf(emptyList<Pair<Long, CellReading>>()) }
    val current = scan
    when (current?.problem) {
        CellProblem.NO_PERMISSION -> PermissionPrompt(AppPermission.LOCATION, Res.string.cell_location_needed.str()) { granted++ }
        CellProblem.NO_SIM -> ErrorText(Res.string.cell_no_sim.str())
        CellProblem.UNSUPPORTED -> ErrorText(Res.string.cell_old_android.str())
        null -> if (current == null) LoadingRow()
    }
    if (current == null || current.problem != null) return
    val cells = current.cells
    val serving = cells.firstOrNull { it.registered && (it.tech == CellTech.LTE || it.tech == CellTech.NR) } ?: cells.firstOrNull { it.registered }
    LaunchedEffect(current) {
        serving?.let { history = (history + (currentEpochMillis() to it)).takeLast(HISTORY) }
    }
    if (serving == null) {
        Hint(Res.string.cell_no_service.str())
        return
    }
    ServingCell(serving, generation(serving, cells), history)
    val neighbours = cells.filter { it !== serving }.sortedByDescending { CellGrades.main(it)?.first ?: -200 }
    if (neighbours.isNotEmpty()) {
        ResultCard(Res.string.cell_neighbours.str()) {
            SimpleTable(
                header = listOf(Res.string.cell_network.str(), Res.string.cell_channel.str(), "PCI", Res.string.cell_signal.str()),
                rows = neighbours.map { cell ->
                    listOf(
                        cell.tech.name,
                        listOfNotNull(cell.arfcn?.toString(), cell.arfcn?.let { CellBands.channel(cell.tech, it)?.bands?.firstOrNull() }).joinToString(" · "),
                        cell.pci?.toString() ?: "—",
                        CellGrades.main(cell)?.first?.let { "$it ${Res.string.unit_dbm.str()}" } ?: "—",
                    )
                },
                weights = listOf(0.8f, 1.6f, 0.7f, 1.1f),
                mono = false,
            )
            if (serving.tech == CellTech.LTE && cells.any { it.tech == CellTech.NR }) Hint(Res.string.cell_nsa_hint.str())
        }
    }
}

@Composable
fun SignalBeep(on: Boolean, strength: Float) {
    val latest by rememberUpdatedState(strength.coerceIn(0f, 1f))
    DisposableEffect(on) { onDispose { stopTone() } }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        while (true) {
            val pitch = 400.0 + 900 * latest
            startTone(pitch, Waveform.SINE, 0.5f)
            delay(70.milliseconds)
            startTone(pitch, Waveform.SINE, 0f)
            delay((900 - 780 * latest).toLong().milliseconds)
        }
    }
}

@Composable
private fun gradeText(grade: SignalGrade): String = grade.title.str()

@Composable
private fun ServingCell(cell: CellReading, mode: String, history: List<Pair<Long, CellReading>>) {
    val main = CellGrades.main(cell)
    val dbm = Res.string.unit_dbm.str()
    val colors = LocalStatusColors.current
    ResultCard(listOfNotNull(cell.operator, mode).joinToString(" · ")) {
        if (main != null) {
            val label = if (cell.rsrp != null) "RSRP" else if (cell.tech == CellTech.UMTS) "RSCP" else "RSSI"
            AnswerCard("${main.first} $dbm", "$label · ${gradeText(main.second)}")
        }
        val db = Res.string.unit_db.str()
        cell.rsrq?.let { KeyValueRow(if (cell.tech == CellTech.UMTS) "Ec/No" else "RSRQ", "$it $db · ${gradeText(CellGrades.rsrq(it))}", mono = false, copyable = false) }
        cell.sinr?.let { KeyValueRow("SINR", "${it.fmt(1)} $db · ${gradeText(CellGrades.sinr(it))}", mono = false, copyable = false) }
        cell.rssi?.takeIf { cell.rsrp != null }?.let { KeyValueRow("RSSI", "$it $dbm", mono = false, copyable = false) }
        cell.arfcn?.let { arfcn ->
            val channel = CellBands.channel(cell.tech, arfcn)
            val bands = cell.bands.map { if (cell.tech == CellTech.NR) "n$it" else "B$it" }.ifEmpty { channel?.bands.orEmpty() }
            KeyValueRow(Res.string.cell_channel.str(), listOfNotNull(bands.joinToString(", ").ifEmpty { null }, channel?.let { "${it.downlinkMhz.fmt(1)} ${Res.string.unit_mhz.str()}" }, "#$arfcn").joinToString(" · "), mono = false, copyable = false)
        }
        if (cell.arfcn == null && cell.bands.isNotEmpty()) KeyValueRow(Res.string.cell_channel.str(), cell.bands.joinToString(", ") { "B$it" }, mono = false, copyable = false)
        cell.bandwidthKhz?.let { KeyValueRow(Res.string.cell_bandwidth.str(), "${it / 1000} ${Res.string.unit_mhz.str()}", mono = false, copyable = false) }
        cell.pci?.let { KeyValueRow(if (cell.tech == CellTech.UMTS) "PSC" else if (cell.tech == CellTech.GSM) "BSIC" else "PCI", it.toString(), copyable = false) }
        cell.cellId?.let { id ->
            // an LTE cell id is the eNodeB number shifted left by eight bits plus the sector
            val value = if (cell.tech == CellTech.LTE) "$id · eNB ${id shr 8} · ${stringResource(Res.string.cell_sector, (id and 0xFF).toString())}" else id.toString()
            KeyValueRow(Res.string.cell_id.str(), value)
        }
        cell.area?.let { KeyValueRow(if (cell.tech == CellTech.LTE || cell.tech == CellTech.NR) "TAC" else "LAC", it.toString(), copyable = false) }
        if (cell.mcc != null && cell.mnc != null) KeyValueRow("MCC / MNC", "${cell.mcc} / ${cell.mnc}", copyable = false)
        // one LTE timing advance step is 78 meters of distance to the tower
        cell.timingAdvance?.takeIf { cell.tech == CellTech.LTE && it > 0 }?.let {
            KeyValueRow(Res.string.cell_distance.str(), stringResource(Res.string.cell_distance_value, (it * 78).toString()), mono = false, copyable = false)
        }
        var sound by remember { mutableStateOf(false) }
        SwitchRow(Res.string.wifi_sound.str(), sound, { sound = it }, description = Res.string.cell_sound_hint.str())
        SignalBeep(sound, ((main?.first ?: -130) + 120) / 50f)
    }
    if (history.size > 1) {
        val time: (Int) -> String = { history.getOrNull(it)?.let { (at, _) -> clock(at) }.orEmpty() }
        val label = if (cell.rsrp != null) "RSRP" else "RSSI"
        ResultCard("$label, ${Res.string.unit_dbm.str()}") {
            InteractiveChart(series = listOf(ChartSeries(label, colors.info, history.map { (CellGrades.main(it.second)?.first ?: -140).toFloat() })), xLabel = time)
        }
        if (history.any { it.second.sinr != null }) {
            ResultCard("SINR, ${Res.string.unit_db.str()}") {
                InteractiveChart(series = listOf(ChartSeries("SINR", colors.good, history.map { (it.second.sinr ?: 0.0).toFloat() })), xLabel = time)
            }
        }
    }
}

private suspend fun readModem(kind: ModemKind, address: String): CellReading? = when (kind) {
    ModemKind.HUAWEI -> {
        val session = Modems.huaweiSession(ConnectionProbe.body(plainGet("http://$address/api/webserver/SesTokInfo", 3_000)))
        val headers = session?.let { listOf("Cookie: ${it.first}", "__RequestVerificationToken: ${it.second}") }.orEmpty()
        Modems.huaweiSignal(ConnectionProbe.body(plainGet("http://$address/api/device/signal", 3_000, headers)))
    }
    ModemKind.ZTE -> {
        val query = "isTest=false&multi_data=1&cmd=" + Modems.ZTE_FIELDS.replace(",", "%2C")
        val body = ConnectionProbe.body(plainGet("http://$address/goform/goform_get_cmd_process?$query", 3_000, listOf("Referer: http://$address/index.html")))
        Modems.zteSignal(Net.json.parseToJsonElement(body).jsonObject)
    }
}

@Composable
private fun ModemCells() {
    var kind by rememberSaveable { mutableStateOf(ModemKind.HUAWEI) }
    var address by rememberSaveable(kind) { mutableStateOf(kind.address) }
    var active by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<CellReading?>(null) }
    var failed by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Pair<Long, CellReading>>()) }
    LaunchedEffect(active, kind, address) {
        reading = null
        history = emptyList()
        failed = false
        if (!active) return@LaunchedEffect
        while (true) {
            val next = runCatching { readModem(kind, address.trim()) }.getOrNull()
            failed = next == null
            if (next != null) {
                reading = next
                history = (history + (currentEpochMillis() to next)).takeLast(HISTORY)
            }
            delay(2_000.milliseconds)
        }
    }
    Hint(Res.string.cell_modem_intro.str())
    SegmentedChoice(ModemKind.entries, kind, { kind = it }, { it.title.str() })
    ToolInputField(address, { address = it }, Res.string.cell_modem_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    ActionButton((if (active) Res.string.cell_modem_stop else Res.string.cell_modem_start).str(), onClick = { active = !active })
    val current = reading
    when {
        !active -> Unit
        current != null -> ServingCell(current, generation(current, listOf(current)), history)
        failed -> ErrorText(Res.string.cell_modem_failed.str())
        else -> LoadingRow()
    }
}

@Composable
private fun ManualValues() {
    val technologies by produceState(emptyList<String>()) { value = cellReadings().technologies }
    if (technologies.isNotEmpty()) Text(stringResource(Res.string.cell_ios_technology, technologies.joinToString(", ")), style = MaterialTheme.typography.bodyLarge)
    Hint((if (currentPlatform == PlatformKind.IOS) Res.string.cell_field_test else Res.string.cell_manual_intro).str())
    var rsrp by rememberSaveable { mutableStateOf("") }
    var rsrq by rememberSaveable { mutableStateOf("") }
    var sinr by rememberSaveable { mutableStateOf("") }
    var earfcn by rememberSaveable { mutableStateOf("") }
    NumberField(rsrp, { rsrp = it }, "RSRP", suffix = Res.string.unit_dbm.str())
    NumberField(rsrq, { rsrq = it }, "RSRQ", suffix = "dB")
    NumberField(sinr, { sinr = it }, "SINR", suffix = "dB")
    NumberField(earfcn, { earfcn = it }, "EARFCN")
    val cell = CellReading(
        tech = CellTech.LTE,
        registered = true,
        rsrp = rsrp.toDoubleLenient()?.toInt()?.let { if (it > 0) -it else it },
        rsrq = rsrq.toDoubleLenient()?.toInt()?.let { if (it > 0) -it else it },
        sinr = sinr.toDoubleLenient(),
        arfcn = earfcn.trim().toIntOrNull(),
    )
    if (cell.rsrp != null || cell.rsrq != null || cell.sinr != null || cell.arfcn != null) ServingCell(cell, "LTE", emptyList())
}

@Composable
private fun ChannelCalculator() {
    val techs = listOf(CellTech.LTE, CellTech.NR, CellTech.UMTS, CellTech.GSM)
    var tech by rememberSaveable { mutableStateOf(CellTech.LTE) }
    var number by rememberSaveable { mutableStateOf("") }
    SegmentedChoice(techs, tech, { tech = it }, { it.name })
    val label = when (tech) {
        CellTech.LTE -> "EARFCN"
        CellTech.NR -> "NR-ARFCN"
        CellTech.UMTS -> "UARFCN"
        else -> "ARFCN"
    }
    NumberField(number, { number = it }, label)
    val channel = number.trim().toIntOrNull()?.let { CellBands.channel(tech, it) }
    when {
        number.isBlank() -> Hint(Res.string.cell_calculator_hint.str())
        channel == null -> ErrorText(Res.string.cell_calculator_unknown.str())
        else -> ResultCard {
            AnswerCard("${channel.downlinkMhz.fmt(3)} ${Res.string.unit_mhz.str()}", Res.string.cell_downlink.str())
            if (channel.bands.isNotEmpty()) KeyValueRow(Res.string.cell_band.str(), channel.bands.joinToString(", "), mono = false)
        }
    }
}
