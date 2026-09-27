package com.vasmarfas.card.tools.security

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource

private enum class GenMode { PASSWORD, PASSPHRASE }

private enum class PhraseLang { EN, RU }

val passwordGeneratorTool = Tool(
    id = "password-generator",
    category = ToolCategory.SECURITY,
    title = Res.string.password_generator,
    description = Res.string.password_generator_description,
    icon = Icons.Filled.Password,
    keywords = listOf("password", "generator", "random", "passphrase", "entropy", "secure", "пароль", "генератор", "случайный", "фраза", "энтропия"),
) { PasswordGeneratorScreen() }

@Composable
private fun PasswordGeneratorScreen() {
    var mode by rememberSaveable { mutableStateOf(GenMode.PASSWORD) }
    var length by rememberSaveable { mutableStateOf(20) }
    var count by rememberSaveable { mutableStateOf(1) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var excludeAmbiguous by rememberSaveable { mutableStateOf(false) }
    var requireEach by rememberSaveable { mutableStateOf(true) }
    var words by rememberSaveable { mutableStateOf(4) }
    var phraseLang by rememberSaveable { mutableStateOf(PhraseLang.EN) }
    var separator by rememberSaveable { mutableStateOf("-") }
    var capitalize by rememberSaveable { mutableStateOf(false) }
    var addNumber by rememberSaveable { mutableStateOf(true) }
    var seed by remember { mutableStateOf(0) }
    val sets = CharSets(lower, upper, digits, symbols, excludeAmbiguous)
    val wordList = if (phraseLang == PhraseLang.EN) WordLists.english else WordLists.russian
    val problem = when {
        mode == GenMode.PASSWORD && sets.groups.isEmpty() -> Res.string.select_at_least_one_character_set
        mode == GenMode.PASSWORD && requireEach && length < sets.groups.size -> Res.string.password_length_is_smaller
        else -> null
    }
    val results = remember(mode, length, count, sets, requireEach, words, phraseLang, separator, capitalize, addNumber, seed, problem) {
        if (problem != null) {
            emptyList()
        } else {
            List(count) {
                if (mode == GenMode.PASSWORD) {
                    PasswordGen.password(length, sets, requireEach).orEmpty()
                } else {
                    PasswordGen.passphrase(words, wordList, separator, capitalize, addNumber)
                }
            }
        }
    }
    SegmentedChoice(
        options = GenMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = { if (it == GenMode.PASSWORD) Res.string.password.str() else Res.string.passphrase.str() },
    )
    if (problem != null) {
        ErrorText(problem.str())
    } else {
        ResultCard {
            results.forEach { result ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(result, style = MaterialTheme.typography.titleMedium.copy(fontFamily = monoFamily()))
                    }
                    CopyIconButton(result)
                }
            }
        }
    }
    ActionButton(Res.string.generate.str(), onClick = { seed++ }, icon = Icons.Filled.Refresh)
    if (mode == GenMode.PASSWORD) {
        Text(Res.string.length.str() + ": $length")
        Slider(
            value = length.toFloat(),
            onValueChange = { length = it.roundToInt() },
            valueRange = 4f..128f,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(Res.string.lowercase_a_z.str(), lower, { lower = it })
        SwitchRow(Res.string.uppercase_a_z.str(), upper, { upper = it })
        SwitchRow(Res.string.digits_0_9.str(), digits, { digits = it })
        SwitchRow(Res.string.symbols.str(), symbols, { symbols = it })
        SwitchRow(Res.string.exclude_ambiguous_il1o0.str(), excludeAmbiguous, { excludeAmbiguous = it })
        SwitchRow(Res.string.at_least_one_from_each_set.str(), requireEach, { requireEach = it })
    } else {
        Text(Res.string.words.str() + ": $words")
        Slider(
            value = words.toFloat(),
            onValueChange = { words = it.roundToInt() },
            valueRange = 3f..12f,
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedChoice(
            options = PhraseLang.entries,
            selected = phraseLang,
            onSelect = { phraseLang = it },
            label = { if (it == PhraseLang.EN) Res.string.english.str() else Res.string.russian.str() },
        )
        ToolInputField(value = separator, onValueChange = { separator = it }, label = Res.string.separator.str(), monospace = true)
        SwitchRow(Res.string.capitalize_words.str(), capitalize, { capitalize = it })
        SwitchRow(Res.string.append_a_number.str(), addNumber, { addNumber = it })
    }
    Text(Res.string.count.str() + ": $count")
    Slider(
        value = count.toFloat(),
        onValueChange = { count = it.roundToInt() },
        valueRange = 1f..20f,
        steps = 18,
        modifier = Modifier.fillMaxWidth(),
    )
    if (problem != null) return
    val bits = if (mode == GenMode.PASSWORD) {
        PasswordGen.entropyBits(sets.alphabet.length, length)
    } else {
        PasswordGen.passphraseEntropyBits(wordList.size, words, addNumber)
    }
    ResultCard(Res.string.strength.str()) {
        KeyValueRow(Res.string.entropy.str(), "${bits.fmt(1)} ${Res.string.unit_bit.str()}", copyable = false)
        KeyValueRow(Res.string.rating.str(), PasswordGen.strengthLabel(bits).str(), mono = false, copyable = false)
        if (mode == GenMode.PASSWORD) {
            KeyValueRow(Res.string.alphabet_size.str(), sets.alphabet.length.toString(), copyable = false)
        } else {
            KeyValueRow(Res.string.dictionary_size.str(), wordList.size.toString(), copyable = false)
        }
        KeyValueRow(
            Res.string.offline_attack_10_12_guesses_s.str(),
            crackTimeText(PasswordGen.crackTimeSeconds(bits, 1e12)),
            mono = false,
            copyable = false,
        )
        KeyValueRow(
            Res.string.online_attack_10_4_guesses_s.str(),
            crackTimeText(PasswordGen.crackTimeSeconds(bits, 1e4)),
            mono = false,
            copyable = false,
        )
    }
}

@Composable
fun crackTimeText(seconds: Double): String = when {
    seconds < 1 -> Res.string.crack_instantly.str()
    seconds < 60 -> countText(Res.plurals.crack_seconds, seconds)
    seconds < 3600 -> countText(Res.plurals.crack_minutes, seconds / 60)
    seconds < 86_400 -> countText(Res.plurals.crack_hours, seconds / 3600)
    seconds < 2_592_000 -> countText(Res.plurals.crack_days, seconds / 86_400)
    seconds < 31_536_000 -> countText(Res.plurals.crack_months, seconds / 2_592_000)
    seconds < 31_536_000_000.0 -> countText(Res.plurals.year_count, seconds / 31_536_000)
    seconds < 31_536_000_000_000.0 -> countText(Res.plurals.crack_thousand_years, seconds / 31_536_000_000.0)
    seconds < 3.1536e16 -> countText(Res.plurals.crack_million_years, seconds / 31_536_000_000_000.0)
    else -> Res.string.crack_longer_than_the_universe.str()
}

@Composable
private fun countText(resource: PluralStringResource, value: Double): String = value.toInt().let { pluralStringResource(resource, it, it) }
