package com.vasmarfas.card.tools.text

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

val findReplaceTool = Tool(
    id = "find-replace",
    category = ToolCategory.TEXT,
    title = Res.string.find_and_replace,
    description = Res.string.find_replace_description,
    icon = Icons.Filled.FindReplace,
    keywords = listOf("find", "replace", "search", "regex", "substitute", "найти", "заменить", "поиск", "регулярное выражение"),
) { FindReplaceScreen() }

@Composable
private fun FindReplaceScreen() {
    var input by rememberSaveable { mutableStateOf("") }
    var find by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var ignoreCase by rememberSaveable { mutableStateOf(true) }
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.text.str(),
        singleLine = false,
        minLines = 5,
    )
    ToolInputField(
        value = find,
        onValueChange = { find = it },
        label = Res.string.find.str(),
        monospace = true,
    )
    ToolInputField(
        value = replacement,
        onValueChange = { replacement = it },
        label = Res.string.replace_with.str(),
        placeholder = if (useRegex) "\$1" else null,
        monospace = true,
    )
    SwitchRow(Res.string.ignore_case.str(), ignoreCase, { ignoreCase = it })
    SwitchRow(Res.string.whole_words_only.str(), wholeWord, { wholeWord = it })
    SwitchRow(Res.string.regular_expression.str(), useRegex, { useRegex = it })
    val result = remember(input, find, replacement, ignoreCase, useRegex, wholeWord) {
        FindReplace.run(input, find, replacement, ignoreCase, useRegex, wholeWord)
    }
    if (result.error != null) {
        ErrorText(stringResource(Res.string.invalid_regex, result.error))
    } else if (find.isNotEmpty()) {
        KeyValueRow(Res.string.matches.str(), result.count.toString(), copyable = false)
        OutputCard(result.output)
    }
}
