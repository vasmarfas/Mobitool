package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.text.OutputCard
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection

private enum class UuidVersion { V4, V7 }

val uuidGeneratorTool = Tool(
    id = "uuid-generator",
    category = ToolCategory.DEVELOPER,
    title = Res.string.uuid_generator,
    description = Res.string.uuid_generator_description,
    icon = Icons.Filled.Fingerprint,
    keywords = listOf("uuid", "guid", "v4", "v7", "identifier", "random", "уникальный идентификатор", "генератор"),
) { UuidGeneratorScreen() }

@Composable
private fun UuidGeneratorScreen() {
    var version by rememberSaveable { mutableStateOf(UuidVersion.V4) }
    var countText by rememberSaveable { mutableStateOf("5") }
    var uppercase by rememberSaveable { mutableStateOf(false) }
    var braces by rememberSaveable { mutableStateOf(false) }
    var seed by remember { mutableStateOf(0) }
    var parseInput by rememberSaveable { mutableStateOf("") }
    SegmentedChoice(
        options = UuidVersion.entries,
        selected = version,
        onSelect = { version = it },
        label = { if (it == UuidVersion.V4) Res.string.v4_random.str() else Res.string.v7_time_ordered.str() },
    )
    val count = countText.trim().toIntOrNull()
    NumberField(
        value = countText,
        onValueChange = { countText = it },
        label = Res.string.count_1_100.str(),
        isError = count == null || count !in 1..100,
    )
    SwitchRow(Res.string.uppercase.str(), uppercase, { uppercase = it })
    SwitchRow(Res.string.braces.str(), braces, { braces = it })
    ActionButton(Res.string.generate.str(), onClick = { seed++ }, icon = Icons.Filled.Refresh)
    if (count != null && count in 1..100) {
        val ids = remember(version, count, seed) {
            List(count) { if (version == UuidVersion.V4) Uuids.v4() else Uuids.v7(currentEpochMillis()) }
        }
        val output = ids.joinToString("\n") { id ->
            val cased = if (uppercase) id.uppercase() else id
            if (braces) "{$cased}" else cased
        }
        OutputCard(output)
    }
    ToolSection(Res.string.uuid_parse.str()) {
        ToolInputField(
            value = parseInput,
            onValueChange = { parseInput = it },
            label = "UUID",
            placeholder = "019364c0-8a4d-7c4b-9d3e-1f2a3b4c5d6e",
            monospace = true,
        )
        if (parseInput.isNotBlank()) {
            val info = remember(parseInput) { Uuids.parse(parseInput) }
            if (info == null) {
                ErrorText(Res.string.uuid_not_a_uuid_expected.str())
            } else {
                ResultCard {
                    KeyValueRow(Res.string.canonical.str(), info.canonical)
                    val versionText = when {
                        info.isNil -> "Nil UUID"
                        info.isMax -> "Max UUID"
                        info.version == 0 -> "—"
                        else -> "v${info.version}"
                    }
                    KeyValueRow(Res.string.version.str(), versionText, copyable = false)
                    KeyValueRow(Res.string.variant.str(), info.variant, mono = false, copyable = false)
                    if (info.timestampMs != null) {
                        KeyValueRow(Res.string.embedded_time.str(), "${localDateTime(info.timestampMs).formatted()}  (${info.timestampMs} ${Res.string.unit_ms.str()})")
                    }
                    KeyValueRow("Hex", info.canonical.replace("-", ""))
                    KeyValueRow("URN", "urn:uuid:" + info.canonical)
                }
            }
        }
    }
}
