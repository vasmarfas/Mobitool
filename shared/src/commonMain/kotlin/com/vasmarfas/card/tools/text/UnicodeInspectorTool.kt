package com.vasmarfas.card.tools.text

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiSymbols
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.pluralStringResource

private enum class UnicodeMode { TEXT, CODE_POINT }

val unicodeInspectorTool = Tool(
    id = "unicode-inspector",
    category = ToolCategory.TEXT,
    title = Res.string.unicode_inspector,
    description = Res.string.unicode_inspector_description,
    icon = Icons.Filled.EmojiSymbols,
    keywords = listOf("unicode", "utf-8", "utf-16", "code point", "escape", "character", "юникод", "символ", "кодовая точка", "экранирование"),
) { UnicodeInspectorScreen() }

@Composable
private fun UnicodeInspectorScreen() {
    var mode by rememberSaveable { mutableStateOf(UnicodeMode.TEXT) }
    var input by rememberSaveable { mutableStateOf("") }
    var codeInput by rememberSaveable { mutableStateOf("U+1F600") }
    SegmentedChoice(
        options = UnicodeMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = { if (it == UnicodeMode.TEXT) Res.string.text.str() else Res.string.code_point.str() },
    )
    when (mode) {
        UnicodeMode.TEXT -> {
            ToolInputField(
                value = input,
                onValueChange = { input = it },
                label = Res.string.text.str(),
                singleLine = false,
                minLines = 2,
            )
            if (input.isEmpty()) return
            val infos = remember(input) { UnicodeInfo.inspect(input) }
            ResultCard(Res.string.escapes.str()) {
                KeyValueRow("\\uXXXX (UTF-16)", UnicodeInfo.escapeUtf16(input))
                KeyValueRow("\\u{X} (ES6)", UnicodeInfo.escapeCodePoints(input))
                KeyValueRow("HTML &#x…;", UnicodeInfo.htmlHex(input))
                KeyValueRow("HTML &#…;", UnicodeInfo.htmlDecimal(input))
                KeyValueRow("URL", UnicodeInfo.urlEncode(input))
            }
            ResultCard(Res.string.characters.str()) {
                val header = listOf(
                    Res.string.ascii_header_char.str(),
                    Res.string.code_point.str(),
                    Res.string.ascii_header_dec.str(),
                    "UTF-8",
                    "UTF-16",
                    Res.string.category.str(),
                    Res.string.block.str(),
                )
                val widths = listOf(6, 10, 9, 13, 11, 29).mapIndexed { column, width -> maxOf(width, header[column].length + 2) }
                fun line(cells: List<String>) = cells.dropLast(1).mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("") + cells.last()
                MonoTable(
                    listOf(line(header)) +
                        infos.map { i -> line(listOf(i.text, i.hex, i.cp.toString(), i.utf8, i.utf16, i.category, i.block)) },
                )
                if (input.codePointList().size > infos.size) {
                    Text(pluralStringResource(Res.plurals.unicode_first_characters_shown, infos.size, infos.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        UnicodeMode.CODE_POINT -> {
            val cp = remember(codeInput) { UnicodeInfo.parseCodePoint(codeInput) }
            ToolInputField(
                value = codeInput,
                onValueChange = { codeInput = it },
                label = Res.string.code_point.str(),
                placeholder = "U+0416 · 1046 · \\u0416 · &#x416;",
                isError = codeInput.isNotBlank() && cp == null,
                monospace = true,
            )
            if (cp == null) {
                if (codeInput.isNotBlank()) ErrorText(Res.string.not_a_valid_code_point.str())
                return
            }
            val info = remember(cp) { UnicodeInfo.info(cp) }
            ResultCard {
                Text(info.text, style = MaterialTheme.typography.displayMedium)
                KeyValueRow(Res.string.code_point.str(), info.hex)
                KeyValueRow(Res.string.decimal_format.str(), cp.toString())
                KeyValueRow("UTF-8", info.utf8)
                KeyValueRow("UTF-16", info.utf16)
                KeyValueRow(Res.string.category.str(), info.category, mono = false, copyable = false)
                KeyValueRow(Res.string.block.str(), info.block, mono = false, copyable = false)
                KeyValueRow("\\u", UnicodeInfo.escapeUtf16(info.text))
                KeyValueRow("HTML", UnicodeInfo.htmlHex(info.text))
                KeyValueRow("URL", UnicodeInfo.urlEncode(info.text))
            }
        }
    }
}
