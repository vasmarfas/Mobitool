package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.TagChips
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.rememberCopy
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

val licensePickerTool = Tool(
    id = "license-picker",
    category = ToolCategory.DEVELOPER,
    title = Res.string.license_picker,
    description = Res.string.license_picker_description,
    icon = Icons.Filled.Gavel,
    keywords = listOf(
        "license", "licence", "spdx", "mit", "gpl", "agpl", "lgpl", "apache", "bsd", "mpl", "creative commons", "cc by", "cc0",
        "polyform", "open source", "copyleft", "unlicense", "busl", "lgpl", "ofl", "odbl", "cern ohl", "readme", "copyright",
        "лицензия", "лицензии", "открытый код", "открытая лицензия", "свободная лицензия", "копилефт", "авторское право",
        "креатив коммонс", "указать автора", "совместимость",
    ),
    expandable = true,
) { LicenseScreen() }

private enum class LicenseTab(val title: StringResource, val icon: ImageVector) {
    PICK(Res.string.license_tab_pick, Icons.Filled.Checklist),
    CATALOG(Res.string.license_tab_catalog, Icons.AutoMirrored.Filled.MenuBook),
    COMPARE(Res.string.license_tab_compare, Icons.AutoMirrored.Filled.CompareArrows),
    TABLE(Res.string.license_tab_table, Icons.Filled.TableChart),
    COMPAT(Res.string.license_tab_compat, Icons.AutoMirrored.Filled.Rule),
    IDENTIFY(Res.string.license_tab_identify, Icons.Filled.FindInPage),
    APPLY(Res.string.license_tab_apply, Icons.Filled.PostAdd),
}

private const val SHOWN_MATCHES = 6

internal const val MAX_COMPARED = 4

internal class LicenseActions(
    val details: (License) -> Unit,
    val compare: (License) -> Unit,
    val apply: (License) -> Unit,
)

@Composable
private fun LicenseScreen() {
    var tab by rememberSaveable { mutableStateOf(LicenseTab.PICK) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    var compared by rememberSaveable { mutableStateOf("MIT,Apache-2.0,GPL-3.0") }
    var applied by rememberSaveable { mutableStateOf("MIT") }
    val top = remember { BringIntoViewRequester() }
    val actions = LicenseActions(
        details = { opened = it.id; reading = null; tab = LicenseTab.CATALOG },
        compare = { license ->
            compared = (compared.split(',').filter { it.isNotEmpty() && it != license.id } + license.id).takeLast(MAX_COMPARED).joinToString(",")
            tab = LicenseTab.COMPARE
        },
        apply = { applied = it.id; tab = LicenseTab.APPLY },
    )
    ChoiceChips(
        options = LicenseTab.entries,
        selected = tab,
        onSelect = { tab = it },
        label = { it.title.str() },
        icon = { it.icon },
        modifier = Modifier.bringIntoViewRequester(top),
    )
    LaunchedEffect(tab, reading) {
        if (tab != LicenseTab.CATALOG || opened == null || reading != null) top.bringIntoView()
    }
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(tab) {
        when (tab) {
            LicenseTab.PICK -> PickTab(actions)
            LicenseTab.CATALOG -> {
                val text = reading?.let(Licenses::byId)
                if (text != null) {
                    LicenseReader(text) { reading = null }
                } else {
                    CatalogTab(opened, { opened = it }, { reading = it.id }, actions)
                }
            }
            LicenseTab.COMPARE -> CompareTab(compared.split(',').mapNotNull(Licenses::byId)) { list -> compared = list.joinToString(",") { it.id } }
            LicenseTab.TABLE -> TableTab(actions)
            LicenseTab.COMPAT -> CompatTab()
            LicenseTab.IDENTIFY -> IdentifyTab(actions)
            LicenseTab.APPLY -> ApplyTab(Licenses.byId(applied) ?: Licenses.all.first()) { applied = it.id }
        }
    }
    Hint(Res.string.license_disclaimer.str())
}

@Composable
internal fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun License.shortName(): String = if (family == LicenseFamily.PROPRIETARY) Res.string.license_proprietary.str() else short

@Composable
internal fun License.fullName(): String = if (family == LicenseFamily.PROPRIETARY) Res.string.license_proprietary.str() else name

@Composable
private fun PickTab(actions: LicenseActions) {
    var subject by rememberSaveable { mutableStateOf(LicenseSubject.CODE) }
    var commercial by rememberSaveable { mutableStateOf(PickCommercial.ANY) }
    var copyleft by rememberSaveable { mutableStateOf(PickCopyleft.NONE) }
    var changes by rememberSaveable { mutableStateOf(true) }
    var credit by rememberSaveable { mutableStateOf(true) }
    var patent by rememberSaveable { mutableStateOf(false) }
    var delayed by rememberSaveable { mutableStateOf(false) }
    var gpl by rememberSaveable { mutableStateOf(false) }
    var priority by rememberSaveable { mutableStateOf(LicensePriority.POPULAR) }

    Question(Res.string.license_q_subject.str())
    ChoiceChips(options = LicenseSubject.entries, selected = subject, onSelect = { subject = it }, label = { it.title.str() })
    val commercialOptions = remember(subject) { LicensePicker.commercialOptions(subject) }
    val chosenCommercial = commercial.takeIf { it in commercialOptions } ?: commercialOptions.first()
    if (commercialOptions.size > 1) {
        Question(Res.string.license_q_commercial.str())
        ChoiceChips(options = commercialOptions, selected = chosenCommercial, onSelect = { commercial = it }, label = { it.title.str() })
    }
    var chosenCopyleft = copyleft
    if (chosenCommercial.openQuestions) {
        SwitchRow(Res.string.license_q_changes.str(), changes, { changes = it }, description = Res.string.license_q_changes_hint.str())
        val copyleftOptions = remember(subject, chosenCommercial) { LicensePicker.copyleftOptions(subject, chosenCommercial) }
        chosenCopyleft = copyleft.takeIf { it in copyleftOptions } ?: copyleftOptions.firstOrNull() ?: PickCopyleft.NONE
        if (changes && copyleftOptions.size > 1) {
            Question(Res.string.license_q_copyleft.str())
            ChoiceChips(options = copyleftOptions, selected = chosenCopyleft, onSelect = { copyleft = it }, label = { it.title.str() })
        }
        SwitchRow(Res.string.license_q_credit.str(), credit, { credit = it })
    }
    val needs = LicenseNeeds(subject, chosenCommercial, chosenCopyleft, changes, credit, patent, delayed, gpl, priority)
    if (needs.asksPatent) {
        SwitchRow(Res.string.license_q_patent.str(), patent, { patent = it }, description = Res.string.license_q_patent_hint.str())
    }
    if (needs.asksDelayed) {
        SwitchRow(Res.string.license_q_delayed.str(), delayed, { delayed = it }, description = Res.string.license_q_delayed_hint.str())
    }
    if (needs.asksGpl) {
        SwitchRow(Res.string.license_q_gpl.str(), gpl, { gpl = it }, description = Res.string.license_q_gpl_hint.str())
    }
    Question(Res.string.license_q_priority.str())
    ChoiceChips(options = LicensePriority.entries, selected = priority, onSelect = { priority = it }, label = { it.title.str() })

    val matches = remember(needs) { LicensePicker.pick(needs).take(SHOWN_MATCHES) }
    if (matches.isEmpty()) {
        ErrorText(Res.string.license_nothing_fits.str())
        return
    }
    ResultCard(Res.string.license_best.str()) {
        matches.forEachIndexed { index, match ->
            if (index > 0) SoftDivider()
            val license = match.license
            Text("${index + 1}. ${license.fullName()}", style = MaterialTheme.typography.titleSmall)
            Text(reasons(match, needs).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            license.notes.firstOrNull()?.let { Hint(it.str()) }
            LicenseButtons(license, actions, details = true)
        }
    }
}

@Composable
private fun reasons(match: LicenseMatch, needs: LicenseNeeds): List<String> = buildList {
    val license = match.license
    if (license.commercial != Commercial.ANY && license.commercial != Commercial.NONE) add(license.commercial.cell.str())
    if (LicenseFlag.MODIFY !in license && license.family != LicenseFamily.PROPRIETARY) add(Res.string.license_why_nd.str())
    when (license.copyleft) {
        Copyleft.FILE -> add(Res.string.license_why_file.str())
        Copyleft.LIBRARY -> add(Res.string.license_why_library.str())
        Copyleft.PROJECT -> add(Res.string.license_why_strong.str())
        Copyleft.NETWORK -> add(Res.string.license_why_network.str())
        Copyleft.NONE -> Unit
    }
    if (match.copyleftGap > 0) {
        add(if (license.copyleft.level < needs.copyleft.level) Res.string.license_why_less_copyleft.str() else Res.string.license_why_more_copyleft.str())
    }
    if (LicenseFlag.PATENT in license && needs.asksPatent) add(Res.string.license_why_patent.str())
    if (LicenseFlag.DELAYED in license) add(Res.string.license_why_delayed.str())
    if (needs.subject == LicenseSubject.CODE && license.family != LicenseFamily.PROPRIETARY) {
        add(if (LicenseFlag.OSI in license) Res.string.license_why_osi.str() else Res.string.license_why_not_osi.str())
    }
    if (needs.gpl && LicenseFlag.GPL_COMPATIBLE in license) add(Res.string.license_why_gpl.str())
    if (license.popularity >= 4) add(Res.string.license_why_popular.str())
    if (license.words in 1..<300) add(pluralStringResource(Res.plurals.license_why_words, license.words, license.words))
    if (license.family != LicenseFamily.PROPRIETARY) {
        add(if (LicenseFlag.NOTICE in license) Res.string.license_why_credit.str() else Res.string.license_why_no_credit.str())
    }
}

@Composable
internal fun LicenseButtons(license: License, actions: LicenseActions, details: Boolean = false, onRead: (() -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (details) TextButton(onClick = { actions.details(license) }) { Text(Res.string.license_details.str()) }
        if (onRead != null) TextButton(onClick = onRead) { Text(Res.string.license_full_text.str()) }
        TextButton(onClick = { actions.compare(license) }) { Text(Res.string.license_compare_action.str()) }
        TextButton(onClick = { actions.apply(license) }) { Text(Res.string.license_apply_action.str()) }
        if (onRead != null && license.url.isNotEmpty()) TextButton(onClick = { openUrl(license.url) }) { Text(Res.string.license_official.str()) }
    }
}

@Composable
private fun CatalogTab(opened: String?, onOpen: (String?) -> Unit, onRead: (License) -> Unit, actions: LicenseActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<LicenseFamily?>(null) }
    ToolInputField(value = query, onValueChange = { query = it }, label = Res.string.license_search.str(), placeholder = "MIT · GPL · CC BY")
    val families = listOf<LicenseFamily?>(null) + LicenseFamily.entries
    ChoiceChips(options = families, selected = family, onSelect = { family = it }, label = { it?.title?.str() ?: Res.string.license_family_all.str() })
    val found = remember(query, family) { Licenses.search(query, family) }
    if (found.isEmpty()) {
        Hint(Res.string.nothing_found.str())
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        found.forEach { license ->
            LicenseCard(license, license.id == opened, { onOpen(if (license.id == opened) null else license.id) }, { onRead(license) }, actions)
        }
    }
}

@Composable
private fun LicenseCard(license: License, open: Boolean, onToggle: () -> Unit, onRead: () -> Unit, actions: LicenseActions) {
    val requester = remember { BringIntoViewRequester() }
    if (open) LaunchedEffect(Unit) { requester.bringIntoView() }
    Card(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        colors = CardDefaults.cardColors(containerColor = if (open) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.clickable(onClick = onToggle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(license.fullName(), style = MaterialTheme.typography.titleMedium)
            Text(
                if (license.inSpdxList) "${license.id} · ${license.family.title.str()}" else license.family.title.str(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(license.summary.str(), style = MaterialTheme.typography.bodyMedium)
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LicenseFacts(license)
                license.notes.forEach { Hint(it.str()) }
                val badges = buildList {
                    if (LicenseFlag.OSI in license) add(Res.string.license_badge_osi.str())
                    if (LicenseFlag.FSF in license) add(Res.string.license_badge_fsf.str())
                    if (LicenseFlag.GPL_COMPATIBLE in license) add(Res.string.license_badge_gpl.str())
                }
                if (badges.isNotEmpty()) TagChips(badges)
                if (license.usedBy.isNotEmpty()) Text(stringResource(Res.string.license_used_by, license.usedBy), style = MaterialTheme.typography.bodySmall)
                if (license.inSpdxList) KeyValueRow("SPDX", if (license.versioned) "${license.spdx(true)} · ${license.spdx(false)}" else license.id)
                LicenseButtons(license, actions, onRead = onRead)
            }
        }
    }
}

private class Fact(val text: String, val kind: Mark)

@Composable
private fun LicenseFacts(license: License) {
    val facts = buildList {
        val proprietary = license.family == LicenseFamily.PROPRIETARY
        val code = LicenseSubject.CODE in license.subjects || LicenseSubject.HARDWARE in license.subjects
        when (license.commercial) {
            Commercial.ANY -> add(Fact(Res.string.license_can_commercial.str(), Mark.YES))
            Commercial.NONE -> add(Fact(Res.string.license_cannot_commercial.str(), Mark.NO))
            else -> add(Fact(stringResource(Res.string.license_must_commercial, license.commercial.cell.str()), Mark.PARTLY))
        }
        add(if (LicenseFlag.MODIFY in license) Fact(Res.string.license_can_modify.str(), Mark.YES) else Fact(Res.string.license_cannot_modify.str(), Mark.NO))
        add(if (LicenseFlag.DISTRIBUTE in license) Fact(Res.string.license_can_distribute.str(), Mark.YES) else Fact(Res.string.license_cannot_distribute.str(), Mark.NO))
        if (proprietary) add(Fact(Res.string.license_cannot_use.str(), Mark.NO))
        if (LicenseFlag.PATENT in license) add(Fact(Res.string.license_can_patent.str(), Mark.YES))
        else if (code && !proprietary) add(Fact(Res.string.license_cannot_patent.str(), Mark.NO))
        if (LicenseFlag.NOTICE in license) add(Fact(Res.string.license_must_notice.str(), Mark.PARTLY))
        if (LicenseFlag.STATE_CHANGES in license) add(Fact(Res.string.license_must_state_changes.str(), Mark.PARTLY))
        if (LicenseFlag.DISCLOSE in license) add(Fact(Res.string.license_must_disclose.str(), Mark.PARTLY))
        when (license.copyleft) {
            Copyleft.FILE -> add(Fact(Res.string.license_must_same_file.str(), Mark.PARTLY))
            Copyleft.LIBRARY -> add(Fact(Res.string.license_must_same_library.str(), Mark.PARTLY))
            Copyleft.PROJECT -> add(Fact(Res.string.license_must_same_project.str(), Mark.PARTLY))
            Copyleft.NETWORK -> {
                add(Fact(Res.string.license_must_same_project.str(), Mark.PARTLY))
                add(Fact(Res.string.license_must_network.str(), Mark.PARTLY))
            }
            Copyleft.NONE -> Unit
        }
        if (LicenseFlag.DELAYED in license) add(Fact(Res.string.license_must_delayed.str(), Mark.YES))
    }
    FactGroup(Res.string.license_permissions.str(), facts.filter { it.kind == Mark.YES }, Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary)
    FactGroup(Res.string.license_conditions.str(), facts.filter { it.kind == Mark.PARTLY }, Icons.Filled.Info, MaterialTheme.colorScheme.tertiary)
    FactGroup(Res.string.license_limitations.str(), facts.filter { it.kind == Mark.NO }, Icons.Filled.Cancel, MaterialTheme.colorScheme.error)
}

@Composable
private fun FactGroup(title: String, facts: List<Fact>, icon: ImageVector, tint: Color) {
    if (facts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        facts.forEach { fact ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(fact.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun rememberLicenseFile(path: String): Result<String>? =
    produceState<Result<String>?>(null, path) { value = runCatching { Res.readBytes(path).decodeToString() } }.value

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicenseReader(license: License, onBack: () -> Unit) {
    val copy = rememberCopy()
    TextButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(Res.string.license_back.str(), modifier = Modifier.padding(start = 8.dp))
    }
    Text(license.fullName(), style = MaterialTheme.typography.titleLarge)
    val loaded = rememberLicenseFile("files/licenses/${license.file}")
    when {
        loaded == null -> LoadingRow()
        loaded.isFailure -> ErrorText(Res.string.license_text_failed.str())
        else -> {
            val text = loaded.getOrThrow()
            val blocks = remember(text) { LicenseTexts.blocks(text, license.markdown) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Res.string.copy.str(), onClick = { copy(text) })
                SaveButton(Res.string.save_file.str()) { saveBytes(text.encodeToByteArray(), licenseFileName(license)) }
            }
            if (license.family == LicenseFamily.PROPRIETARY) Hint(Res.string.license_proprietary_template.str())
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { block ->
                        when (block) {
                            is TextBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                            is TextBlock.Paragraph -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = (block.indent * 16).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun licenseFileName(license: License): String = if (license.markdown) "LICENSE.md" else "LICENSE"
