package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.LocalOpenTool
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.rememberCopy
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val comparePresets = listOf(
    "MIT · Apache · GPL" to listOf("MIT", "Apache-2.0", "GPL-3.0"),
    "GPL 2 · GPL 3 · AGPL" to listOf("GPL-2.0", "GPL-3.0", "AGPL-3.0"),
    "LGPL · MPL · EPL" to listOf("LGPL-3.0", "MPL-2.0", "EPL-2.0"),
    "CC BY · BY-SA · BY-NC" to listOf("CC-BY-4.0", "CC-BY-SA-4.0", "CC-BY-NC-4.0"),
    "BUSL · FSL · Elastic · SSPL" to listOf("BUSL-1.1", "FSL-1.1-MIT", "Elastic-2.0", "SSPL-1.0"),
    "PolyForm" to listOf("PolyForm-Noncommercial-1.0.0", "PolyForm-Small-Business-1.0.0", "LicenseRef-PolyForm-Shield-1.0.0", "LicenseRef-PolyForm-Strict-1.0.0"),
)

private const val DEFAULT_DEPENDENCIES = "kotlinx-coroutines: Apache-2.0\nokhttp: Apache-2.0\nlibsodium: ISC\nffmpeg: LGPL-2.1-or-later\n" +
    "readline: GPL-3.0-or-later\nrust-crate: MIT OR Apache-2.0\nsyncthing: MPL-2.0\nicons: CC-BY-4.0\nfont: OFL-1.1"

private const val PREVIEW_LINES = 14

@Composable
private fun LicenseDropdown(selected: License, label: String, onSelect: (License) -> Unit) {
    DropdownChoice(
        options = Licenses.all,
        selected = selected,
        onSelect = onSelect,
        label = label,
        text = { it.shortName() },
        menuText = { if (it.inSpdxList) "${it.shortName()} · ${it.name}" else it.fullName() },
        searchable = true,
    )
}

@Composable
internal fun CompareTab(compared: List<License>, onChange: (List<License>) -> Unit) {
    ChoiceChips(
        options = comparePresets,
        selected = comparePresets.firstOrNull { preset -> preset.second == compared.map { it.id } },
        onSelect = { preset -> onChange(preset.second.mapNotNull(Licenses::byId)) },
        label = { it.first },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        compared.forEach { license ->
            InputChip(
                selected = true,
                onClick = { onChange(compared - license) },
                label = { Text(license.shortName()) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = Res.string.license_remove.str(), modifier = Modifier.size(18.dp)) },
            )
        }
    }
    if (compared.size < MAX_COMPARED) {
        DropdownChoice(
            options = listOf<License?>(null) + Licenses.all.filter { it !in compared },
            selected = null,
            onSelect = { license -> license?.let { onChange(compared + it) } },
            label = Res.string.license_add.str(),
            text = { it?.fullName() ?: Res.string.license_add_placeholder.str() },
            menuText = { it?.let { license -> if (license.inSpdxList) "${license.shortName()} · ${license.name}" else license.fullName() } ?: "" },
            searchable = true,
        )
    } else {
        Hint(Res.string.license_compare_limit.str())
    }
    if (compared.size < 2) {
        Hint(Res.string.license_compare_empty.str())
        return
    }
    var differences by rememberSaveable { mutableStateOf(false) }
    SwitchRow(Res.string.license_only_differences.str(), differences, { differences = it })
    val features = LicenseFeature.entries.filter { feature ->
        !differences || compared.map { Licenses.value(it, feature).text }.distinct().size > 1
    }
    ResultCard {
        SimpleTable(
            header = listOf(Res.string.license_feature_column.str()) + compared.map { it.shortName() },
            rows = features.map { feature -> listOf(feature.title.str()) + compared.map { Licenses.value(it, feature).text.str() } },
            weights = listOf(1.4f) + compared.map { 1f },
            mono = false,
        )
    }
    compared.forEach { license ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(license.fullName(), style = MaterialTheme.typography.titleSmall)
            Text(license.summary.str(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TableTab(actions: LicenseActions) {
    var grid by rememberSaveable { mutableStateOf(true) }
    var family by rememberSaveable { mutableStateOf<LicenseFamily?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    SwitchRow(Res.string.filament_grid.str(), grid, { grid = it }, description = Res.string.filament_grid_hint.str())
    ToolInputField(value = query, onValueChange = { query = it }, label = Res.string.license_search.str())
    ChoiceChips(
        options = listOf<LicenseFamily?>(null) + LicenseFamily.entries,
        selected = family,
        onSelect = { family = it },
        label = { it?.title?.str() ?: Res.string.license_family_all.str() },
    )
    val licenses = remember(query, family) { Licenses.search(query, family) }
    if (licenses.isEmpty()) {
        Hint(Res.string.nothing_found.str())
        return
    }
    SimpleTable(
        header = listOf(Res.string.license_column.str()) + LicenseFeature.entries.map { it.title.str() },
        rows = licenses.map { license -> listOf(license.shortName()) + LicenseFeature.entries.map { Licenses.value(license, it).text.str() } },
        mono = false,
        wide = grid,
    )
    Hint(Res.string.license_table_legend.str())
    if (licenses.size == 1) {
        TextButton(onClick = { actions.details(licenses.first()) }) { Text(Res.string.license_details.str()) }
    }
}

@Composable
internal fun CompatTab() {
    var projectId by rememberSaveable { mutableStateOf("MIT") }
    var orLater by rememberSaveable { mutableStateOf(true) }
    var usage by rememberSaveable { mutableStateOf(LicenseUsage.DISTRIBUTE) }
    var input by rememberSaveable { mutableStateOf(DEFAULT_DEPENDENCIES) }
    val project = Licenses.byId(projectId) ?: Licenses.all.first()
    LicenseDropdown(project, Res.string.license_project.str()) { projectId = it.id }
    val versionMatters = project.id == "GPL-2.0" || project.id == "LGPL-2.1"
    if (versionMatters) {
        SwitchRow(Res.string.license_or_later.str(), orLater, { orLater = it }, description = Res.string.license_project_or_later_hint.str())
    }
    Question(Res.string.license_q_usage.str())
    ChoiceChips(options = LicenseUsage.entries, selected = usage, onSelect = { usage = it }, label = { it.title.str() })
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.license_dependencies.str(),
        singleLine = false,
        minLines = 6,
        monospace = true,
    )
    Hint(Res.string.license_dependencies_hint.str())
    val lines = remember(input, project, orLater, usage) { LicenseCompat.checkList(input, project, versionMatters && orLater, usage) }
    if (lines.isEmpty()) return
    val counts = Verdict.entries.associateWith { verdict -> lines.count { it.result.verdict == verdict } }
    ResultCard(
        stringResource(
            Res.string.license_compat_summary,
            counts.getValue(Verdict.YES), counts.getValue(Verdict.CONDITIONS), counts.getValue(Verdict.NO), counts.getValue(Verdict.UNKNOWN),
        ),
    ) {
        lines.sortedByDescending { it.result.verdict.ordinal }.forEach { line -> DependencyRow(line) }
    }
}

@Composable
private fun DependencyRow(line: DependencyLine) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint, verdict) = when (line.result.verdict) {
        Verdict.YES -> Triple(Icons.Filled.CheckCircle, colors.primary, Res.string.license_verdict_yes)
        Verdict.CONDITIONS -> Triple(Icons.Filled.Warning, colors.tertiary, Res.string.license_verdict_conditions)
        Verdict.NO -> Triple(Icons.Filled.Cancel, colors.error, Res.string.license_verdict_no)
        Verdict.UNKNOWN -> Triple(Icons.AutoMirrored.Filled.Help, colors.onSurfaceVariant, Res.string.license_verdict_unknown)
    }
    val result = line.result
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = verdict.str(), tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val chosen = if (line.label == line.expression) line.label else "${line.label} · ${line.expression}"
            Text(chosen, style = MaterialTheme.typography.titleSmall)
            val note = if (line.choice && result.verdict != Verdict.UNKNOWN) {
                "${verdict.str()}, ${result.license}"
            } else {
                verdict.str()
            }
            Text(note, style = MaterialTheme.typography.labelMedium, color = tint)
            Text(stringResource(result.reason, *result.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun IdentifyTab(actions: LicenseActions) {
    var input by rememberSaveable { mutableStateOf("") }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.license_identify_input.str(),
        singleLine = false,
        minLines = 6,
        maxLines = 16,
    )
    Hint(Res.string.license_identify_hint.str())
    val prints by produceState<Map<License, Set<String>>?>(null) {
        value = withContext(Dispatchers.Default) {
            Licenses.all.filter { it.family != LicenseFamily.PROPRIETARY }.mapNotNull { license ->
                runCatching { Res.readBytes("files/licenses/${license.file}").decodeToString() }.getOrNull()
                    ?.let { license to LicenseTexts.fingerprint(LicenseTexts.comparable(license, it)) }
            }.toMap()
        }
    }
    if (input.isBlank()) return
    val loaded = prints
    if (loaded == null) {
        LoadingRow()
        return
    }
    val guesses = remember(input, loaded) { LicenseTexts.identify(input, loaded).take(3) }
    if (guesses.isEmpty()) {
        Hint(Res.string.license_identify_short.str())
        return
    }
    if (guesses.first().score < 0.5) Hint(Res.string.license_identify_none.str())
    ResultCard(Res.string.license_identify_result.str()) {
        guesses.forEachIndexed { index, guess ->
            if (index > 0) SoftDivider()
            Text(guess.license.fullName(), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(Res.string.license_identify_match, stringResource(Res.string.percent_value, (guess.score * 100).roundToInt().toString())),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LicenseButtons(guess.license, actions, details = true)
        }
    }
}

private enum class ApplyMode(val title: StringResource) {
    OWN(Res.string.license_apply_own),
    OTHERS(Res.string.license_apply_others),
}

@Composable
internal fun ApplyTab(license: License, onLicense: (License) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(ApplyMode.OWN) }
    SegmentedChoice(options = ApplyMode.entries, selected = mode, onSelect = { mode = it }, label = { it.title.str() })
    when (mode) {
        ApplyMode.OWN -> OwnProject(license, onLicense)
        ApplyMode.OTHERS -> AttributionBuilder()
    }
}

private fun currentYear(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year

@Composable
private fun CodeBlock(text: String, label: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonoText(text)
            }
            CopyIconButton(text)
        }
    }
}

@Composable
private fun OwnProject(license: License, onLicense: (License) -> Unit) {
    var holder by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf(currentYear().toString()) }
    var project by rememberSaveable { mutableStateOf("") }
    var orLater by rememberSaveable { mutableStateOf(true) }
    var style by rememberSaveable { mutableStateOf(CommentStyle.SLASHES) }
    var fullNotice by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LicenseDropdown(license, Res.string.license_choose.str(), onLicense)
    ToolInputField(value = holder, onValueChange = { holder = it }, label = Res.string.license_holder.str(), placeholder = Res.string.license_holder_hint.str())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolInputField(value = year, onValueChange = { year = it }, label = Res.string.year.str(), modifier = Modifier.weight(1f))
        ToolInputField(
            value = project,
            onValueChange = { project = it },
            label = Res.string.license_project_name.str(),
            placeholder = Res.string.optional.str(),
            modifier = Modifier.weight(2f),
        )
    }
    if (license.versioned) {
        SwitchRow(Res.string.license_or_later.str(), orLater, { orLater = it }, description = Res.string.license_or_later_hint.str())
    }
    val spdx = if (license.inSpdxList) license.spdx(orLater) else license.id
    val shownYear = year.trim().ifEmpty { currentYear().toString() }

    val fileName = licenseFileName(license)
    ResultCard(stringResource(Res.string.license_file_title, fileName)) {
        val loaded = rememberLicenseFile("files/licenses/${license.file}")
        when {
            loaded == null -> LoadingRow()
            loaded.isFailure -> ErrorText(Res.string.license_text_failed.str())
            else -> {
                val text = remember(loaded, holder, shownYear, project) { LicenseTexts.fill(license, loaded.getOrThrow(), holder, shownYear, project) }
                val lines = text.lines()
                MonoText(if (expanded || lines.size <= PREVIEW_LINES) text else lines.take(PREVIEW_LINES).joinToString("\n") + "\n…")
                if (lines.size > PREVIEW_LINES) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) Res.string.show_less.str() else Res.string.license_show_all.str())
                    }
                }
                val copy = rememberCopy()
                ActionButton(Res.string.copy.str(), onClick = { copy(text) })
                SaveButton(Res.string.save_file.str()) { saveBytes(text.encodeToByteArray(), fileName) }
                when {
                    license.id == "BUSL-1.1" -> Hint(Res.string.license_note_busl_parameters.str())
                    holder.isBlank() && text.contains('<') -> Hint(Res.string.license_fill_holder.str())
                    text == loaded.getOrNull() && license.family != LicenseFamily.PROPRIETARY -> Hint(Res.string.license_file_verbatim.str())
                }
            }
        }
    }

    ResultCard(Res.string.license_header_title.str()) {
        ChoiceChips(options = CommentStyle.entries, selected = style, onSelect = { style = it }, label = { it.label })
        val noticePath = LicenseTexts.headerFile(license, orLater)
        if (noticePath != null) {
            SwitchRow(Res.string.license_full_notice.str(), fullNotice, { fullNotice = it }, description = Res.string.license_full_notice_hint.str())
        }
        val notice = if (noticePath != null && fullNotice) rememberLicenseFile(noticePath) else null
        if (noticePath != null && fullNotice && notice == null) {
            LoadingRow()
        } else {
            val lines = LicenseTexts.header(spdx, holder, shownYear, project, notice?.getOrNull())
            CodeBlock(LicenseTexts.comment(lines, style))
        }
    }

    ResultCard("README") {
        CodeBlock(LicenseTexts.badge(spdx, license.url), Res.string.license_badge.str())
        val who = holder.trim().ifEmpty { "[${Res.string.license_holder.str()}]" }
        val section = if (license.family == LicenseFamily.PROPRIETARY) {
            stringResource(Res.string.license_readme_proprietary, shownYear, who)
        } else {
            stringResource(Res.string.license_readme_section, shownYear, who, license.shortName(), fileName)
        }
        CodeBlock(section, Res.string.license_readme_text.str())
        if (license.family == LicenseFamily.CREATIVE_COMMONS || license.id == "CC0-1.0") {
            val work = project.trim().ifEmpty { Res.string.license_this_work.str() }
            CodeBlock(stringResource(Res.string.license_cc_marking, work, shownYear, who, license.short, license.url), Res.string.license_cc_marking_title.str())
        }
    }

    ResultCard(Res.string.license_manifest_title.str()) {
        LicenseTexts.manifests(license, spdx).forEach { (file, snippet) -> KeyValueRow(file, snippet) }
    }

    val openTool = LocalOpenTool.current
    TextButton(
        onClick = {
            Prefs.store.put(PRIVACY_HANDOFF_KEY, "${holder.trim()}\n${project.trim()}")
            openTool("privacy-policy")
        },
    ) { Text(Res.string.privacy_from_license.str()) }
}

@Composable
private fun AttributionBuilder() {
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var licenseId by rememberSaveable { mutableStateOf("CC-BY-4.0") }
    var modified by rememberSaveable { mutableStateOf(false) }
    val license = Licenses.byId(licenseId) ?: Licenses.all.first()
    ToolInputField(value = title, onValueChange = { title = it }, label = Res.string.license_attr_title.str())
    ToolInputField(value = author, onValueChange = { author = it }, label = Res.string.license_attr_author.str())
    ToolInputField(value = source, onValueChange = { source = it }, label = Res.string.license_attr_source.str(), placeholder = "https://")
    LicenseDropdown(license, Res.string.license_choose.str()) { licenseId = it.id }
    SwitchRow(Res.string.license_attr_modified.str(), modified, { modified = it })
    when {
        license.family == LicenseFamily.PROPRIETARY -> {
            ErrorText(Res.string.license_attr_proprietary.str())
            return
        }
        modified && LicenseFlag.MODIFY !in license -> ErrorText(Res.string.license_attr_no_changes.str())
        modified && license.copyleft.level >= 2 -> Hint(Res.string.license_attr_share_alike.str())
    }
    if (license.commercial == Commercial.NONE) Hint(Res.string.license_attr_noncommercial.str())
    if (LicenseFlag.NOTICE !in license) Hint(Res.string.license_attr_optional.str())
    if (title.isBlank() || author.isBlank()) {
        Hint(Res.string.license_attr_fill.str())
        return
    }
    val format = if (modified) Res.string.license_attr_format_modified else Res.string.license_attr_format
    val (plain, html, markdown) = LicenseTexts.attribution(Attribution(title.trim(), author.trim(), source, license)).map { stringResource(format, *it.toTypedArray()) }
    ResultCard(Res.string.license_attr_result.str()) {
        CodeBlock(if (source.isBlank()) plain else "$plain ${source.trim()}", Res.string.text.str())
        CodeBlock(html, "HTML")
        CodeBlock(markdown, "Markdown")
    }
}
