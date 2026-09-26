package com.vasmarfas.card.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.APP_VERSION
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.AppConfig
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.platformInfo
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.supportsDynamicColor
import com.vasmarfas.card.data.GithubStars
import com.vasmarfas.card.data.HabrStats
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.data.ProfileRepository
import com.vasmarfas.card.data.ResumeRepository
import com.vasmarfas.card.data.ThemeMode
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ContentColumn
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.PageMaxWidth
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.theme.seedPresets
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onRunOnboarding: () -> Unit) {
    val settings = LocalSettings.current
    val profileState by ProfileRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val info = platformInfo()
    ContentColumn(maxWidth = PageMaxWidth) {
        Text(Res.string.settings.str(), style = MaterialTheme.typography.headlineSmall)

        SettingsCard(Res.string.appearance.str()) {
            SettingsLabel(Res.string.language.str())
            SegmentedChoice(
                options = Lang.entries,
                selected = settings.lang,
                onSelect = { settings.updateLang(it) },
                label = { it.nativeName },
            )
            SettingsLabel(Res.string.theme.str())
            SegmentedChoice(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                onSelect = { settings.updateThemeMode(it) },
                label = {
                    when (it) {
                        ThemeMode.SYSTEM -> Res.string.system.str()
                        ThemeMode.LIGHT -> Res.string.light.str()
                        ThemeMode.DARK -> Res.string.dark.str()
                    }
                },
            )
            if (supportsDynamicColor()) {
                SwitchRow(
                    Res.string.dynamic_color.str(),
                    settings.dynamicColor,
                    { settings.updateDynamicColor(it) },
                    description = Res.string.dynamic_color_hint.str(),
                )
            }
            val accentUsed = !(supportsDynamicColor() && settings.dynamicColor)
            Column(
                modifier = Modifier.alpha(if (accentUsed) 1f else 0.4f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsLabel(Res.string.accent_color.str())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    seedPresets.forEach { (seed, title) ->
                        val selected = seed == settings.seedColor
                        val name = title.str()
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .selectable(
                                    selected = selected,
                                    enabled = accentUsed,
                                    role = Role.RadioButton,
                                    onClick = { settings.updateSeedColor(seed) },
                                )
                                .pointerHoverIcon(PointerIcon.Hand)
                                .semantics { contentDescription = name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (selected) 32.dp else 36.dp)
                                    .clip(CircleShape)
                                    .background(Color(seed))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            )
                        }
                    }
                }
            }
        }

        SettingsCard(Res.string.behaviour.str()) {
            SwitchRow(
                Res.string.wide_tables.str(),
                settings.wideTables,
                { settings.updateWideTables(it) },
                description = Res.string.wide_tables_hint.str(),
            )
            if (currentPlatform != PlatformKind.WEB) {
                Text(Res.string.pick_again_hint.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onRunOnboarding, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(Res.string.pick_again.str())
                }
            }
        }

        SettingsCard(Res.string.profile_data.str()) {
            val profile = profileState.value
            KeyValueRow(Res.string.profile_data_updated.str(), profile?.updated ?: "—", mono = false, copyable = false)
            KeyValueRow(
                Res.string.source.str(),
                profileState.source.name.lowercase() + (profileState.error?.let { " · $it" } ?: ""),
                mono = false,
                copyable = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    text = Res.string.refresh.str(),
                    onClick = {
                        scope.launch {
                            ProfileRepository.refresh()
                            ResumeRepository.refresh()
                            GithubStars.refresh(profileState.value?.projects.orEmpty(), force = true)
                            HabrStats.refresh(profileState.value?.articles.orEmpty(), force = true)
                        }
                    },
                    enabled = !profileState.refreshing,
                )
                TextButton(onClick = { openUrl(AppConfig.contentUrl("profile.json")) }) { Text("profile.json") }
            }
        }

        SettingsCard(Res.string.about.str()) {
            KeyValueRow(Res.string.version.str(), APP_VERSION, mono = false, copyable = false)
            KeyValueRow(Res.string.platform.str(), "${info.kind.title.str()} · ${info.osName} ${info.osVersion} · ${info.deviceModel}", mono = false, copyable = false)
            Text(Res.string.built_with.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutLink(Res.string.source_code.str(), AppConfig.REPO_URL, "source")
                AboutLink("vasmarfas.com", AppConfig.SITE_COM, "site_com")
                AboutLink("vasmarfas.ru", AppConfig.SITE_RU, "site_ru")
                AboutLink(Res.string.privacy_policy.str(), "${AppConfig.REPO_URL}/blob/master/privacy-policy.md", "privacy")
                AboutLink(Res.string.terms.str(), "${AppConfig.REPO_URL}/blob/master/terms.md", "terms")
            }
        }
    }
}

@Composable
private fun AboutLink(text: String, url: String, id: String) {
    AssistChip(
        onClick = {
            Analytics.log(AnalyticsEvent.LINK_OPEN, mapOf(AnalyticsParam.LINK to id))
            openUrl(url)
        },
        label = { Text(text) },
        leadingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
