package com.vasmarfas.card.tools.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Base64Tools
import com.vasmarfas.card.tools.developer.toHex
import com.vasmarfas.card.tools.everyday.Coin
import com.vasmarfas.card.tools.everyday.DiceTray
import com.vasmarfas.card.tools.everyday.RollingText
import com.vasmarfas.card.tools.text.OutputCard
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlin.math.abs
import kotlin.random.Random
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class RandomMode(val title: StringResource) {
    INT(Res.string.number),
    UNIQUE(Res.string.unique_numbers),
    DICE(Res.string.dice),
    COIN(Res.string.coin),
    LIST(Res.string.from_a_list),
    BYTES(Res.string.bytes),
}

private const val MAX_BALLS = 60
private const val BALL_LAG_STEPS = 10
private const val BALL_LAG_MS = 100
private const val MAX_DICE_SHOWN = 12

val randomGeneratorTool = Tool(
    id = "random-generator",
    category = ToolCategory.SECURITY,
    title = Res.string.random_generator,
    description = Res.string.random_generator_description,
    icon = Icons.Filled.Casino,
    keywords = listOf("random", "dice", "coin", "shuffle", "lottery", "pick", "случайное", "кубик", "монетка", "жребий", "перемешать", "розыгрыш"),
) { RandomGeneratorScreen() }

@Composable
private fun RandomGeneratorScreen() {
    var mode by rememberSaveable { mutableStateOf(RandomMode.INT) }
    var fromText by rememberSaveable { mutableStateOf("1") }
    var toText by rememberSaveable { mutableStateOf("100") }
    var countText by rememberSaveable { mutableStateOf("6") }
    var diceText by rememberSaveable { mutableStateOf("3d6+2") }
    var listText by rememberSaveable { mutableStateOf("") }
    var bytesText by rememberSaveable { mutableStateOf("32") }
    var seed by remember { mutableStateOf(0) }
    ChoiceChips(options = RandomMode.entries, selected = mode, onSelect = { mode = it }, label = { it.title.str() })
    when (mode) {
        RandomMode.INT, RandomMode.UNIQUE -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(value = fromText, onValueChange = { fromText = it }, label = Res.string.from__3.str(), modifier = Modifier.weight(1f))
            NumberField(value = toText, onValueChange = { toText = it }, label = Res.string.random_to.str(), modifier = Modifier.weight(1f))
            if (mode == RandomMode.UNIQUE) {
                NumberField(value = countText, onValueChange = { countText = it }, label = Res.string.random_count.str(), modifier = Modifier.weight(1f))
            }
        }

        RandomMode.DICE -> {
            ToolInputField(
                value = diceText,
                onValueChange = { diceText = it },
                label = Res.string.dice_notation.str(),
                placeholder = "2d20 · 3d6+2 · d100",
                monospace = true,
            )
            Hint(Res.string.dice_notation_hint.str())
        }

        RandomMode.LIST -> ToolInputField(
            value = listText,
            onValueChange = { listText = it },
            label = Res.string.options_one_per_line.str(),
            singleLine = false,
            minLines = 4,
        )

        RandomMode.BYTES -> NumberField(value = bytesText, onValueChange = { bytesText = it }, label = Res.string.bytes_1_1024.str())

        RandomMode.COIN -> {}
    }
    ActionButton(Res.string.roll.str(), onClick = { seed++ }, icon = Icons.Filled.Refresh)
    val from = fromText.trim().toIntOrNull()
    val to = toText.trim().toIntOrNull()
    val count = countText.trim().toIntOrNull()
    when (mode) {
        RandomMode.INT -> {
            if (from == null || to == null || from > to) {
                ErrorText(Res.string.random_enter_a_valid_range.str())
                return
            }
            val value = remember(from, to, seed) { Dice.intInRange(from, to) }
            ResultCard {
                Box(Modifier.fillMaxWidth()) {
                    RollingText(value.toString(), seed, MaterialTheme.typography.displayMedium, Modifier.align(Alignment.Center)) {
                        Random.nextLong(from.toLong(), to + 1L).toString()
                    }
                    CopyIconButton(value.toString(), Modifier.align(Alignment.CenterEnd))
                }
                Text("${Res.string.range.str()}: $from … $to", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        RandomMode.UNIQUE -> {
            if (from == null || to == null || from > to || count == null || count < 1) {
                ErrorText(Res.string.enter_a_valid_range_and_count.str())
                return
            }
            if (count > Dice.MAX_UNIQUE) {
                ErrorText(stringResource(Res.string.random_count_limit, Dice.MAX_UNIQUE.fmtGrouped()))
                return
            }
            val values = remember(from, to, count, seed) { Dice.uniqueInts(from, to, count) }
            if (values == null) {
                ErrorText(Res.string.random_range_is_smaller.str())
                return
            }
            ResultCard {
                if (values.size <= MAX_BALLS) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        values.forEachIndexed { i, v ->
                            Box(
                                Modifier.widthIn(min = 48.dp).height(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RollingText(v.toString(), seed, MaterialTheme.typography.titleMedium, lag = minOf(i, BALL_LAG_STEPS) * BALL_LAG_MS) {
                                    Random.nextLong(from.toLong(), to + 1L).toString()
                                }
                            }
                        }
                    }
                }
                KeyValueRow(Res.string.draw_order.str(), values.joinToString(", "))
                KeyValueRow(Res.string.sorted.str(), values.sorted().joinToString(", "))
            }
        }

        RandomMode.DICE -> {
            val roll = remember(diceText, seed) { Dice.roll(diceText) }
            if (roll == null) {
                ErrorText(Res.string.random_notation_like_3d6_2.str())
                return
            }
            ResultCard {
                if (roll.rolls.size <= MAX_DICE_SHOWN) DiceTray(roll.rolls, roll.sides, seed)
                Text(roll.total.toString(), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val modifierText = if (roll.modifier != 0) " ${if (roll.modifier > 0) "+" else "−"} ${abs(roll.modifier)}" else ""
                KeyValueRow(Res.string.rolls.str(), roll.rolls.joinToString(" + ") + modifierText + " = ${roll.total}")
                KeyValueRow(Res.string.random_dice.str(), roll.rolls.size.toString(), copyable = false)
            }
        }

        RandomMode.COIN -> {
            val heads = remember(seed) { Dice.intInRange(0, 1) == 0 }
            ResultCard { Coin(heads, seed, Res.string.heads.str(), Res.string.tails.str()) }
        }

        RandomMode.LIST -> {
            val items = remember(listText) { listText.lines().map { it.trim() }.filter { it.isNotEmpty() } }
            if (items.isEmpty()) {
                ErrorText(Res.string.add_at_least_one_option.str())
                return
            }
            val picked = remember(items, seed) { Dice.pick(items) }
            val shuffled = remember(items, seed) { Dice.shuffled(items) }
            ResultCard {
                Text(Res.string.picked.str(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RollingText(picked.orEmpty(), seed, MaterialTheme.typography.headlineMedium, Modifier.fillMaxWidth()) { items.random() }
                KeyValueRow(Res.string.random_options.str(), items.size.toString(), copyable = false)
            }
            OutputCard(shuffled.joinToString("\n"), title = Res.string.shuffled_order.str())
        }

        RandomMode.BYTES -> {
            val size = bytesText.trim().toIntOrNull()
            if (size == null || size !in 1..1024) {
                ErrorText(Res.string.enter_a_size_from_1_to_1024_bytes.str())
                return
            }
            val bytes = remember(size, seed) { secureRandomBytes(size) }
            ResultCard {
                KeyValueRow("hex", bytes.toHex())
                KeyValueRow("HEX", bytes.toHex(upper = true))
                KeyValueRow("Base64", Base64Tools.encode(bytes, urlSafe = false, padding = true))
                KeyValueRow("Base64 URL-safe", Base64Tools.encode(bytes, urlSafe = true, padding = false))
            }
        }
    }
}
