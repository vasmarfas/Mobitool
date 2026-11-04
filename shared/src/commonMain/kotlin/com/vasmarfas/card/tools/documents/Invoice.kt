package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.tools.converters.Amount
import com.vasmarfas.card.tools.converters.NumberWords
import com.vasmarfas.card.tools.converters.WordsCurrency
import com.vasmarfas.card.tools.converters.WordsLang
import com.vasmarfas.card.tools.everyday.AccountCheck
import com.vasmarfas.card.tools.everyday.IdCheck
import com.vasmarfas.card.tools.everyday.Identifiers
import com.vasmarfas.card.tools.everyday.InnCheck
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.math.roundToLong

enum class InvoiceKind { INVOICE, ACT, BOTH }

class Payee(val name: String, val inn: String, val account: String, val bank: String, val bik: String, val correspondent: String)

class InvoiceLine(val title: String, val quantity: Double, val unit: String, val priceKopecks: Long) {
    val sumKopecks: Long get() = (priceKopecks * quantity).roundToLong()
}

class Invoice(
    val number: String,
    val date: LocalDate,
    val seller: Payee,
    val selfEmployed: Boolean,
    val buyer: String,
    val buyerInn: String,
    val lines: List<InvoiceLine>,
) {
    val totalKopecks: Long get() = lines.sumOf { it.sumKopecks }
}

// the document follows Russian rules whatever the interface language, so its text is not in strings.xml
object InvoiceDoc {
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    private inline fun <reified T : IdCheck> passes(number: String, bik: String = ""): Boolean =
        Identifiers.check(number, bik, 0).filterIsInstance<T>().firstOrNull()?.valid == true

    fun innValid(inn: String): Boolean = passes<InnCheck>(inn)

    // a BIK has no check digit of its own, it takes part in the account's key
    fun bikValid(bik: String): Boolean = bik.length == 9 && bik.all { it in '0'..'9' }

    fun accountValid(account: String, bik: String): Boolean = passes<AccountCheck>(account, bik)

    fun correspondentValid(account: String, bik: String): Boolean =
        account.startsWith("30101") && account.takeLast(3) == bik.takeLast(3) && accountValid(account, bik)

    fun kopecks(text: String): Long? = text.toDoubleLenient()?.takeIf { it >= 0 }?.let { (it * 100).roundToLong() }

    fun rubles(kopecks: Long): String = "${(kopecks / 100).fmtGrouped()},${(kopecks % 100).toString().padStart(2, '0')}"

    fun inWords(kopecks: Long): String =
        NumberWords.spellAmount(Amount(false, kopecks / 100, (kopecks % 100).toString().padStart(2, '0')), WordsLang.RU, WordsCurrency.RUB)

    fun longDate(date: LocalDate): String = "${date.day} ${months[date.month.number - 1]} ${date.year} г."

    private fun shortDate(date: LocalDate): String =
        "${date.day.toString().padStart(2, '0')}.${date.month.number.toString().padStart(2, '0')}.${date.year}"

    fun purpose(invoice: Invoice): String = "Оплата по счёту № ${invoice.number} от ${shortDate(invoice.date)}. Без НДС"

    // ГОСТ Р 56042-2014: ST0001 is the format, 2 the charset (UTF-8), the sum is in kopecks. A value must not hold the separator
    fun qrPayload(invoice: Invoice): String {
        val seller = invoice.seller
        val fields = listOf(
            "Name" to seller.name.take(160),
            "PersonalAcc" to seller.account,
            "BankName" to seller.bank.take(45),
            "BIC" to seller.bik,
            "CorrespAcc" to seller.correspondent,
            "PayeeINN" to seller.inn,
            "Sum" to invoice.totalKopecks.toString(),
            "Purpose" to purpose(invoice).take(210),
        )
        return fields.joinToString("|", prefix = "ST00012|") { (key, value) -> "$key=${value.replace('|', ' ').trim()}" }
    }

    private fun text(value: String, bold: Boolean = false, align: Align = Align.START) = Block.Paragraph(listOf(Inline.Text(value, bold = bold)), align)

    private fun cell(value: String, align: Align = Align.START, bold: Boolean = false, colSpan: Int = 1) = Cell(listOf(text(value, bold, align)), colSpan)

    private fun party(name: String, inn: String) = if (inn.isBlank()) name else "$name, ИНН $inn"

    private fun table(invoice: Invoice): List<Block> {
        val header = listOf("№", "Наименование", "Кол-во", "Ед.", "Цена, руб.", "Сумма, руб.").map { cell(it) }
        val rows = invoice.lines.mapIndexed { i, line ->
            listOf(
                cell("${i + 1}"),
                cell(line.title),
                cell(line.quantity.fmt(3).replace('.', ','), Align.END),
                cell(line.unit),
                cell(rubles(line.priceKopecks), Align.END),
                cell(rubles(line.sumKopecks), Align.END),
            )
        }
        val total = listOf(cell("Итого, без НДС", Align.END, bold = true, colSpan = 5), cell(rubles(invoice.totalKopecks), Align.END, bold = true))
        return listOf(
            Block.Table(listOf(header) + rows + listOf(total), headerRows = 1),
            text("Всего наименований ${invoice.lines.size} на сумму ${rubles(invoice.totalKopecks)} руб."),
            text(inWords(invoice.totalKopecks), bold = true),
        )
    }

    private fun bill(invoice: Invoice, qr: Block.Picture?): List<Block> = buildList {
        val seller = invoice.seller
        add(Block.Heading(1, listOf(Inline.Text("Счёт на оплату № ${invoice.number} от ${longDate(invoice.date)}"))))
        add(
            Block.Table(
                listOf(
                    "Получатель" to seller.name,
                    "ИНН" to seller.inn,
                    "Счёт" to seller.account,
                    "Банк" to seller.bank,
                    "БИК" to seller.bik,
                    "Корр. счёт" to seller.correspondent,
                ).map { (label, value) -> listOf(cell(label), cell(value)) },
            ),
        )
        add(text("Покупатель: ${party(invoice.buyer, invoice.buyerInn)}"))
        addAll(table(invoice))
        if (invoice.selfEmployed) add(text("Получатель применяет налог на профессиональный доход."))
        if (qr != null) {
            add(text("QR-код для оплаты в приложении банка", align = Align.CENTER))
            add(qr)
        }
        add(text("Исполнитель ____________________ ${seller.name}"))
    }

    private fun act(invoice: Invoice): List<Block> = buildList {
        val seller = invoice.seller
        add(Block.Heading(1, listOf(Inline.Text("Акт оказанных услуг № ${invoice.number} от ${longDate(invoice.date)}"))))
        add(text("Исполнитель: ${party(seller.name, seller.inn)}"))
        add(text("Заказчик: ${party(invoice.buyer, invoice.buyerInn)}"))
        addAll(table(invoice))
        if (invoice.selfEmployed) add(text("Исполнитель применяет налог на профессиональный доход."))
        add(text("Услуги оказаны полностью и в срок. Заказчик претензий по объёму, качеству и срокам не имеет."))
        add(
            Block.Table(
                listOf(
                    listOf(cell("Исполнитель"), cell("Заказчик")),
                    listOf(cell(seller.name), cell(invoice.buyer)),
                    listOf(cell("Подпись ________________"), cell("Подпись ________________")),
                ),
                headerRows = 1,
            ),
        )
    }

    fun build(invoice: Invoice, kind: InvoiceKind, qr: Block.Picture?): Doc {
        val blocks = when (kind) {
            InvoiceKind.INVOICE -> bill(invoice, qr)
            InvoiceKind.ACT -> act(invoice)
            InvoiceKind.BOTH -> bill(invoice, qr) + Block.PageBreak + act(invoice)
        }
        return Doc(blocks, title = "Счёт № ${invoice.number}", language = "ru")
    }
}
