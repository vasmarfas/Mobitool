package com.vasmarfas.card.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsMetric
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.Interest
import com.vasmarfas.card.data.InterestGroup
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.data.Onboarding
import com.vasmarfas.card.data.OnboardingStatus
import com.vasmarfas.card.data.Role
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.ToolRegistry
import com.vasmarfas.card.ui.components.caseMark
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

const val OnboardingFirstRun = "first_run"

private val OnboardingWidth = 640.dp

private enum class Step(val id: String) { ROLE("role"), INTERESTS("interests"), RESULT("result") }

@Composable
fun OnboardingScreen(entry: String, onFinish: (saved: Boolean) -> Unit) {
    val settings = LocalSettings.current
    val firstRun = entry == OnboardingFirstRun
    val started = remember { TimeSource.Monotonic.markNow() }
    var step by rememberSaveable { mutableStateOf(Step.ROLE) }
    var roleIds by rememberSaveable { mutableStateOf(settings.roles.toList()) }
    var interestIds by rememberSaveable { mutableStateOf(settings.interests.toList()) }
    var unchecked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val roles = Role.byIds(roleIds)
    val roleKey = Onboarding.roleKey(roleIds)
    val interests = Interest.entries.filter { it.id in interestIds }
    val proposed = (settings.myTools + Onboarding.propose(roles, interests)).distinct()
    val extras = Onboarding.extras(roles, interests, proposed)
    val chosen = proposed.filter { it !in unchecked } + extras.filter { it in added }

    LaunchedEffect(Unit) { Analytics.log(AnalyticsEvent.ONBOARDING_START, mapOf(AnalyticsParam.ENTRY to entry)) }
    LaunchedEffect(step) {
        val params = mapOf(AnalyticsParam.STEP to step.id, AnalyticsParam.ENTRY to entry)
        Analytics.log(
            AnalyticsEvent.ONBOARDING_STEP,
            roleKey?.let { params + (AnalyticsParam.ROLE to it) } ?: params,
            mapOf(AnalyticsMetric.STEP_INDEX to step.ordinal + 1L),
        )
    }

    fun finish(status: OnboardingStatus, tools: List<String>) {
        val params = mapOf(AnalyticsParam.ENTRY to entry)
        roles.forEach { Analytics.log(AnalyticsEvent.ONBOARDING_ROLE, params + (AnalyticsParam.ROLE to it.id)) }
        interests.forEach { Analytics.log(AnalyticsEvent.ONBOARDING_INTEREST, params + (AnalyticsParam.INTEREST to it.id)) }
        Analytics.log(
            AnalyticsEvent.ONBOARDING_COMPLETE,
            params + mapOf(AnalyticsParam.ROLE to (roleKey ?: "none"), AnalyticsParam.STATE to status.name.lowercase()),
            mapOf(
                AnalyticsMetric.PROPOSED to proposed.size.toLong(),
                AnalyticsMetric.KEPT to proposed.count { it in tools }.toLong(),
                AnalyticsMetric.ADDED to extras.count { it in tools }.toLong(),
                AnalyticsMetric.ROLES to roles.size.toLong(),
                AnalyticsMetric.INTERESTS to interests.size.toLong(),
                AnalyticsMetric.SECONDS to started.elapsedNow().inWholeSeconds,
            ),
        )
        settings.finishOnboarding(status, roleIds.toSet(), interestIds.toSet(), tools)
        onFinish(true)
    }

    fun skip() {
        Analytics.log(AnalyticsEvent.ONBOARDING_SKIP, mapOf(AnalyticsParam.STEP to step.id, AnalyticsParam.ENTRY to entry))
        when {
            !firstRun -> onFinish(false)
            settings.myTools.isNotEmpty() -> finish(OnboardingStatus.SKIPPED, settings.myTools)
            else -> finish(OnboardingStatus.SKIPPED, if (interests.isEmpty()) Onboarding.STARTER else Onboarding.propose(roles, interests))
        }
    }

    fun back() {
        Analytics.log(AnalyticsEvent.ONBOARDING_BACK, mapOf(AnalyticsParam.STEP to step.id, AnalyticsParam.ENTRY to entry))
        step = Step.entries[step.ordinal - 1]
    }

    fun next() {
        if (step == Step.RESULT) finish(OnboardingStatus.DONE, chosen) else step = Step.entries[step.ordinal + 1]
    }

    fun toggle(id: String, on: Boolean, fromMore: Boolean) {
        if (fromMore) {
            added = if (on) added + id else added - id
        } else {
            unchecked = if (on) unchecked - id else unchecked + id
        }
        Analytics.log(
            AnalyticsEvent.ONBOARDING_TOOL_TOGGLE,
            mapOf(
                AnalyticsParam.TOOL to id,
                AnalyticsParam.STATE to if (on) "on" else "off",
                AnalyticsParam.SOURCE to if (fromMore) "more" else "proposal",
            ),
        )
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = step != Step.ROLE || !firstRun,
        onBackCompleted = { if (step != Step.ROLE) back() else skip() },
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            val primary = MaterialTheme.colorScheme.primary
            val background = MaterialTheme.colorScheme.background
            Column(Modifier.widthIn(max = OnboardingWidth).fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(remember(primary, background) { caseMark(primary, background) }, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(Res.string.onboarding_step_of, step.ordinal + 1, Step.entries.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (step != Step.RESULT || !firstRun) {
                        TextButton(onClick = ::skip, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(if (firstRun) Res.string.skip.str() else Res.string.cancel.str())
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { (step.ordinal + 1f) / Step.entries.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                )
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val shift = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(210, delayMillis = 90)) + slideInHorizontally(tween(300)) { it / 12 * shift }) togetherWith fadeOut(tween(90))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { current ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(
                        modifier = Modifier.widthIn(max = OnboardingWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (current) {
                            Step.ROLE -> RoleStep(roles) { role ->
                                val before = roles.flatMap { it.interests }.map { it.id }.toSet()
                                roleIds = if (role.id in roleIds) roleIds - role.id else roleIds + role.id
                                val after = Role.byIds(roleIds).flatMap { it.interests }.map { it.id }.toSet()
                                interestIds = (after + (interestIds - before)).toList()
                            }
                            Step.INTERESTS -> InterestsStep(interestIds) { interest ->
                                interestIds = if (interest.id in interestIds) interestIds - interest.id else interestIds + interest.id
                            }
                            Step.RESULT -> ResultStep(proposed, extras, chosen, ::toggle)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.widthIn(max = OnboardingWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step != Step.ROLE) {
                    TextButton(onClick = ::back, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(Res.string.back.str()) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = ::next,
                    enabled = when (step) {
                        Step.ROLE -> roles.isNotEmpty()
                        Step.INTERESTS -> interests.isNotEmpty()
                        Step.RESULT -> chosen.isNotEmpty()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(
                        when {
                            step != Step.RESULT -> Res.string.next.str()
                            firstRun -> pluralStringResource(Res.plurals.onboarding_add, chosen.size, chosen.size)
                            else -> Res.string.save.str()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoleStep(selected: List<Role>, onToggle: (Role) -> Unit) {
    StepTitle(
        Res.string.onboarding_role_title.str(),
        pluralStringResource(Res.plurals.onboarding_intro, ToolRegistry.all.size, ToolRegistry.all.size),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Role.entries.forEach { role ->
            val active = role in selected
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardDefaults.shape)
                    .toggleable(value = active, role = SemanticsRole.Checkbox, onValueChange = { onToggle(role) })
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
                border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(role.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(role.title.str(), style = MaterialTheme.typography.titleMedium)
                        Text(role.hint.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = active, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestsStep(selected: List<String>, onToggle: (Interest) -> Unit) {
    StepTitle(Res.string.onboarding_interests_title.str(), Res.string.onboarding_interests_hint.str())
    InterestGroup.entries.forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title.str(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Interest.entries.filter { it.group == group }.forEach { interest ->
                    val on = interest.id in selected
                    FilterChip(
                        selected = on,
                        onClick = { onToggle(interest) },
                        label = { Text(interest.title.str()) },
                        leadingIcon = {
                            Icon(if (on) Icons.Filled.Check else interest.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultStep(proposed: List<String>, extras: List<String>, chosen: List<String>, onToggle: (String, Boolean, Boolean) -> Unit) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    StepTitle(
        Res.string.onboarding_result_title.str(),
        pluralStringResource(Res.plurals.onboarding_result_hint, chosen.size, chosen.size),
    )
    val tools = proposed.mapNotNull { ToolRegistry.byId(it) }
    ToolCategory.entries.forEach { category ->
        val inCategory = tools.filter { it.category == category }
        if (inCategory.isEmpty()) return@forEach
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(category.title.str(), style = MaterialTheme.typography.titleSmall)
            }
            inCategory.forEach { tool ->
                ToolCheckRow(tool.icon, tool.title.str(), tool.id in chosen) { onToggle(tool.id, it, false) }
            }
        }
    }
    if (extras.isEmpty()) return
    TextButton(onClick = { showMore = !showMore }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Icon(if (showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.onboarding_more, extras.size))
    }
    if (showMore) {
        Column {
            extras.mapNotNull { ToolRegistry.byId(it) }.forEach { tool ->
                ToolCheckRow(tool.icon, tool.title.str(), tool.id in chosen) { onToggle(tool.id, it, true) }
            }
        }
    }
}

@Composable
private fun ToolCheckRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = SemanticsRole.Checkbox, onValueChange = onChange)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
