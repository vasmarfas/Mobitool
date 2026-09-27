package com.vasmarfas.card.tools.security

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
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
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val checksumCompareTool = Tool(
    id = "checksum-compare",
    category = ToolCategory.SECURITY,
    title = Res.string.checksum_compare,
    description = Res.string.checksum_compare_description,
    icon = Icons.AutoMirrored.Filled.CompareArrows,
    keywords = listOf("checksum", "hash", "compare", "verify", "integrity", "sha256", "md5", "контрольная сумма", "хеш", "сравнить", "целостность"),
) { ChecksumCompareScreen() }

fun normalizeHash(text: String): String {
    var best = ""
    var run = StringBuilder()
    for (group in text.trim().split(Regex("\\s+"))) {
        val digits = group.removePrefix("0x").filterNot { it == ':' || it == '-' }
        if (digits.isNotEmpty() && digits.all { it.digitToIntOrNull(16) != null }) run.append(digits) else run = StringBuilder()
        if (run.length > best.length) best = run.toString()
    }
    return best.ifEmpty { text.filterNot { it.isWhitespace() } }.lowercase()
}

private fun detectAlgorithm(hash: String): StringResource? = when (hash.length) {
    8 -> Res.string.crc32
    32 -> Res.string.md5
    40 -> Res.string.sha_1
    56 -> Res.string.sha_224
    64 -> Res.string.sha_256
    96 -> Res.string.sha_384
    128 -> Res.string.sha_512
    else -> null
}

@Composable
private fun ChecksumCompareScreen() {
    var left by rememberSaveable { mutableStateOf("") }
    var right by rememberSaveable { mutableStateOf("") }
    ToolInputField(
        value = left,
        onValueChange = { left = it },
        label = Res.string.expected_checksum.str(),
        singleLine = false,
        minLines = 2,
        monospace = true,
    )
    ToolInputField(
        value = right,
        onValueChange = { right = it },
        label = Res.string.actual_checksum.str(),
        singleLine = false,
        minLines = 2,
        monospace = true,
    )
    if (left.isBlank() || right.isBlank()) return
    val a = remember(left) { normalizeHash(left) }
    val b = remember(right) { normalizeHash(right) }
    if (a.length < 8 || b.length < 8) {
        ErrorText(Res.string.checksum_too_short.str())
        return
    }
    val hexOnly = a.all { it.digitToIntOrNull(16) != null } && b.all { it.digitToIntOrNull(16) != null }
    val match = a == b
    ResultCard {
        KeyValueRow(
            Res.string.result.str(),
            if (match) Res.string.match.str() else Res.string.do_not_match.str(),
            mono = false,
            copyable = false,
        )
        KeyValueRow(Res.string.length.str(), "${a.length} / ${b.length}", copyable = false)
        val algorithm = detectAlgorithm(a) ?: detectAlgorithm(b)
        KeyValueRow(
            Res.string.detected_type.str(),
            algorithm?.str() ?: Res.string.unknown_length.str(),
            mono = false,
            copyable = false,
        )
        if (!hexOnly) {
            Text(
                Res.string.checksum_one_of_the_values.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!match) {
            if (a.length != b.length) {
                ErrorText(Res.string.checksum_lengths_differ_so_these.str())
            } else {
                val index = a.indices.first { a[it] != b[it] }
                ErrorText(stringResource(Res.string.checksum_first_difference, index + 1, a[index].toString(), b[index].toString()))
            }
        }
        KeyValueRow(Res.string.normalized_expected.str(), a)
        KeyValueRow(Res.string.normalized_actual.str(), b)
    }
}
