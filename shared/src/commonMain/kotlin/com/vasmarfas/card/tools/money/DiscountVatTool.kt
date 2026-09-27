package com.vasmarfas.card.tools.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import org.jetbrains.compose.resources.stringResource

private enum class PriceMode { DISCOUNT, VAT }

private enum class VatMode { ADD, REMOVE }

private val vatPresets = listOf(0, 5, 7, 10, 13, 19, 20, 21, 22)

val discountVatTool = Tool(
    id = "discount-vat",
    category = ToolCategory.MONEY,
    title = Res.string.discount_and_vat,
    description = Res.string.discount_vat_description,
    icon = Icons.Filled.LocalOffer,
    keywords = listOf("discount", "sale", "vat", "tax", "net", "gross", "скидка", "ндс", "налог", "распродажа", "цена"),
) { DiscountVatScreen() }

@Composable
private fun DiscountVatScreen() {
    var mode by rememberSaveable { mutableStateOf(PriceMode.DISCOUNT) }
    SegmentedChoice(
        options = PriceMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = { if (it == PriceMode.DISCOUNT) Res.string.discount.str() else Res.string.vat.str() },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(mode) {
        when (mode) {
            PriceMode.DISCOUNT -> DiscountSection()
            PriceMode.VAT -> VatSection()
        }
    }
}

@Composable
private fun DiscountSection() {
    var priceText by rememberSaveable { mutableStateOf("1000") }
    var discountText by rememberSaveable { mutableStateOf("20") }
    var extraText by rememberSaveable { mutableStateOf("") }
    val price = priceText.toDoubleLenient()
    val discount = discountText.toDoubleLenient()
    val extra = if (extraText.isBlank()) 0.0 else extraText.toDoubleLenient()
    NumberField(
        value = priceText,
        onValueChange = { priceText = it },
        label = Res.string.price.str(),
        isError = priceText.isNotBlank() && price == null,
    )
    NumberField(
        value = discountText,
        onValueChange = { discountText = it },
        label = Res.string.discount.str(),
        suffix = "%",
        isError = discountText.isNotBlank() && discount == null,
    )
    NumberField(
        value = extraText,
        onValueChange = { extraText = it },
        label = Res.string.extra_discount_optional.str(),
        suffix = "%",
        isError = extraText.isNotBlank() && extra == null,
    )
    if (price != null && discount != null && extra != null) {
        val afterFirst = DiscountVat.discounted(price, discount)
        val final = DiscountVat.discounted(afterFirst, extra)
        AnswerCard(final.fmt(2, grouping = true), Res.string.final_price.str(), copyValue = final.fmt(2))
        ResultCard {
            KeyValueRow(Res.string.you_save.str(), (price - final).fmt(2, grouping = true))
            if (extra != 0.0) {
                KeyValueRow(Res.string.after_first_discount.str(), afterFirst.fmt(2, grouping = true))
                KeyValueRow(
                    Res.string.effective_discount.str(),
                    if (price == 0.0) "—" else stringResource(Res.string.percent_value, ((price - final) / price * 100).fmt(2)),
                    copyable = false,
                )
            }
        }
    }
}

@Composable
private fun VatSection() {
    var amountText by rememberSaveable { mutableStateOf("1000") }
    var rateText by rememberSaveable { mutableStateOf("20") }
    var vatMode by rememberSaveable { mutableStateOf(VatMode.ADD) }
    val amount = amountText.toDoubleLenient()
    val rate = rateText.toDoubleLenient()
    SegmentedChoice(
        options = VatMode.entries,
        selected = vatMode,
        onSelect = { vatMode = it },
        label = { if (it == VatMode.ADD) Res.string.add_vat_to_net.str() else Res.string.extract_from_gross.str() },
    )
    NumberField(
        value = amountText,
        onValueChange = { amountText = it },
        label = if (vatMode == VatMode.ADD) Res.string.net_price.str() else Res.string.gross_price.str(),
        isError = amountText.isNotBlank() && amount == null,
    )
    NumberField(
        value = rateText,
        onValueChange = { rateText = it },
        label = Res.string.vat_rate.str(),
        suffix = "%",
        isError = rateText.isNotBlank() && rate == null,
    )
    ChoiceChips(
        options = vatPresets,
        selected = rate?.toInt()?.takeIf { it.toDouble() == rate },
        onSelect = { rateText = it.toString() },
        label = { stringResource(Res.string.percent_value, it) },
    )
    if (amount != null && rate != null) {
        val result = if (vatMode == VatMode.ADD) DiscountVat.addVat(amount, rate) else DiscountVat.removeVat(amount, rate)
        ResultCard {
            KeyValueRow(Res.string.net.str(), result.net.fmt(2, grouping = true))
            KeyValueRow(stringResource(Res.string.vat_with_rate, rate.fmt(2)), result.vat.fmt(2, grouping = true))
            KeyValueRow(Res.string.gross.str(), result.gross.fmt(2, grouping = true))
        }
    }
}
