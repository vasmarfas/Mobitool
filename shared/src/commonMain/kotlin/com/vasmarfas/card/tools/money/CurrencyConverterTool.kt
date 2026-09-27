package com.vasmarfas.card.tools.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.converters.fmtSig
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString

val currencyConverterTool = Tool(
    id = "currency-converter",
    category = ToolCategory.MONEY,
    title = Res.string.currency_converter,
    description = Res.string.currency_converter_description,
    icon = Icons.Filled.CurrencyExchange,
    keywords = listOf("currency", "exchange", "rate", "usd", "eur", "rub", "валюта", "курс", "доллар", "евро", "рубль", "обмен"),
) { CurrencyConverterScreen() }

@Composable
private fun CurrencyConverterScreen() {
    var amountText by rememberSaveable { mutableStateOf("100") }
    var from by rememberSaveable { mutableStateOf("USD") }
    var to by rememberSaveable { mutableStateOf("RUB") }
    var rates by remember { mutableStateOf(Currency.cached()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        val current = rates
        if (refresh == 0 && current != null && currentEpochMillis() - current.fetchedAt < Currency.MAX_AGE_MS) return@LaunchedEffect
        loading = true
        error = null
        try {
            rates = Currency.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = getString(Res.string.failed_to_load_rates) + ": " + (e.message ?: getString(Res.string.network_error))
        } finally {
            loading = false
        }
    }
    val amount = amountText.toDoubleLenient()
    val codes = rates?.rates?.keys?.sorted() ?: listOf("EUR", "RUB", "USD")
    NumberField(
        value = amountText,
        onValueChange = { amountText = it },
        label = Res.string.money_amount.str(),
        suffix = from,
        isError = amountText.isNotBlank() && amount == null,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DropdownChoice(
            options = codes,
            selected = from,
            onSelect = { from = it },
            label = Res.string.from_.str(),
            text = { it },
            modifier = Modifier.weight(1f),
            menuText = { currencyLabel(it) },
            searchable = true,
        )
        IconButton(
            onClick = {
                val previous = from
                from = to
                to = previous
            },
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = Res.string.swap.str())
        }
        DropdownChoice(
            options = codes,
            selected = to,
            onSelect = { to = it },
            label = Res.string.to.str(),
            text = { it },
            modifier = Modifier.weight(1f),
            menuText = { currencyLabel(it) },
            searchable = true,
        )
    }
    if (loading) LoadingRow(Res.string.loading_rates.str())
    error?.let { ErrorText(it) }
    val current = rates
    if (current != null && amount != null) {
        val converted = Currency.convert(amount, from, to, current.rates)
        val rate = Currency.convert(1.0, from, to, current.rates)
        val reverse = Currency.convert(1.0, to, from, current.rates)
        if (converted != null) {
            AnswerCard(
                "${money(converted)} $to",
                "${money(amount)} $from" + (rate?.let { " · 1 $from = ${money(it)} $to" } ?: ""),
                copyValue = money(converted, grouping = false),
            )
        }
        ResultCard {
            if (reverse != null) KeyValueRow("1 $to", "${money(reverse)} $from")
            KeyValueRow(Res.string.rates_updated.str(), formatTime(current.fetchedAt), copyable = false)
        }
        ResultCard(Res.string.popular_currencies.str()) {
            Currency.popular.filter { it != from }.forEach { code ->
                Currency.convert(amount, from, code, current.rates)?.let {
                    KeyValueRow(currencyLabel(code), "${money(it)} $code")
                }
            }
        }
    }
    ActionButton(
        text = Res.string.refresh_rates.str(),
        onClick = { refresh++ },
        enabled = !loading,
        icon = Icons.Filled.Refresh,
    )
}

@Composable
internal fun currencyLabel(code: String): String = Currency.names[code]?.let { "$code — ${it.str()}" } ?: code

internal fun money(value: Double, grouping: Boolean = true): String = if (abs(value) >= 1) value.fmt(2, grouping = grouping) else value.fmtSig(4)

internal fun formatTime(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.date} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
