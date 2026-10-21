package com.vasmarfas.card.tools.network.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.CameraScanner
import com.vasmarfas.card.core.Hotspot
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.cameraScanning
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.ensurePermission
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.incomingFolder
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.openReceived
import com.vasmarfas.card.core.shareFiles
import com.vasmarfas.card.core.shareOverBluetooth
import com.vasmarfas.card.core.shareOverNfc
import com.vasmarfas.card.core.startHotspot
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.design.QrPayload
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.network.Ipv4
import com.vasmarfas.card.tools.network.Ipv4Subnet
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.navigation.UrlRoutes
import com.vasmarfas.card.ui.theme.LocalStatusColors
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val fileTransferTool = Tool(
    id = "file-transfer",
    category = ToolCategory.NETWORK,
    title = Res.string.file_transfer,
    description = Res.string.file_transfer_description,
    icon = Icons.Filled.ImportExport,
    keywords = listOf(
        "file transfer", "send files", "share files", "localsend", "airdrop", "quick share", "nearby share", "shareit", "xender",
        "send anywhere", "wifi transfer", "hotspot", "bluetooth", "nfc", "qr",
        "передача файлов", "отправить файл", "передать файлы", "скинуть файл", "с телефона на компьютер", "с компьютера на телефон",
        "по wi-fi", "по блютуз", "без проводов", "без интернета",
    ),
    platforms = PlatformKind.all,
) { FileTransferScreen() }

private enum class Side(val title: StringResource) {
    SEND(Res.string.transfer_send),
    RECEIVE(Res.string.transfer_receive),
}

private enum class Way(val title: StringResource, val icon: ImageVector) {
    INTERNET(Res.string.transfer_way_internet, Icons.Filled.Public),
    BROWSER(Res.string.transfer_way_browser, Icons.Filled.QrCode2),
    HOTSPOT(Res.string.transfer_way_hotspot, Icons.Filled.WifiTethering),
    BLUETOOTH(Res.string.transfer_way_bluetooth, Icons.Filled.Bluetooth),
    APPS(Res.string.transfer_way_apps, Icons.Filled.Share),
}

private val ways: List<Way>
    get() = when (currentPlatform) {
        PlatformKind.ANDROID -> Way.entries
        PlatformKind.IOS -> listOf(Way.INTERNET, Way.BROWSER, Way.APPS)
        PlatformKind.DESKTOP -> listOf(Way.INTERNET, Way.BROWSER)
        PlatformKind.WEB -> listOf(Way.INTERNET)
    }

private val local get() = currentPlatform != PlatformKind.WEB

private suspend fun privateAddresses(): List<String> = networkInterfaces().filter { it.isUp && !it.isLoopback }
    .flatMap { it.addresses }.map { it.substringBefore('/') }
    .filter { a -> Ipv4.parse(a)?.let { Ipv4Subnet(it, 24).isPrivate } == true }
    .distinct()

@Composable
private fun FileTransferScreen() {
    val scope = rememberCoroutineScope()
    val page = PageText(
        lang = LocalLang.current.code,
        title = Res.string.file_transfer.str(),
        forYou = Res.string.transfer_page_for_you.str(),
        download = Res.string.transfer_page_download.str(),
        sendTo = Res.string.transfer_page_send_to.str(),
        choose = Res.string.transfer_pick_files.str(),
        drop = Res.string.transfer_page_drop.str(),
        done = Res.string.transfer_done.str(),
        failed = Res.string.transfer_failed.str(),
    )
    val hub = remember { TransferHub(scope, page) }
    LaunchedEffect(hub) { if (local) hub.start() }
    val linkCode = remember { Relay.parse(UrlRoutes.toolQuery).also { UrlRoutes.toolQuery = "" } }
    var side by rememberSaveable { mutableStateOf(if (linkCode != null) Side.RECEIVE else Side.SEND) }
    var files by remember { mutableStateOf(emptyList<PlatformFile>()) }

    SegmentedChoice(Side.entries, side, { side = it }, { it.title.str() })
    when (side) {
        Side.SEND -> SendSide(hub, files) { files = it }
        Side.RECEIVE -> ReceiveSide(hub, linkCode)
    }
    hub.jobs.forEach { job -> key(job) { JobCard(hub, job) } }
    hub.offer?.let { OfferDialog(it) }
    TransferSettings(hub)
}

@Composable
private fun SendSide(hub: TransferHub, files: List<PlatformFile>, onFiles: (List<PlatformFile>) -> Unit) {
    var writing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val message = text.trim().takeIf { writing && it.isNotEmpty() }

    ResultCard(Res.string.transfer_what.str()) {
        val empty = files.isEmpty() && !writing
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickButton(Res.string.transfer_pick_files.str(), emptySet(), multiple = true, icon = Icons.Filled.UploadFile, empty = empty) { picked ->
                writing = false
                onFiles((files + picked).distinctBy { it.toString() })
            }
            OutlinedButton(onClick = { writing = !writing }) {
                Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(Res.string.transfer_text.str(), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (writing) {
            ToolInputField(text, { text = it }, Res.string.transfer_text_label.str(), singleLine = false, minLines = 3)
        } else if (files.isNotEmpty()) {
            files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint(formatBytes(file.size()))
                    }
                    IconButton(onClick = { onFiles(files - file) }) { Icon(Icons.Filled.Close, contentDescription = Res.string.transfer_remove.str()) }
                }
            }
            Text(
                pluralStringResource(Res.plurals.file_count, files.size, files.size) + " · " + formatBytes(files.sumOf { it.size() }),
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Hint(Res.string.transfer_what_hint.str())
        }
    }
    val ready = if (writing) message != null else files.isNotEmpty()
    if (!ready) return

    if (local) Nearby(hub) { peer -> hub.send(peer, if (writing) emptyList() else files, message) }
    var way by rememberSaveable { mutableStateOf<Way?>(null) }
    ResultCard(Res.string.transfer_other_ways.str()) {
        ways.filter { !writing || it == Way.INTERNET }.forEachIndexed { index, item ->
            if (index > 0) SoftDivider()
            WayRow(item, selected = way == item) { way = if (way == item) null else item }
            AnimatedVisibility(way == item) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    when (item) {
                        Way.INTERNET -> InternetSend(hub, files.takeUnless { writing }.orEmpty(), message)
                        Way.BROWSER -> BrowserSend(hub, files)
                        Way.HOTSPOT -> HotspotPanel(hub, files)
                        Way.BLUETOOTH -> SystemShare(Res.string.transfer_bluetooth_hint, Res.string.transfer_bluetooth_open) { shareOverBluetooth(files) }
                        Way.APPS -> SystemShare(
                            if (currentPlatform == PlatformKind.IOS) Res.string.transfer_apps_hint_ios else Res.string.transfer_apps_hint_android,
                            Res.string.transfer_apps_open,
                        ) { shareFiles(files) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WayRow(way: Way, selected: Boolean, onClick: () -> Unit) {
    val description = when (way) {
        Way.INTERNET -> Res.string.transfer_way_internet_hint
        Way.BROWSER -> Res.string.transfer_way_browser_hint
        Way.HOTSPOT -> Res.string.transfer_way_hotspot_hint
        Way.BLUETOOTH -> Res.string.transfer_way_bluetooth_hint
        Way.APPS -> if (currentPlatform == PlatformKind.IOS) Res.string.transfer_way_apps_hint_ios else Res.string.transfer_way_apps_hint_android
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(way.icon, selected)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(way.title.str(), style = MaterialTheme.typography.titleSmall)
            Hint(description.str())
        }
    }
}

@Composable
private fun Avatar(icon: ImageVector, accent: Boolean = true, size: Int = 40) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (accent) colors.primaryContainer else colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (accent) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size((size * 0.55).dp))
    }
}

private fun Peer.icon(): ImageVector = when (info.deviceType) {
    "mobile" -> Icons.Filled.Smartphone
    "desktop", "server", "headless" -> Icons.Filled.Computer
    "web" -> Icons.Filled.Language
    else -> Icons.Filled.Devices
}

@Composable
private fun Nearby(hub: TransferHub, onPick: (Peer) -> Unit) {
    LaunchedEffect(hub.port) {
        if (hub.port == null) return@LaunchedEffect
        if (!hub.multicast) hub.scan() else {
            delay(4_000.milliseconds)
            if (hub.peers.isEmpty()) hub.scan()
        }
    }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    ResultCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Res.string.transfer_nearby.str(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                hub.peers.clear()
                hub.announce()
                hub.scan()
            }) { Icon(Icons.Filled.Refresh, contentDescription = Res.string.refresh.str()) }
        }
        if (!hub.listening) ErrorText(Res.string.transfer_no_port.str())
        val peers = hub.peers.values.sortedBy { it.info.alias.lowercase() }
        if (peers.isEmpty()) {
            Radar()
            Hint(Res.string.transfer_nearby_hint.str())
        }
        peers.forEach { peer ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(peer) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(peer.icon(), size = 44)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(peer.info.alias, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(listOfNotNull(peer.info.deviceModel, peer.host).joinToString(" · "))
                }
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = Res.string.transfer_send.str(), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (hub.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolInputField(address, { address = it }, Res.string.transfer_address.str(), Modifier.weight(1f), keyboardType = KeyboardType.Uri)
                FilledTonalButton(onClick = { hub.find(address.trim()) }, enabled = Ipv4.parse(address.trim()) != null) { Text(Res.string.transfer_find.str()) }
            }
        } else {
            TextButton(onClick = { manual = true }) { Text(Res.string.transfer_by_address.str()) }
        }
    }
}

@Composable
private fun Radar() {
    val pulse = rememberInfiniteTransition()
    val phase by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart))
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 3) {
                val t = (phase + i / 3f) % 1f
                drawCircle(ring.copy(alpha = (1f - t) * 0.5f), radius = size.minDimension / 2 * (0.25f + 0.75f * t), center = center, style = Stroke(3.dp.toPx()))
            }
        }
        Avatar(if (currentPlatform == PlatformKind.DESKTOP) Icons.Filled.Computer else Icons.Filled.Smartphone, size = 52)
    }
}

@Composable
private fun QrCard(data: String, caption: String? = null) {
    val ink = Color(0xFF326773)
    val painter = rememberQrCodePainter(data = data) {
        shapes {
            ball = QrBallShape.roundCorners(.25f)
            frame = QrFrameShape.roundCorners(.25f)
            darkPixel = QrPixelShape.roundCorners()
        }
        colors {
            dark = QrBrush.solid(ink)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
            Image(painter, contentDescription = caption, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        caption?.let { Hint(it) }
    }
}

@Composable
private fun LinkBlock(link: String) {
    val nfc = remember(link) { shareOverNfc(link) }
    DisposableEffect(link) { onDispose { shareOverNfc(null) } }
    QrCard(link)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(link, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        CopyIconButton(link)
    }
    if (nfc) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Hint(Res.string.transfer_nfc_hint.str())
        }
    }
}

@Composable
private fun InternetSend(hub: TransferHub, files: List<PlatformFile>, text: String?) {
    val scope = rememberCoroutineScope()
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var link by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<TransferJob?>(null) }
    val running = job?.state.let { it == JobState.WAITING || it == JobState.RUNNING }
    if (!running) {
        if (text == null) {
            SwitchRow(
                Res.string.transfer_encrypt.str(),
                encrypt,
                { encrypt = it },
                description = (if (encrypt) Res.string.transfer_encrypt_on else Res.string.transfer_encrypt_off).str(),
            )
        }
        ActionButton(Res.string.transfer_make_link.str(), icon = if (encrypt || text != null) Icons.Filled.Lock else Icons.Filled.LockOpen, onClick = {
            val created = TransferJob(outgoing = true, peer = Relay.host, items = files.map { TransferItem(it.name, it.size()) })
            hub.jobs.add(0, created)
            job = created
            val task = scope.launch {
                try {
                    if (encrypt || text != null) {
                        val fresh = Relay.newCode()
                        code = fresh
                        link = Relay.link(fresh)
                        Relay.send(fresh, LocalSend.alias, files, text, created)
                    } else {
                        val path = Relay.plainPath(files)
                        code = null
                        link = "${Relay.base}/$path"
                        Relay.sendPlain(path, files, created)
                    }
                    created.finish(JobState.DONE)
                } catch (e: CancellationException) {
                    created.finish(JobState.CANCELLED)
                    throw e
                } catch (_: Exception) {
                    created.finish(JobState.FAILED)
                }
            }
            created.stop = { task.cancel() }
        })
        if (job == null) Hint(Res.string.transfer_relay_hint.str())
    }
    val shown = link
    if (running && shown != null) {
        code?.let { value ->
            Text(Res.string.transfer_code.str(), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Relay.pretty(value),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                CopyIconButton(Relay.pretty(value))
            }
        }
        LinkBlock(shown)
        Hint((if (code != null) Res.string.transfer_wait_code else Res.string.transfer_wait_plain).str())
    }
}

@Composable
private fun BrowserSend(hub: TransferHub, files: List<PlatformFile>) {
    DisposableEffect(files) {
        hub.shared = files
        onDispose { hub.shared = emptyList() }
    }
    BrowserLink(hub, Res.string.transfer_browser_send_hint)
}

@Composable
private fun BrowserLink(hub: TransferHub, hint: StringResource, prefer: List<String> = emptyList()) {
    val port = hub.port
    if (port == null) {
        if (hub.listening) LoadingRow() else ErrorText(Res.string.transfer_no_port.str())
        return
    }
    val addresses by produceState(emptyList<String>(), prefer) { value = (prefer + privateAddresses()).distinct() }
    var chosen by remember(addresses) { mutableStateOf(addresses.firstOrNull()) }
    val address = chosen
    if (address == null) {
        ErrorText(Res.string.transfer_no_network.str())
        return
    }
    if (addresses.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            addresses.forEach { a -> TextButton(onClick = { chosen = a }) { Text(a, fontWeight = if (a == address) FontWeight.Bold else FontWeight.Normal) } }
        }
    }
    LinkBlock("http://$address:$port/${hub.token}/")
    Hint(hint.str())
}

@Composable
private fun HotspotPanel(hub: TransferHub, files: List<PlatformFile>) {
    val scope = rememberCoroutineScope()
    var hotspot by remember { mutableStateOf<Hotspot?>(null) }
    var starting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(emptyList<String>()) }
    DisposableEffect(Unit) { onDispose { hotspot?.stop?.invoke() } }
    val active = hotspot
    if (active == null) {
        Hint(Res.string.transfer_hotspot_hint.str())
        ActionButton(Res.string.transfer_hotspot_start.str(), icon = Icons.Filled.WifiTethering, enabled = !starting, onClick = {
            scope.launch {
                starting = true
                failed = false
                before = privateAddresses()
                hotspot = startHotspot()
                failed = hotspot == null
                starting = false
            }
        })
        if (starting) LoadingRow()
        if (failed) ErrorText(Res.string.transfer_hotspot_failed.str())
        return
    }
    Text(Res.string.transfer_hotspot_step1.str(), style = MaterialTheme.typography.titleSmall)
    QrCard(QrPayload.wifi(active.ssid, active.password, "WPA", false))
    KeyValueRow(Res.string.transfer_hotspot_name.str(), active.ssid)
    KeyValueRow(Res.string.transfer_hotspot_password.str(), active.password)
    Text(Res.string.transfer_hotspot_step2.str(), style = MaterialTheme.typography.titleSmall)
    val fresh by produceState(emptyList<String>(), active) {
        repeat(10) {
            value = privateAddresses() - before.toSet()
            if (value.isNotEmpty()) return@produceState
            delay(1_000.milliseconds)
        }
    }
    DisposableEffect(files) {
        hub.shared = files
        onDispose { hub.shared = emptyList() }
    }
    BrowserLink(hub, Res.string.transfer_browser_send_hint, fresh)
    TextButton(onClick = {
        active.stop()
        hotspot = null
    }) { Text(Res.string.transfer_hotspot_stop.str()) }
}

@Composable
private fun SystemShare(hint: StringResource, button: StringResource, share: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Hint(hint.str())
    ActionButton(button.str(), icon = Icons.Filled.Share, onClick = { scope.launch { failed = !share() } })
    if (failed) ErrorText(Res.string.transfer_share_failed.str())
}

@Composable
private fun ReceiveSide(hub: TransferHub, linkCode: String?) {
    if (local) {
        ResultCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(if (currentPlatform == PlatformKind.DESKTOP) Icons.Filled.Computer else Icons.Filled.Smartphone, size = 56)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Hint(Res.string.transfer_seen_as.str())
                    Text(LocalSend.alias, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = LocalStatusColors.current
                    val port = hub.port
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (port != null) status.good else status.bad))
                        Hint(
                            when {
                                port != null -> stringResource(Res.string.transfer_ready, port.toString())
                                hub.listening -> Res.string.loading.str()
                                else -> Res.string.transfer_no_port.str()
                            },
                        )
                    }
                }
            }
            Hint(Res.string.transfer_receive_hint.str())
            SwitchRow(Res.string.transfer_auto_accept.str(), hub.autoAccept, { hub.autoAccept = it }, description = Res.string.transfer_auto_accept_hint.str())
            ToolInputField(hub.pin, { hub.pin = it.filter(Char::isDigit).take(8) }, Res.string.transfer_pin.str(), keyboardType = KeyboardType.NumberPassword)
        }
        var browser by rememberSaveable { mutableStateOf(false) }
        ResultCard {
            WayRow(Way.BROWSER, browser) { browser = !browser }
            AnimatedVisibility(browser) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { BrowserLink(hub, Res.string.transfer_browser_receive_hint) }
            }
        }
    }
    InternetReceive(hub, linkCode)
    if (hub.messages.isNotEmpty()) {
        ResultCard(Res.string.transfer_messages.str()) {
            hub.messages.forEachIndexed { index, message ->
                if (index > 0) SoftDivider()
                Hint(message.sender)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.text, modifier = Modifier.weight(1f))
                    CopyIconButton(message.text)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Hint(incomingFolder()?.let { stringResource(Res.string.transfer_folder, it) } ?: Res.string.transfer_folder_browser.str())
    }
}

@Composable
private fun InternetReceive(hub: TransferHub, linkCode: String?) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf(linkCode?.let(Relay::pretty).orEmpty()) }
    var scanning by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Boolean?>(null) }
    var job by remember { mutableStateOf<TransferJob?>(null) }
    var waiting by remember { mutableStateOf(false) }
    val code = Relay.parse(input)

    fun start(value: String) {
        val created = TransferJob(outgoing = false, peer = Relay.host, items = emptyList())
        waiting = true
        val task = scope.launch {
            try {
                Relay.receive(value, LocalSend.alias) { manifest ->
                    waiting = false
                    manifest.text?.let { hub.messages.add(0, TextMessage(manifest.sender, it)) }
                    TransferJob(outgoing = false, peer = manifest.sender, items = manifest.files.map { TransferItem(it.name, it.size) }).also {
                        it.stop = created.stop
                        job = it
                        hub.jobs.add(0, it)
                    }
                }
            } catch (e: CancellationException) {
                job?.finish(JobState.CANCELLED)
                throw e
            } catch (_: Exception) {
                job?.finish(JobState.FAILED) ?: created.finish(JobState.FAILED).also { hub.jobs.add(0, created) }
            } finally {
                waiting = false
            }
        }
        created.stop = { task.cancel() }
        job = created
    }
    LaunchedEffect(linkCode) { if (linkCode != null) start(linkCode) }

    ResultCard(Res.string.transfer_by_code.str()) {
        if (waiting) {
            LoadingRow(Res.string.transfer_waiting_sender.str())
            Hint(Res.string.transfer_waiting_sender_hint.str())
            TextButton(onClick = { job?.cancel() }) { Text(Res.string.cancel.str()) }
            return@ResultCard
        }
        ToolInputField(
            input,
            { input = it.filter { c -> c.isDigit() || c == '-' || c == ' ' }.take(16) },
            Res.string.transfer_code.str(),
            placeholder = "1234-5678-9012",
            keyboardType = KeyboardType.Number,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Res.string.transfer_receive.str(), icon = Icons.Filled.Download, enabled = code != null, onClick = { code?.let(::start) })
            if (cameraScanning) {
                OutlinedButton(onClick = {
                    scope.launch {
                        camera = ensurePermission(AppPermission.CAMERA)
                        scanning = true
                    }
                }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(Res.string.transfer_scan.str(), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (scanning) {
            when (camera) {
                false -> PermissionPrompt(AppPermission.CAMERA, Res.string.scan_camera_denied.str()) { camera = true }
                true -> CameraScanner(
                    onFound = { found ->
                        Relay.parse(found.text)?.let { value ->
                            scanning = false
                            input = Relay.pretty(value)
                            start(value)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                )
                null -> LoadingRow()
            }
        } else {
            Hint(Res.string.transfer_by_code_hint.str())
        }
    }
}

@Composable
private fun JobCard(hub: TransferHub, job: TransferJob) {
    val colors = MaterialTheme.colorScheme
    val running = job.state == JobState.RUNNING || job.state == JobState.WAITING
    val now by produceState(currentEpochMillis(), running) {
        while (running) {
            value = currentEpochMillis()
            delay(500.milliseconds)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(if (job.outgoing) Icons.Filled.Upload else Icons.Filled.Download, accent = job.state != JobState.FAILED, size = 36)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        stringResource(if (job.outgoing) Res.string.transfer_to else Res.string.transfer_from, job.peer),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Hint(job.stateText(now))
                    when {
                        job.direct -> Hint(Res.string.transfer_via_direct.str())
                        job.relay != null -> Hint(stringResource(Res.string.transfer_via_relay, job.relay.orEmpty()))
                    }
                }
                when {
                    running -> IconButton(onClick = job::cancel) { Icon(Icons.Filled.Close, contentDescription = Res.string.cancel.str()) }
                    else -> IconButton(onClick = { hub.jobs.remove(job) }) { Icon(Icons.Filled.Close, contentDescription = Res.string.transfer_remove.str()) }
                }
            }
            if (job.total > 0 && job.state != JobState.DECLINED && job.state != JobState.BUSY && job.state != JobState.PIN) {
                LinearProgressIndicator(progress = { (job.done.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (job.state == JobState.PIN) PinRetry(job)
            job.items.forEach { item ->
                val file = item.file
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (file != null && local) {
                        TextButton(onClick = { openReceived(file) }) { Text(Res.string.open.str()) }
                    } else {
                        Hint(formatBytes(item.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferJob.stateText(now: Long): String = when (state) {
    JobState.WAITING -> Res.string.transfer_state_waiting.str()
    JobState.RUNNING -> {
        val elapsed = (now - startedAt).coerceAtLeast(1)
        val speed = done * 1000 / elapsed
        if (done == 0L || speed == 0L) {
            Res.string.transfer_state_waiting.str()
        } else {
            val left = if (speed > 0) (total - done).coerceAtLeast(0) * 1000 / speed else 0
            stringResource(Res.string.transfer_progress, formatBytes(done), formatBytes(total), stringResource(Res.string.transfer_speed, formatBytes(speed)), formatDurationMs(left))
        }
    }
    JobState.DONE -> if (startedAt == 0L) {
        Res.string.transfer_done.str()
    } else {
        stringResource(Res.string.transfer_state_done, formatBytes(total), formatDurationMs((finishedAt - startedAt).coerceAtLeast(0)))
    }
    JobState.PIN -> Res.string.transfer_state_pin.str()
    JobState.DECLINED -> Res.string.transfer_state_declined.str()
    JobState.BUSY -> Res.string.transfer_state_busy.str()
    JobState.FAILED -> Res.string.transfer_failed.str()
    JobState.CANCELLED -> Res.string.transfer_state_cancelled.str()
}

@Composable
private fun PinRetry(job: TransferJob) {
    var pin by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolInputField(pin, { pin = it.filter(Char::isDigit) }, Res.string.transfer_pin_enter.str(), Modifier.weight(1f), keyboardType = KeyboardType.NumberPassword)
        FilledTonalButton(onClick = { job.retry(pin) }, enabled = pin.isNotEmpty()) { Text(Res.string.transfer_send.str()) }
    }
}

@Composable
private fun OfferDialog(offer: IncomingOffer) {
    val total = offer.files.sumOf { it.size }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
        title = { Text(stringResource(Res.string.transfer_offer_title, offer.sender.alias), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pluralStringResource(Res.plurals.file_count, offer.files.size, offer.files.size) + " · " + formatBytes(total), style = MaterialTheme.typography.titleSmall)
                offer.files.take(6).forEach { Text(it.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (offer.files.size > 6) Hint(pluralStringResource(Res.plurals.transfer_more_files, offer.files.size - 6, offer.files.size - 6))
            }
        },
        confirmButton = { TextButton(onClick = { offer.answer(true) }) { Text(Res.string.transfer_accept.str()) } },
        dismissButton = { TextButton(onClick = { offer.answer(false) }) { Text(Res.string.transfer_decline.str()) } },
    )
}

@Composable
private fun TransferSettings(hub: TransferHub) {
    var open by rememberSaveable { mutableStateOf(false) }
    var alias by remember { mutableStateOf(LocalSend.alias) }
    var relay by remember { mutableStateOf(Relay.base) }
    TextButton(onClick = { open = !open }) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(Res.string.transfer_settings.str(), modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    ResultCard {
        if (local) {
            ToolInputField(alias, {
                alias = it.take(40)
                LocalSend.alias = alias
            }, Res.string.transfer_name.str())
            Hint(Res.string.transfer_name_hint.str())
        }
        ToolInputField(relay, {
            relay = it
            Relay.base = it
        }, Res.string.transfer_relay.str(), keyboardType = KeyboardType.Uri)
        Hint(Res.string.transfer_relay_setting_hint.str())
        if (local) {
            KeyValueRow(Res.string.transfer_compatible.str(), "LocalSend 2", copyable = false)
            Hint(Res.string.transfer_compatible_hint.str())
        }
    }
}
