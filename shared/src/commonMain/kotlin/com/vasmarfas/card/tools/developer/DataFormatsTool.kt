package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.text.OutputCard
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.coroutines.delay

private const val DEBOUNCE_MS = 400L

val dataFormatsTool = Tool(
    id = "yaml-toml-converter",
    category = ToolCategory.DEVELOPER,
    title = Res.string.data_formats,
    description = Res.string.data_formats_description,
    icon = Icons.Filled.SyncAlt,
    keywords = listOf("yaml", "yml", "toml", "json", "convert", "config", "конвертер", "конфиг", "преобразовать"),
) { DataFormatsScreen() }

@Composable
private fun DataFormatsScreen() {
    var input by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf(DataFormat.JSON) }
    var to by rememberSaveable { mutableStateOf(DataFormat.YAML) }
    ToolSection(Res.string.data_formats_from.str()) {
        SegmentedChoice(options = DataFormat.entries, selected = from, onSelect = { from = it }, label = { it.name })
    }
    ToolSection(Res.string.to.str()) {
        SegmentedChoice(options = DataFormat.entries, selected = to, onSelect = { to = it }, label = { it.name })
    }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = from.name,
        singleLine = false,
        minLines = 6,
        placeholder = when (from) {
            DataFormat.JSON -> "{\"name\": \"demo\", \"ports\": [80, 443]}"
            DataFormat.YAML -> "name: demo\nports:\n  - 80\n  - 443"
            DataFormat.TOML -> "name = \"demo\"\nports = [80, 443]"
        },
        monospace = true,
    )
    Hint(Res.string.data_formats_limits.str())
    val outcome = produceState<DataFormatOutcome?>(null, input, from, to) {
        value = convert(input, from, to)
    }.value ?: return
    if (outcome.error != null) ErrorText(outcome.error) else OutputCard(outcome.output)
}

private suspend fun convert(text: String, from: DataFormat, to: DataFormat): DataFormatOutcome? {
    if (text.isBlank()) return null
    delay(DEBOUNCE_MS)
    return DataFormats.outcome { DataFormats.write(DataFormats.parse(text, from), to) }
}
