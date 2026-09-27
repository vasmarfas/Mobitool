package com.vasmarfas.card.tools.everyday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.text.TextStats
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.rememberCopy

private const val SCRATCHPAD_KEY = "scratchpad.text"

val scratchpadTool = Tool(
    id = "notes-scratchpad",
    category = ToolCategory.EVERYDAY,
    title = Res.string.scratchpad,
    description = Res.string.notes_scratchpad_description,
    icon = Icons.AutoMirrored.Filled.Note,
    keywords = listOf("notes", "scratchpad", "text", "clipboard", "заметки", "блокнот", "текст", "черновик"),
) { ScratchpadScreen() }

@Composable
private fun ScratchpadScreen() {
    var text by rememberSaveable { mutableStateOf(Prefs.store.get(SCRATCHPAD_KEY) ?: "") }
    val copy = rememberCopy()
    ToolInputField(
        value = text,
        onValueChange = {
            text = it
            Prefs.store.put(SCRATCHPAD_KEY, it)
        },
        label = Res.string.note.str(),
        singleLine = false,
        minLines = 8,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ActionButton(
            text = Res.string.copy.str(),
            onClick = { copy(text) },
            enabled = text.isNotEmpty(),
            icon = Icons.Filled.ContentCopy,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            text = Res.string.clear.str(),
            onClick = {
                text = ""
                Prefs.store.put(SCRATCHPAD_KEY, "")
            },
            enabled = text.isNotEmpty(),
            icon = Icons.Filled.Delete,
            modifier = Modifier.weight(1f),
        )
    }
    val stats = remember(text) { TextStats.analyze(text) }
    ResultCard {
        KeyValueRow(Res.string.characters_count.str(), stats.chars.toString(), copyable = false)
        KeyValueRow(Res.string.without_spaces.str(), stats.charsNoSpaces.toString(), copyable = false)
        KeyValueRow(Res.string.words.str(), stats.words.toString(), copyable = false)
        KeyValueRow(Res.string.lines.str(), stats.lines.toString(), copyable = false)
    }
}
