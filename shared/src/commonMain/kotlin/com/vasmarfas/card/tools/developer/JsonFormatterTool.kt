package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.fmtGrouped
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.OpenFileButton
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class JsonMode { FORMAT, TREE, MINIFY, ESCAPE, UNESCAPE }

private const val MAX_INPUT_CHARS = 8 * 1024 * 1024
private const val MAX_VISIBLE_CHARS = 200_000
private const val EDITABLE_CHARS = 100_000
private const val PREVIEW_CHARS = 2_000
private const val DEBOUNCE_MS = 400L

private class JsonOutcome(
    val element: JsonElement?,
    val output: String,
    val stats: JsonStats?,
    val inputBytes: Int,
    val outputBytes: Int,
    val error: String?,
)

val jsonFormatterTool = Tool(
    id = "json-formatter",
    category = ToolCategory.DEVELOPER,
    title = Res.string.json_formatter,
    description = Res.string.json_formatter_description,
    icon = Icons.Filled.DataObject,
    keywords = listOf("json", "format", "pretty", "minify", "validate", "beautify", "escape", "tree", "viewer", "джсон", "форматирование", "валидация", "дерево"),
    expandable = true,
) { JsonFormatterScreen() }

@Composable
private fun JsonFormatterScreen() {
    val theInputIsLargerThan8MbSplitTheDocuText = Res.string.json_input_is_larger.str()
    var input by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(JsonMode.FORMAT) }
    var indent by rememberSaveable { mutableStateOf(2) }
    var sortKeys by rememberSaveable { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<JsonOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fileError by remember(input) { mutableStateOf<String?>(null) }
    val lang = LocalLang.current
    LaunchedEffect(input, mode, indent, sortKeys, lang) {
        val text = input
        when {
            text.isBlank() -> {
                outcome = null
                busy = false
            }

            text.length > MAX_INPUT_CHARS -> {
                outcome = JsonOutcome(
                    null, "", null, 0, 0,
                    theInputIsLargerThan8MbSplitTheDocuText,
                )
                busy = false
            }

            else -> {
                busy = true
                delay(DEBOUNCE_MS)
                outcome = withContext(Dispatchers.Default) { process(text, mode, indent, sortKeys) }
                busy = false
            }
        }
    }
    ChoiceChips(
        options = JsonMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                JsonMode.FORMAT -> Res.string.format.str()
                JsonMode.TREE -> Res.string.tree.str()
                JsonMode.MINIFY -> Res.string.minify.str()
                JsonMode.ESCAPE -> Res.string.escape.str()
                JsonMode.UNESCAPE -> Res.string.unescape.str()
            }
        },
    )
    OpenFileButton(setOf("json", "geojson", "txt")) { file ->
        fileError = null
        if (file.size() > MAX_INPUT_CHARS) {
            fileError = theInputIsLargerThan8MbSplitTheDocuText
            return@OpenFileButton
        }
        runCatching { TextDecoding.decode(file.readBytes()) }.onSuccess { input = it }.onFailure { fileError = it.message ?: it.toString() }
    }
    fileError?.let { ErrorText(it) }
    if (input.length > EDITABLE_CHARS) {
        LoadedDocument(input) { input = "" }
    } else {
        ToolInputField(
            value = input,
            // a long paste swaps the field for the card, and on the web the field then reports its stale empty text
            onValueChange = { if (input.length <= EDITABLE_CHARS) input = it },
            label = when (mode) {
                JsonMode.ESCAPE -> Res.string.text.str()
                JsonMode.UNESCAPE -> Res.string.escaped_string.str()
                else -> "JSON"
            },
            singleLine = false,
            minLines = 6,
            placeholder = if (mode == JsonMode.FORMAT) "{\"a\": [1, 2, {\"b\": null}]}" else null,
            monospace = true,
        )
    }
    if (mode == JsonMode.FORMAT) {
        SegmentedChoice(
            options = listOf(2, 4),
            selected = indent,
            onSelect = { indent = it },
            label = { pluralStringResource(Res.plurals.json_indent_spaces, it, it) },
        )
    }
    if (mode == JsonMode.FORMAT || mode == JsonMode.MINIFY || mode == JsonMode.TREE) {
        SwitchRow(Res.string.sort_keys.str(), sortKeys, { sortKeys = it })
    }
    if (busy) {
        LoadingRow(Res.string.parsing.str())
        return
    }
    val result = outcome ?: return
    if (result.error != null) {
        ErrorText(result.error)
        return
    }
    if (mode == JsonMode.TREE) JsonTreeView(result) else CappedOutput(result.output)
    val stats = result.stats ?: return
    ResultCard(Res.string.structure.str()) {
        KeyValueRow(Res.string.keys.str(), stats.keys.fmtGrouped(), copyable = false)
        KeyValueRow(Res.string.max_depth.str(), stats.depth.toString(), copyable = false)
        KeyValueRow(Res.string.objects_arrays.str(), "${stats.objects} / ${stats.arrays}", copyable = false)
        KeyValueRow(
            Res.string.strings_numbers_booleans_nulls.str(),
            "${stats.strings} / ${stats.numbers} / ${stats.booleans} / ${stats.nulls}",
            copyable = false,
        )
        if (result.output.isNotEmpty()) {
            KeyValueRow(
                Res.string.size_input_output.str(),
                "${result.inputBytes.fmtGrouped()} B → ${result.outputBytes.fmtGrouped()} B",
                copyable = false,
            )
        }
    }
}

@Composable
private fun LoadedDocument(text: String, onClear: () -> Unit) {
    ResultCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(Res.plurals.document_characters, text.length, text.length.fmtGrouped()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(Res.string.clear.str()) }
        }
        Text(
            text.take(PREVIEW_CHARS),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()),
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            Res.string.json_input_field_is_hidden.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CappedOutput(text: String) {
    ResultCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Res.string.result.str(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CopyIconButton(text)
        }
        MonoText(if (text.length > MAX_VISIBLE_CHARS) text.take(MAX_VISIBLE_CHARS) else text)
        if (text.length > MAX_VISIBLE_CHARS) {
            Text(
                stringResource(Res.string.output_shown_first, MAX_VISIBLE_CHARS.fmtGrouped(), text.length.fmtGrouped()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JsonTreeView(outcome: JsonOutcome) {
    val root = outcome.element ?: return
    var tree by remember(outcome) { mutableStateOf(JsonTreeState()) }
    var selected by rememberSaveable { mutableStateOf("") }
    val result = remember(outcome, tree) { JsonTree.rows(root, tree) }
    ToolInputField(
        value = tree.query,
        onValueChange = { tree = tree.copy(query = it) },
        label = Res.string.filter_by_key_or_value.str(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            text = Res.string.expand_all.str(),
            onClick = { tree = tree.copy(overrides = emptyMap(), autoDepth = Int.MAX_VALUE) },
        )
        ActionButton(
            text = Res.string.collapse_all.str(),
            onClick = { tree = tree.copy(overrides = emptyMap(), autoDepth = 0) },
        )
    }
    if (selected.isNotEmpty()) {
        ResultCard {
            KeyValueRow(Res.string.path.str(), selected)
        }
    }
    ResultCard(Res.string.tree.str()) {
        if (result.rows.isEmpty()) {
            Text(Res.string.nothing_matches_the_filter.str(), style = MaterialTheme.typography.bodyMedium)
            return@ResultCard
        }
        Column(Modifier.fillMaxWidth()) {
            result.rows.forEach { row ->
                JsonTreeRow(
                    row = row,
                    selected = row.more == 0 && row.path == selected,
                    onClick = {
                        if (row.more > 0) {
                            tree = tree.showMore(row.path)
                        } else {
                            selected = row.path
                            if (row.expandable) tree = tree.toggle(row.path, row.depth)
                        }
                    },
                )
            }
        }
        if (result.truncated) {
            Text(
                stringResource(Res.string.json_tree_rows_capped, JsonTree.MAX_ROWS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JsonTreeRow(row: JsonRow, selected: Boolean, onClick: () -> Unit) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily())
    val guide = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(row.depth) {
            Box(Modifier.width(14.dp).fillMaxHeight()) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(guide))
            }
        }
        if (row.more > 0) {
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(Res.string.json_tree_show_more, row.more.fmtGrouped()),
                style = mono,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            return@Row
        }
        if (row.expandable) {
            Icon(
                imageVector = if (row.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(row.label, style = mono, maxLines = 1)
        Text(
            text = when (row.kind) {
                JsonKind.OBJECT -> "  {…} " + pluralStringResource(Res.plurals.json_key_count, row.childCount, row.childCount.fmtGrouped())
                JsonKind.ARRAY -> "  […] " + pluralStringResource(Res.plurals.json_item_count, row.childCount, row.childCount.fmtGrouped())
                else -> "  ${row.value}"
            },
            style = mono,
            color = kindColor(row.kind),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun kindColor(kind: JsonKind): Color = when (kind) {
    JsonKind.STRING -> MaterialTheme.colorScheme.tertiary
    JsonKind.NUMBER -> MaterialTheme.colorScheme.primary
    JsonKind.BOOLEAN -> MaterialTheme.colorScheme.secondary
    JsonKind.NULL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private suspend fun process(text: String, mode: JsonMode, indent: Int, sortKeys: Boolean): JsonOutcome = when (mode) {
    JsonMode.ESCAPE -> {
        val output = JsonTools.escapeString(text)
        JsonOutcome(null, output, null, JsonTools.utf8Length(text), JsonTools.utf8Length(output), null)
    }

    JsonMode.UNESCAPE -> {
        val output = JsonTools.unescapeString(text)
        if (output == null) {
            JsonOutcome(null, "", null, 0, 0, getString(Res.string.not_a_valid_escaped_json_string))
        } else {
            JsonOutcome(null, output, null, JsonTools.utf8Length(text), JsonTools.utf8Length(output), null)
        }
    }

    else -> {
        val parsed = JsonTools.parse(text)
        val element = parsed.getOrNull()
        if (element == null) {
            val failure = parsed.exceptionOrNull()!!
            val message = if (failure is JsonTooDeepException) {
                getString(Res.string.json_too_deep, JsonTools.MAX_DEPTH)
            } else {
                getString(Res.string.invalid_json, JsonTools.errorMessage(failure))
            }
            JsonOutcome(null, "", null, 0, 0, message)
        } else {
            val prepared = if (sortKeys) JsonTools.sortKeys(element) else element
            val output = when (mode) {
                JsonMode.TREE -> ""
                JsonMode.MINIFY -> JsonTools.minify(prepared)
                else -> JsonTools.format(prepared, indent)
            }
            JsonOutcome(prepared, output, JsonTools.stats(prepared), JsonTools.utf8Length(text), JsonTools.utf8Length(output), null)
        }
    }
}
