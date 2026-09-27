package com.vasmarfas.card.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.AppConfig
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.setSystemBarsHidden
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.ToolRegistry
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ContentColumn
import com.vasmarfas.card.ui.components.EmptyState
import com.vasmarfas.card.ui.components.ImmersiveMaxWidth
import com.vasmarfas.card.ui.components.LocalChrome
import com.vasmarfas.card.ui.components.LocalToolId
import com.vasmarfas.card.ui.components.PageMaxWidth
import com.vasmarfas.card.ui.components.PlatformBadges
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ToolScreen(toolId: String, onBack: () -> Unit) {
    val tool = ToolRegistry.byId(toolId)
    val settings = LocalSettings.current
    val chrome = LocalChrome.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val web = currentPlatform == PlatformKind.WEB
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val addedText = Res.string.added_to_home.str()
    val removedText = Res.string.removed_from_home.str()
    LaunchedEffect(toolId) { if (tool != null) settings.markRecent(tool.id) }
    LaunchedEffect(chrome.immersive) { setSystemBarsHidden(chrome.immersive) }
    DisposableEffect(Unit) { onDispose { setSystemBarsHidden(false) } }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = chrome.immersive,
        onBackCompleted = { chrome.immersive = false },
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (chrome.immersive) return@Scaffold
            BoxWithConstraints {
                val side = ((maxWidth - PageMaxWidth) / 2).coerceAtLeast(0.dp)
                MediumFlexibleTopAppBar(
                    title = {
                        val style = LocalTextStyle.current
                        Text(
                            tool?.title?.str() ?: Res.string.nothing_found.str(),
                            autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.6f, maxFontSize = style.fontSize),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    subtitle = tool?.let { { Text(it.category.title.str(), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Res.string.back.str())
                        }
                    },
                    actions = {
                        if (tool?.expandable == true) {
                            IconButton(
                                onClick = { chrome.immersive = true },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Filled.OpenInFull, contentDescription = Res.string.fullscreen.str())
                            }
                        }
                        if (tool != null) {
                            val pinned = tool.id in settings.myTools
                            IconButton(
                                onClick = {
                                    if (pinned) settings.unpin(tool.id) else settings.pin(tool.id)
                                    Analytics.log(
                                        if (pinned) AnalyticsEvent.TOOL_UNPIN else AnalyticsEvent.TOOL_PIN,
                                        mapOf(AnalyticsParam.TOOL to tool.id, AnalyticsParam.CATEGORY to tool.category.id),
                                    )
                                    if (!web) scope.launch { snackbar.showSnackbar(if (pinned) removedText else addedText) }
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    when {
                                        web -> if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder
                                        else -> if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
                                    },
                                    contentDescription = when {
                                        web -> if (pinned) Res.string.remove_from_favorites.str() else Res.string.add_to_favorites.str()
                                        else -> if (pinned) Res.string.remove_from_home.str() else Res.string.add_to_home.str()
                                    },
                                )
                            }
                        }
                    },
                    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = side, right = side)),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        if (tool == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = Res.string.nothing_found.str(),
                    description = Res.string.unknown_tool_hint.str(),
                ) {
                    ActionButton(text = Res.string.tools_short.str(), onClick = onBack)
                }
            }
            return@Scaffold
        }
        Box(Modifier.padding(padding).fillMaxSize()) {
            ContentColumn(
                modifier = Modifier.fillMaxSize(),
                maxWidth = if (chrome.immersive) ImmersiveMaxWidth else PageMaxWidth,
                contentPadding = if (chrome.immersive) PaddingValues(0.dp) else PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalSpacing = if (chrome.immersive) 0.dp else 16.dp,
            ) {
                if (!chrome.immersive) ToolDescription(tool.description.str(), tool.id)
                if (tool.availableHere) {
                    CompositionLocalProvider(LocalToolId provides tool.id) { tool.content() }
                } else {
                    EmptyState(
                        icon = Icons.Filled.DoNotDisturbOn,
                        title = Res.string.not_available_on_this_platform.str(),
                        description = Res.string.tool_needs_a_system_api.str(),
                    ) {
                        Text(
                            Res.string.available_on.str(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PlatformBadges(tool.platforms, Modifier.wrapContentWidth())
                        ActionButton(text = Res.string.get_the_app.str(), onClick = { openUrl(AppConfig.REPO_URL + "/releases") })
                    }
                }
            }
            if (chrome.immersive) {
                FilledTonalIconButton(
                    onClick = { chrome.immersive = false },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).pointerHoverIcon(PointerIcon.Hand),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    ),
                ) {
                    Icon(Icons.Filled.CloseFullscreen, contentDescription = Res.string.exit_fullscreen.str())
                }
            }
        }
    }
}

@Composable
private fun ToolDescription(text: String, toolId: String) {
    var expanded by rememberSaveable(toolId) { mutableStateOf(false) }
    var clipped by remember(toolId) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
        )
        if (clipped || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (expanded) Res.string.show_less.str() else Res.string.show_more.str())
            }
        }
    }
}
