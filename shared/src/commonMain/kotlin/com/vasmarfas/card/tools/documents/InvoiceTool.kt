package com.vasmarfas.card.tools.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.media.ImageTarget
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.tools.media.encodeImage
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import io.github.alexzhirkevich.qrose.QrCodePainter
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

val invoiceTool = Tool(
    id = "invoice-act",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.invoice_act,
    description = Res.string.invoice_act_description,
    icon = Icons.AutoMirrored.Filled.ReceiptLong,
    keywords = listOf(
        "invoice", "bill", "payment qr", "service act", "self-employed", "sole trader",
        "счёт на оплату", "счет на оплату", "акт", "акт выполненных работ", "акт оказанных услуг", "самозанятый", "ип", "qr оплата",
        "сумма прописью", "реквизиты",
    ),
) { InvoiceScreen() }

private const val PAYEE_KEY = "invoice_payee"
private const val QR_SIDE_PT = 113f

// a module fills whole 8x8 JPEG blocks, so the edges stay clean
private const val QR_MODULE_PX = 8

private data class InvoiceRow(val title: String = "", val quantity: String = "1", val unit: String = "шт.", val price: String = "")

private val rowsSaver = listSaver<SnapshotStateList<InvoiceRow>, String>(
    save = { rows -> rows.flatMap { listOf(it.title, it.quantity, it.unit, it.price) } },
    restore = { saved -> mutableStateListOf<InvoiceRow>().apply { addAll(saved.chunked(4) { InvoiceRow(it[0], it[1], it[2], it[3]) }) } },
)

internal fun invoiceQr(payload: String): PreparedImage {
    val painter = QrCodePainter(payload, QrOptions(errorCorrectionLevel = QrErrorCorrectionLevel.Medium))
    val side = (painter.intrinsicSize.width / 10).toInt() * QR_MODULE_PX
    return PreparedImage(encodeImage(painter.toImageBitmap(side, side), ImageTarget.JPEG, 95), side, side)
}

@Composable
private fun RequisiteField(value: String, onChange: (String) -> Unit, label: String, valid: Boolean, error: String) {
    val wrong = value.isNotBlank() && !valid
    ToolInputField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter { it in '0'..'9' }) },
        label = label,
        keyboardType = KeyboardType.Number,
        isError = wrong,
        supportingText = error.takeIf { wrong },
    )
}

@Composable
private fun InvoiceScreen() {
    val scope = rememberCoroutineScope()
    val saved = remember { Prefs.store.get(PAYEE_KEY)?.split('\n')?.takeIf { it.size == 7 } }
    var keep by rememberSaveable { mutableStateOf(saved != null) }
    var selfEmployed by rememberSaveable { mutableStateOf(saved?.get(0) != "0") }
    var name by rememberSaveable { mutableStateOf(saved?.get(1).orEmpty()) }
    var inn by rememberSaveable { mutableStateOf(saved?.get(2).orEmpty()) }
    var account by rememberSaveable { mutableStateOf(saved?.get(3).orEmpty()) }
    var bank by rememberSaveable { mutableStateOf(saved?.get(4).orEmpty()) }
    var bik by rememberSaveable { mutableStateOf(saved?.get(5).orEmpty()) }
    var correspondent by rememberSaveable { mutableStateOf(saved?.get(6).orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(InvoiceKind.BOTH) }
    var number by rememberSaveable { mutableStateOf("1") }
    var dateText by rememberSaveable { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()) }
    var buyer by rememberSaveable { mutableStateOf("") }
    var buyerInn by rememberSaveable { mutableStateOf("") }
    val rows = rememberSaveable(saver = rowsSaver) { mutableStateListOf(InvoiceRow()) }
    var result by remember { mutableStateOf<PdfResult?>(null) }
    val task = remember { TaskState() }

    LaunchedEffect(keep, selfEmployed, name, inn, account, bank, bik, correspondent) {
        if (keep) {
            Prefs.store.put(PAYEE_KEY, listOf(if (selfEmployed) "1" else "0", name, inn, account, bank, bik, correspondent).joinToString("\n"))
        } else {
            Prefs.store.remove(PAYEE_KEY)
        }
    }

    SegmentedChoice(
        options = InvoiceKind.entries,
        selected = kind,
        onSelect = { kind = it },
        label = {
            when (it) {
                InvoiceKind.INVOICE -> Res.string.invoice_kind_invoice.str()
                InvoiceKind.ACT -> Res.string.invoice_kind_act.str()
                InvoiceKind.BOTH -> Res.string.invoice_kind_both.str()
            }
        },
    )
    val needsBank = kind != InvoiceKind.ACT
    ToolSection(Res.string.invoice_seller.str()) {
        SegmentedChoice(
            options = listOf(true, false),
            selected = selfEmployed,
            onSelect = { selfEmployed = it },
            label = { if (it) Res.string.invoice_self_employed.str() else Res.string.invoice_entrepreneur.str() },
        )
        ToolInputField(name, { name = it }, Res.string.invoice_seller_name.str())
        RequisiteField(inn, { inn = it }, Res.string.invoice_inn.str(), InvoiceDoc.innValid(inn), Res.string.invoice_bad_inn.str())
        if (needsBank) {
            ToolInputField(bank, { bank = it }, Res.string.invoice_bank.str())
            RequisiteField(bik, { bik = it }, Res.string.invoice_bik.str(), InvoiceDoc.bikValid(bik), Res.string.invoice_bad_bik.str())
            RequisiteField(account, { account = it }, Res.string.invoice_account.str(), InvoiceDoc.accountValid(account, bik), Res.string.invoice_bad_account.str())
            RequisiteField(
                correspondent, { correspondent = it }, Res.string.invoice_correspondent.str(),
                InvoiceDoc.correspondentValid(correspondent, bik), Res.string.invoice_bad_correspondent.str(),
            )
        }
        SwitchRow(Res.string.invoice_keep.str(), keep, { keep = it }, description = Res.string.invoice_keep_hint.str())
    }
    ToolSection(Res.string.invoice_buyer.str()) {
        ToolInputField(buyer, { buyer = it }, Res.string.invoice_buyer_name.str())
        RequisiteField(buyerInn, { buyerInn = it }, Res.string.invoice_inn.str(), InvoiceDoc.innValid(buyerInn), Res.string.invoice_bad_inn.str())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolInputField(number, { number = it }, Res.string.invoice_number.str(), modifier = Modifier.weight(1f))
        DateField(dateText, { dateText = it }, Res.string.date.str(), modifier = Modifier.weight(2f))
    }
    ToolSection(Res.string.invoice_items.str()) {
        rows.forEachIndexed { index, row ->
            ToolInputField(row.title, { rows[index] = row.copy(title = it) }, Res.string.invoice_item_title.str())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(
                    row.quantity, { rows[index] = row.copy(quantity = it) }, Res.string.invoice_quantity_short.str(), modifier = Modifier.weight(1f),
                    isError = (row.quantity.toDoubleLenient() ?: 0.0) <= 0,
                )
                ToolInputField(row.unit, { rows[index] = row.copy(unit = it) }, Res.string.invoice_item_unit.str(), modifier = Modifier.weight(1f))
                NumberField(
                    row.price, { rows[index] = row.copy(price = it) }, Res.string.price.str(), modifier = Modifier.weight(1f),
                    isError = row.price.isNotBlank() && InvoiceDoc.kopecks(row.price) == null,
                )
                IconButton(onClick = { rows.removeAt(index) }, enabled = rows.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = Res.string.remove.str())
                }
            }
        }
        ActionButton(text = Res.string.add_row.str(), icon = Icons.Filled.Add, onClick = { rows.add(InvoiceRow()) })
    }

    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val lines = rows.mapNotNull { row ->
        val quantity = row.quantity.toDoubleLenient()?.takeIf { it > 0 }
        val price = InvoiceDoc.kopecks(row.price)
        if (row.title.isBlank() || quantity == null || price == null) null else InvoiceLine(row.title.trim(), quantity, row.unit.trim(), price)
    }
    val payable = !needsBank || (
        bank.isNotBlank() && InvoiceDoc.bikValid(bik) && InvoiceDoc.accountValid(account, bik) && InvoiceDoc.correspondentValid(correspondent, bik)
        )
    val invoice = if (
        date != null && payable && number.isNotBlank() && name.isNotBlank() && InvoiceDoc.innValid(inn) && buyer.isNotBlank() &&
        (buyerInn.isBlank() || InvoiceDoc.innValid(buyerInn)) && lines.size == rows.size
    ) {
        val payee = Payee(name.trim(), inn, account, bank.trim(), bik, correspondent)
        Invoice(number.trim(), date, payee, selfEmployed, buyer.trim(), buyerInn, lines).takeIf { it.totalKopecks > 0 }
    } else {
        null
    }

    invoice?.let {
        ResultCard {
            KeyValueRow(Res.string.total.str(), "${InvoiceDoc.rubles(it.totalKopecks)} ₽", copyable = false)
            Hint(InvoiceDoc.inWords(it.totalKopecks))
        }
    }
    if (invoice == null) Hint(Res.string.invoice_fill_in.str())
    ActionButton(
        text = Res.string.make_pdf.str(),
        icon = Icons.Filled.PictureAsPdf,
        enabled = invoice != null && !task.running,
        onClick = {
            val source = invoice ?: return@ActionButton
            val type = kind
            result = null
            task.launch(scope) { _ ->
                val fonts = documentFonts(serif = false)
                result = withContext(Dispatchers.Default) {
                    val qr = if (type == InvoiceKind.ACT) null else Block.Picture(ByteArray(0), QR_SIDE_PT, QR_SIDE_PT)
                    val images = listOfNotNull(qr).associateWith { invoiceQr(InvoiceDoc.qrPayload(source)) }
                    val doc = InvoiceDoc.build(source, type, qr)
                    val bytes = PdfTypesetter.typeset(doc, fonts, images, TypesetOptions(fontSize = 10f, pageNumbers = false))
                    val prefix = if (type == InvoiceKind.ACT) "act" else "invoice"
                    PdfResult("$prefix-${source.number.filter { it.isLetterOrDigit() }}.pdf", bytes, PdfDocument.parse(bytes).pageCount)
                }
            }
        },
    )
    TaskProgress(task, Res.string.rendering.str())
    result?.let { PdfResultCard(it) }
}
