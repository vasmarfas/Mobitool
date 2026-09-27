package com.vasmarfas.card.tools.text

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableChart
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
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

val asciiTableTool = Tool(
    id = "ascii-table",
    category = ToolCategory.TEXT,
    title = Res.string.ascii_table,
    description = Res.string.ascii_table_description,
    icon = Icons.Filled.TableChart,
    keywords = listOf("ascii", "table", "character codes", "hex", "octal", "binary", "аски", "таблица", "коды символов"),
) { AsciiTableScreen() }

@Composable
private fun AsciiTableScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var includeControl by rememberSaveable { mutableStateOf(false) }
    ToolInputField(
        value = query,
        onValueChange = { query = it },
        label = Res.string.ascii_search_character_decimal_hex.str(),
        placeholder = "A · 65 · 0x41 · tilde",
    )
    SwitchRow(Res.string.show_control_characters_0_31_127.str(), includeControl, { includeControl = it })
    val rows = remember(query, includeControl) { AsciiTable.search(query, includeControl) }
    ResultCard {
        if (rows.isEmpty()) {
            Text(Res.string.nothing_found.str(), style = MaterialTheme.typography.bodyMedium)
        } else {
            MonoTable(
                listOf(Res.string.ascii_header_dec.str().padEnd(5) + "Hex  Oct  Bin       " + Res.string.ascii_header_char.str().padEnd(6) + Res.string.ascii_header_name.str()) +
                    rows.map { e ->
                        e.code.toString().padEnd(5) + e.hex.padEnd(5) + e.oct.padEnd(5) + e.bin.padEnd(10) + e.symbol.padEnd(6) + stringResource(e.name, e.symbol)
                    },
            )
        }
    }
}
