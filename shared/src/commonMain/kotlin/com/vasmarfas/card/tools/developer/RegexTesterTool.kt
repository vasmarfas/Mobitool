package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.text.OutputCard
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private enum class RegexTab { TEST, BUILD }

val regexTesterTool = Tool(
    id = "regex-tester",
    category = ToolCategory.DEVELOPER,
    title = Res.string.regex_tester,
    description = Res.string.regex_tester_description,
    icon = Icons.Filled.FindInPage,
    keywords = listOf(
        "regex", "regexp", "regular expression", "pattern", "match", "groups", "builder", "generator", "library",
        "регулярное выражение", "регулярка", "шаблон", "конструктор", "библиотека",
    ),
) { RegexTesterScreen() }

@Composable
private fun RegexTesterScreen() {
    var tab by rememberSaveable { mutableStateOf(RegexTab.TEST) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var ignoreCase by rememberSaveable { mutableStateOf(false) }
    var multiline by rememberSaveable { mutableStateOf(false) }
    val blocks = remember {
        mutableStateListOf(RegexBlock(RegexBlockKind.CHARACTERS, set = RegexCharSet.DIGIT, quantifier = RegexQuantifier.ONE_OR_MORE))
    }
    SegmentedChoice(
        options = RegexTab.entries,
        selected = tab,
        onSelect = { tab = it },
        label = { if (it == RegexTab.TEST) Res.string.regex_tab_test.str() else Res.string.regex_tab_build.str() },
    )
    when (tab) {
        RegexTab.TEST -> RegexTest(pattern, { pattern = it }, text, { text = it }, ignoreCase, { ignoreCase = it }, multiline, { multiline = it })
        RegexTab.BUILD -> RegexBuilderPanel(
            blocks = blocks,
            sample = text,
            onSample = { text = it },
            ignoreCase = ignoreCase,
            onIgnoreCase = { ignoreCase = it },
            multiline = multiline,
            onMultiline = { multiline = it },
            onTest = {
                pattern = it
                tab = RegexTab.TEST
            },
        )
    }
}

@Composable
private fun RegexTest(
    pattern: String,
    onPattern: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    ignoreCase: Boolean,
    onIgnoreCase: (Boolean) -> Unit,
    multiline: Boolean,
    onMultiline: (Boolean) -> Unit,
) {
    var replacement by rememberSaveable { mutableStateOf("") }
    var dotAll by rememberSaveable { mutableStateOf(false) }
    var showCheatSheet by rememberSaveable { mutableStateOf(false) }
    val result = rememberRegexRun(pattern, text, ignoreCase, multiline, dotAll, replacement.ifEmpty { null })
    val error = result?.errorText()
    ToolInputField(
        value = pattern,
        onValueChange = onPattern,
        label = Res.string.pattern.str(),
        placeholder = "(\\w+)@(\\w+)\\.com",
        isError = error != null,
        supportingText = error,
        monospace = true,
    )
    SwitchRow(Res.string.ignore_case_i.str(), ignoreCase, onIgnoreCase)
    SwitchRow(Res.string.multiline_and_per_line_m.str(), multiline, onMultiline)
    SwitchRow(Res.string.dot_matches_newline_s.str(), dotAll, { dotAll = it })
    ToolInputField(
        value = text,
        onValueChange = onText,
        label = Res.string.test_text.str(),
        singleLine = false,
        minLines = 4,
    )
    ToolInputField(
        value = replacement,
        onValueChange = { replacement = it },
        label = Res.string.replacement_optional_1_for_groups.str(),
        monospace = true,
    )
    SwitchRow(Res.string.show_cheat_sheet.str(), showCheatSheet, { showCheatSheet = it })
    if (showCheatSheet) {
        ResultCard(Res.string.cheat_sheet.str()) {
            RegexTester.cheatSheet.forEach { (token, description) -> KeyValueRow(token, description.str(), mono = false, copyable = false) }
        }
    }
    if (pattern.isEmpty() || result == null || error != null) return
    val matches = result.matches
    KeyValueRow(Res.string.matches.str(), if (matches.size >= RegexTester.MAX_MATCHES) "${matches.size}+" else matches.size.toString(), copyable = false)
    if (matches.isEmpty()) return
    val status = LocalStatusColors.current
    val colors = listOf(status.series[2].copy(alpha = 0.3f), status.warn.copy(alpha = 0.35f))
    val highlighted = remember(result) {
        val source = result.text
        buildAnnotatedString {
            var pos = 0
            matches.forEachIndexed { i, m ->
                if (m.start > pos) append(source.substring(pos, m.start))
                if (m.end > m.start) {
                    withStyle(SpanStyle(background = colors[i % 2])) { append(source.substring(m.start, m.end)) }
                }
                pos = maxOf(pos, m.end)
            }
            if (pos < source.length) append(source.substring(pos))
        }
    }
    ResultCard(Res.string.highlighted.str()) {
        Text(highlighted, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = monoFamily()))
    }
    ResultCard(Res.string.match_list.str()) {
        val groupsLabel = Res.string.regex_groups.str()
        MonoTable(
            listOf("#    " + Res.string.regex_header_range.str().padEnd(13) + Res.string.regex_header_match.str()) +
                matches.take(200).map { m ->
                    val groups = if (m.groups.isEmpty()) "" else "    $groupsLabel: " + m.groups.mapIndexed { gi, g -> "\$${gi + 1}=${g ?: "null"}" }.joinToString("  ")
                    "${(m.index + 1).toString().padEnd(5)}${"[${m.start}, ${m.end})".padEnd(13)}${m.value}$groups"
                },
        )
    }
    if (result.replaced != null) OutputCard(result.replaced, title = Res.string.replacement_preview.str())
}

@Composable
fun rememberRegexRun(pattern: String, text: String, ignoreCase: Boolean, multiline: Boolean, dotAll: Boolean, replacement: String?): RegexRunResult? {
    val result by produceState<RegexRunResult?>(null, pattern, text, ignoreCase, multiline, dotAll, replacement) {
        value = withContext(Dispatchers.Default) { RegexTester.run(pattern, text, ignoreCase, multiline, dotAll, replacement) }
    }
    return result
}

@Composable
fun RegexRunResult.errorText(): String? = when {
    timedOut -> stringResource(Res.string.regex_timeout, RegexTester.TIME_LIMIT_MS / 1000)
    error != null -> stringResource(Res.string.invalid_regex, error)
    else -> null
}
