package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schema
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
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.delay

private const val DEBOUNCE_MS = 400L

val jsonTypesTool = Tool(
    id = "json-to-types",
    category = ToolCategory.DEVELOPER,
    title = Res.string.json_types,
    description = Res.string.json_types_description,
    icon = Icons.Filled.Schema,
    keywords = listOf(
        "json", "kotlin", "swift", "typescript", "data class", "codable", "interface", "serializable", "model", "pojo", "dto",
        "типы", "модель", "класс", "структура",
    ),
) { JsonTypesScreen() }

@Composable
private fun JsonTypesScreen() {
    var input by rememberSaveable { mutableStateOf("") }
    var rootName by rememberSaveable { mutableStateOf("Root") }
    var target by rememberSaveable { mutableStateOf(TypeTarget.KOTLIN) }
    var serializable by rememberSaveable { mutableStateOf(true) }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = "JSON",
        singleLine = false,
        minLines = 6,
        placeholder = "{\"id\": 1, \"first-name\": \"Ann\", \"tags\": [\"a\", \"b\"]}",
        monospace = true,
    )
    ToolInputField(value = rootName, onValueChange = { rootName = it }, label = Res.string.json_types_root.str(), monospace = true)
    SegmentedChoice(options = TypeTarget.entries, selected = target, onSelect = { target = it }, label = { it.title })
    if (target == TypeTarget.KOTLIN) SwitchRow(Res.string.json_types_serializable.str(), serializable, { serializable = it })
    val outcome = produceState<DataFormatOutcome?>(null, input, rootName, target, serializable) {
        value = generate(input, rootName, target, serializable)
    }.value ?: return
    if (outcome.error != null) ErrorText(outcome.error) else OutputCard(outcome.output)
}

private suspend fun generate(text: String, rootName: String, target: TypeTarget, serializable: Boolean): DataFormatOutcome? {
    if (text.isBlank()) return null
    delay(DEBOUNCE_MS)
    return DataFormats.outcome { JsonTypes.generate(DataFormats.parse(text, DataFormat.JSON), rootName, target, serializable) }
}
