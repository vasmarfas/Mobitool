package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.rememberCopy
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

val privacyPolicyTool = Tool(
    id = "privacy-policy",
    category = ToolCategory.DEVELOPER,
    title = Res.string.privacy_policy_generator,
    description = Res.string.privacy_policy_description,
    icon = Icons.Filled.Policy,
    keywords = listOf(
        "privacy policy", "privacy", "terms of use", "terms of service", "terms and conditions", "tos", "eula", "gdpr", "coppa",
        "data safety", "app privacy", "google play", "app store", "firebase", "admob", "crashlytics", "appmetrica", "metrica",
        "политика конфиденциальности", "конфиденциальность", "персональные данные", "пользовательское соглашение",
        "условия использования", "152-фз", "метрика", "приватность",
    ),
) { PrivacyPolicyScreen() }

const val PRIVACY_HANDOFF_KEY = "privacy.handoff"

private const val PREVIEW_LINES = 12

private inline fun <reified T : Enum<T>> String.keys(key: (T) -> String): Set<T> =
    split(',').mapNotNull { part -> enumValues<T>().firstOrNull { key(it) == part } }.toSet()

@Composable
private fun PrivacyPolicyScreen() {
    var app by rememberSaveable { mutableStateOf("") }
    var developer by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var product by rememberSaveable { mutableStateOf(PolicyProduct.APP) }
    var data by rememberSaveable { mutableStateOf("analytics,crashes") }
    var sdks by rememberSaveable { mutableStateOf("firebase-analytics,crashlytics") }
    var children by rememberSaveable { mutableStateOf(false) }
    var inAppDeletion by rememberSaveable { mutableStateOf(true) }
    var deletionUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sent = Prefs.store.get(PRIVACY_HANDOFF_KEY) ?: return@LaunchedEffect
        Prefs.store.remove(PRIVACY_HANDOFF_KEY)
        val (holder, project) = (sent.split('\n', limit = 2) + "").take(2)
        if (developer.isBlank()) developer = holder
        if (app.isBlank()) app = project
    }

    ToolInputField(value = app, onValueChange = { app = it }, label = Res.string.privacy_app_name.str())
    ToolInputField(value = developer, onValueChange = { developer = it }, label = Res.string.privacy_developer.str(), placeholder = Res.string.privacy_developer_hint.str())
    ToolInputField(value = contact, onValueChange = { contact = it }, label = Res.string.privacy_contact.str(), placeholder = "mail@example.com")
    ToolInputField(value = country, onValueChange = { country = it }, label = Res.string.country.str(), placeholder = Res.string.privacy_country_hint.str())

    Question(Res.string.privacy_q_product.str())
    SegmentedChoice(options = PolicyProduct.entries, selected = product, onSelect = { product = it }, label = { it.title.str() })

    val chosen = data.keys(PolicyData::key)
    Question(Res.string.privacy_q_data.str())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = chosen.isEmpty(), onClick = { data = "" }, label = { Text(Res.string.privacy_data_nothing.str()) })
        PolicyData.entries.forEach { item ->
            val on = item in chosen
            FilterChip(
                selected = on,
                onClick = { data = (if (on) chosen - item else chosen + item).joinToString(",") { it.key } },
                label = { Text(item.title.str()) },
            )
        }
    }
    val offered = PolicySdk.entries.filter { it.data in chosen && it.fits(product) }
    val picked = sdks.keys(PolicySdk::key)
    if (offered.isNotEmpty()) {
        Question(Res.string.privacy_q_sdks.str())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            offered.forEach { sdk ->
                val on = sdk in picked
                FilterChip(
                    selected = on,
                    onClick = { sdks = (if (on) picked - sdk else picked + sdk).joinToString(",") { it.key } },
                    label = { Text(sdk.title.str()) },
                )
            }
        }
    }
    SwitchRow(Res.string.privacy_children.str(), children, { children = it }, description = Res.string.privacy_children_hint.str())
    if (PolicyData.ACCOUNTS in chosen) {
        SwitchRow(Res.string.privacy_in_app_deletion.str(), inAppDeletion, { inAppDeletion = it })
        ToolInputField(
            value = deletionUrl,
            onValueChange = { deletionUrl = it },
            label = Res.string.privacy_deletion_url.str(),
            placeholder = "https://",
            supportingText = Res.string.privacy_deletion_url_hint.str(),
        )
    }

    val date = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString() }
    val input = PolicyInput(app, developer, contact, country, product, chosen, picked, children, inAppDeletion, deletionUrl, date)
    PolicyOutput(input)
    if (product != PolicyProduct.SITE) StoreAnswersCards(input)
    Hint(Res.string.privacy_disclaimer.str())
}

@Composable
private fun PolicyOutput(input: PolicyInput) {
    val uiLang = LocalLang.current
    var document by rememberSaveable { mutableStateOf(PolicyDocument.PRIVACY) }
    var lang by rememberSaveable { mutableStateOf(uiLang) }
    var format by rememberSaveable { mutableStateOf(PolicyFormat.MARKDOWN) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SegmentedChoice(options = PolicyDocument.entries, selected = document, onSelect = { document = it }, label = { it.title.str() })
    SegmentedChoice(options = Lang.entries, selected = lang, onSelect = { lang = it }, label = { it.nativeName })
    SegmentedChoice(options = PolicyFormat.entries, selected = format, onSelect = { format = it }, label = { it.title })
    if (input.app.isBlank() || input.developer.isBlank() || input.contact.isBlank()) Hint(Res.string.privacy_fill.str())
    val fileName = "${document.fileName}.${format.extension}"
    ResultCard(fileName) {
        val loaded = rememberLicenseFile("files/privacy/${document.key}-${lang.code}.md")
        when {
            loaded == null -> LoadingRow()
            loaded.isFailure -> ErrorText(Res.string.privacy_template_failed.str())
            else -> {
                val template = loaded.getOrThrow()
                val text = remember(template, input, format) {
                    when (format) {
                        PolicyFormat.MARKDOWN -> PolicyTemplates.markdown(template, input)
                        PolicyFormat.HTML -> PolicyTemplates.html(template, input, lang.code)
                    }
                }
                val lines = text.lines()
                MonoText(if (expanded || lines.size <= PREVIEW_LINES) text else lines.take(PREVIEW_LINES).joinToString("\n") + "\n…")
                if (lines.size > PREVIEW_LINES) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) Res.string.show_less.str() else Res.string.license_show_all.str())
                    }
                }
                val copy = rememberCopy()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(Res.string.copy.str(), onClick = { copy(text) })
                    SaveButton(Res.string.save_file.str()) { saveBytes(text.encodeToByteArray(), fileName) }
                }
            }
        }
    }
}

@Composable
private fun StoreAnswersCards(input: PolicyInput) {
    ResultCard(Res.string.privacy_store_play.str()) { StoreAnswersList(PolicyStores.dataSafety(input)) }
    ResultCard(Res.string.privacy_store_apple.str()) { StoreAnswersList(PolicyStores.appPrivacy(input)) }
}

@Composable
private fun StoreAnswersList(answers: StoreAnswers) {
    answers.questions.forEach { (question, answer) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(question.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(answer.str(), style = MaterialTheme.typography.titleSmall)
        }
    }
    answers.types.forEach { type ->
        SoftDivider()
        Text(type.type.str(), style = MaterialTheme.typography.titleSmall)
        Text(type.usage.map { it.str() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
    answers.notes.forEach { Hint(it.str()) }
}
