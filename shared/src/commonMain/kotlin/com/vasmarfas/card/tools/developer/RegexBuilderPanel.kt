package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection

private const val SHOWN_MATCHES = 10

@Composable
fun RegexBuilderPanel(
    blocks: SnapshotStateList<RegexBlock>,
    sample: String,
    onSample: (String) -> Unit,
    ignoreCase: Boolean,
    onIgnoreCase: (Boolean) -> Unit,
    multiline: Boolean,
    onMultiline: (Boolean) -> Unit,
    onTest: (String) -> Unit,
) {
    var presetIndex by rememberSaveable { mutableStateOf(0) }
    val pattern = RegexBuilder.pattern(blocks)
    ToolSection(Res.string.pattern_library.str()) {
        DropdownChoice(
            options = RegexBuilder.presets.indices.toList(),
            selected = presetIndex,
            onSelect = { presetIndex = it },
            label = Res.string.ready_pattern.str(),
            text = { RegexBuilder.presets[it].title.str() },
        )
        val preset = RegexBuilder.presets[presetIndex]
        ResultCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonoText(preset.pattern, Modifier.weight(1f))
                CopyIconButton(preset.pattern)
            }
            Text(preset.description.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyValueRow(Res.string.sample.str(), preset.sample)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton(
                    text = Res.string.regex_try_in_tester.str(),
                    onClick = {
                        if (sample.isBlank()) onSample(preset.sample)
                        onTest(preset.pattern)
                    },
                    icon = Icons.Filled.PlayArrow,
                )
                TextButton(onClick = { blocks.add(RegexBlock(RegexBlockKind.GROUP, text = preset.pattern, capture = false)) }) {
                    Text(Res.string.insert_as_a_block.str())
                }
            }
        }
    }
    ToolSection(Res.string.add_a_block.str()) {
        ChoiceChips(
            options = RegexBlockKind.entries,
            selected = null,
            onSelect = { blocks.add(RegexBlock(it)) },
            label = { it.title.str() },
        )
    }
    blocks.forEachIndexed { index, block ->
        BlockCard(
            block = block,
            index = index,
            count = blocks.size,
            onChange = { blocks[index] = it },
            onMove = { step ->
                val target = index + step
                val moved = blocks.removeAt(index)
                blocks.add(target, moved)
            },
            onRemove = { blocks.removeAt(index) },
        )
    }
    SwitchRow(Res.string.ignore_case_i.str(), ignoreCase, onIgnoreCase)
    SwitchRow(Res.string.multiline_and_per_line_m.str(), multiline, onMultiline)
    ResultCard(Res.string.pattern.str()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoText(pattern.ifEmpty { "—" }, Modifier.weight(1f))
            CopyIconButton(pattern)
        }
        KeyValueRow(Res.string.with_flags.str(), RegexBuilder.literal(pattern, ignoreCase, multiline))
        if (pattern.isNotEmpty()) {
            ActionButton(text = Res.string.regex_try_in_tester.str(), onClick = { onTest(pattern) }, icon = Icons.Filled.PlayArrow)
        }
    }
    ToolInputField(
        value = sample,
        onValueChange = onSample,
        label = Res.string.regex_sample_text.str(),
        singleLine = false,
        minLines = 3,
    )
    if (pattern.isEmpty()) return
    val test = rememberRegexRun(pattern, sample, ignoreCase, multiline, dotAll = false, replacement = null) ?: return
    val error = test.errorText()
    ResultCard(Res.string.test.str()) {
        if (error != null) {
            ErrorText(error)
        } else {
            KeyValueRow(
                Res.string.matches.str(),
                if (test.matches.size >= RegexTester.MAX_MATCHES) "${test.matches.size}+" else test.matches.size.toString(),
                copyable = false,
            )
            if (test.matches.isNotEmpty()) {
                MonoTable(
                    listOf("#    " + Res.string.regex_header_range.str().padEnd(13) + Res.string.regex_header_match.str()) +
                        test.matches.take(SHOWN_MATCHES).map { match ->
                            "${(match.index + 1).toString().padEnd(5)}${"[${match.start}, ${match.end})".padEnd(13)}${match.value}"
                        },
                )
            }
        }
    }
}

@Composable
private fun BlockCard(
    block: RegexBlock,
    index: Int,
    count: Int,
    onChange: (RegexBlock) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    ResultCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. ${block.kind.title.str()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = Res.string.up.str())
            }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = Res.string.down.str())
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = Res.string.remove.str())
            }
        }
        when (block.kind) {
            RegexBlockKind.LITERAL -> ToolInputField(
                value = block.text,
                onValueChange = { onChange(block.copy(text = it)) },
                label = Res.string.text_to_match.str(),
                placeholder = "id=",
            )

            RegexBlockKind.CHARACTERS -> {
                DropdownChoice(
                    options = RegexCharSet.entries,
                    selected = block.set,
                    onSelect = { onChange(block.copy(set = it)) },
                    label = Res.string.character_class.str(),
                    text = { it.title.str() },
                )
                if (block.set == RegexCharSet.CUSTOM) {
                    ToolInputField(
                        value = block.text,
                        onValueChange = { onChange(block.copy(text = it)) },
                        label = Res.string.characters_of_the_set.str(),
                        placeholder = "a-fA-F0-9",
                        monospace = true,
                    )
                }
            }

            RegexBlockKind.GROUP -> {
                ToolInputField(
                    value = block.text,
                    onValueChange = { onChange(block.copy(text = it)) },
                    label = Res.string.group_content_regex.str(),
                    placeholder = "\\d{2}-\\d{2}",
                    monospace = true,
                )
                SwitchRow(Res.string.capturing_group.str(), block.capture, { onChange(block.copy(capture = it)) })
            }

            RegexBlockKind.ALTERNATION -> ToolInputField(
                value = block.text,
                onValueChange = { onChange(block.copy(text = it)) },
                label = Res.string.options_separated_by.str(),
                placeholder = "GET|POST|PUT",
                monospace = true,
            )

            else -> Unit
        }
        if (block.kind in RegexBuilder.quantifiableKinds) {
            DropdownChoice(
                options = RegexQuantifier.entries,
                selected = block.quantifier,
                onSelect = { onChange(block.copy(quantifier = it)) },
                label = Res.string.repeat.str(),
                text = { it.title.str() },
            )
            if (block.quantifier == RegexQuantifier.EXACTLY || block.quantifier == RegexQuantifier.RANGE) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = block.min,
                        onValueChange = { onChange(block.copy(min = it)) },
                        label = "n",
                        modifier = Modifier.weight(1f),
                    )
                    if (block.quantifier == RegexQuantifier.RANGE) {
                        NumberField(
                            value = block.max,
                            onValueChange = { onChange(block.copy(max = it)) },
                            label = "m",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        MonoText(RegexBuilder.render(block).ifEmpty { "—" })
    }
}
