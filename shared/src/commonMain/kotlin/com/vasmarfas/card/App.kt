package com.vasmarfas.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.initAnalytics
import com.vasmarfas.card.core.listenForFindShortcut
import com.vasmarfas.card.core.pullToReload
import com.vasmarfas.card.core.reloadPage
import com.vasmarfas.card.core.setWindowTitle
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.AppSettings
import com.vasmarfas.card.data.GithubStars
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.data.OnboardingStatus
import com.vasmarfas.card.data.ProfileRepository
import com.vasmarfas.card.data.ResumeRepository
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.ToolRegistry
import com.vasmarfas.card.ui.components.ChromeState
import com.vasmarfas.card.ui.components.FindBar
import com.vasmarfas.card.ui.components.FindState
import com.vasmarfas.card.ui.components.LayoutSize
import com.vasmarfas.card.ui.components.LocalChrome
import com.vasmarfas.card.ui.components.LocalFind
import com.vasmarfas.card.ui.components.LocalLayoutSize
import com.vasmarfas.card.ui.components.LocalOpenTool
import com.vasmarfas.card.ui.components.layoutSizeFor
import com.vasmarfas.card.ui.navigation.HomeRoute
import com.vasmarfas.card.ui.navigation.MyToolsRoute
import com.vasmarfas.card.ui.navigation.ProjectsRoute
import com.vasmarfas.card.ui.navigation.ResumeRoute
import com.vasmarfas.card.ui.navigation.SettingsRoute
import com.vasmarfas.card.ui.navigation.ToolRoute
import com.vasmarfas.card.ui.navigation.ToolsRoute
import com.vasmarfas.card.ui.navigation.TopDestination
import com.vasmarfas.card.ui.navigation.UrlRoutes
import com.vasmarfas.card.ui.screens.HomeScreen
import com.vasmarfas.card.ui.screens.MyToolsScreen
import com.vasmarfas.card.ui.screens.OnboardingFirstRun
import com.vasmarfas.card.ui.screens.OnboardingScreen
import com.vasmarfas.card.ui.screens.ProjectsScreen
import com.vasmarfas.card.ui.screens.ProjectsTab
import com.vasmarfas.card.ui.screens.ResumeScreen
import com.vasmarfas.card.ui.screens.SettingsScreen
import com.vasmarfas.card.ui.screens.ToolScreen
import com.vasmarfas.card.ui.screens.ToolsScreen
import com.vasmarfas.card.ui.theme.MobitoolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

@Composable
fun App(
    link: String? = null,
    onLinkHandled: () -> Unit = {},
    onNavHostReady: suspend (NavController) -> Unit = {},
    find: FindState = remember { FindState() },
) {
    val settings = remember { AppSettings() }
    val chrome = remember { ChromeState() }
    LaunchedEffect(Unit) {
        initAnalytics()
        settings.syncAnalytics()
        Analytics.log(AnalyticsEvent.APP_OPEN)
        withContext(Dispatchers.Default) { ToolRegistry.all.size }
    }
    LaunchedEffect(Unit) { ProfileRepository.load() }
    LaunchedEffect(Unit) { ResumeRepository.load() }
    val profileState by ProfileRepository.state.collectAsState()
    LaunchedEffect(profileState.value) {
        profileState.value?.projects?.let { GithubStars.refresh(it) }
    }
    val linkRoute = link?.let { UrlRoutes.parse(it.substringAfter('#', "")) }
    // A link to a tool opens the tool. The status stays NONE, so the questions are asked on the next cold start.
    var onboarding by rememberSaveable {
        val firstRun = currentPlatform != PlatformKind.WEB && settings.onboarding == OnboardingStatus.NONE
        mutableStateOf(if (firstRun && linkRoute !is ToolRoute) OnboardingFirstRun else null)
    }
    LaunchedEffect(linkRoute) { if (linkRoute is ToolRoute) onboarding = null }
    CompositionLocalProvider(
        LocalSettings provides settings,
        LocalLang provides settings.lang,
        LocalChrome provides chrome,
        LocalFind provides find,
    ) {
        val navController = rememberNavController()
        key(settings.lang) {
            MobitoolTheme(settings) {
                val entry = onboarding
                if (entry != null) {
                    OnboardingScreen(
                        entry = entry,
                        onFinish = {
                            // Before the first run is over the nav host has never been composed and has no graph yet.
                            if (entry != OnboardingFirstRun) navController.navigateTop(TopDestination.start)
                            onboarding = null
                        },
                    )
                } else {
                    AppShell(navController, linkRoute, onLinkHandled, onNavHostReady, onRunOnboarding = { from -> onboarding = from })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppShell(
    navController: NavHostController,
    linkRoute: Any?,
    onLinkHandled: () -> Unit,
    onNavHostReady: suspend (NavController) -> Unit,
    onRunOnboarding: (String) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val topDestination = TopDestination.entries.firstOrNull { destination?.hasRoute(it.route::class) == true }
    val toolId = backStackEntry?.takeIf { destination?.hasRoute(ToolRoute::class) == true }?.toRoute<ToolRoute>()?.id
    val lang = LocalLang.current
    val chrome = LocalChrome.current
    val find = LocalFind.current

    LaunchedEffect(toolId) { chrome.immersive = false }
    LaunchedEffect(find) { listenForFindShortcut { find.show() } }
    LaunchedEffect(topDestination, toolId) { find.close() }

    // The site's page view carries the window title, so both come from one effect.
    var viewed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(topDestination, toolId, lang) {
        val page = when {
            toolId != null -> ToolRegistry.byId(toolId)?.title?.let { getString(it) } ?: getString(Res.string.tools)
            topDestination == TopDestination.HOME -> null
            topDestination != null -> getString(topDestination.label)
            else -> null
        }
        val title = when {
            page != null -> "$page · vasmarfas"
            currentPlatform == PlatformKind.WEB -> getString(Res.string.site_title)
            else -> "vasmarfas"
        }
        setWindowTitle(title)
        val screen = toolId?.let { "tool_$it" } ?: topDestination?.screenName ?: return@LaunchedEffect
        if (screen == viewed) return@LaunchedEffect
        viewed = screen
        val path = toolId?.let(UrlRoutes::fragmentForTool) ?: topDestination?.let(UrlRoutes::fragmentFor).orEmpty()
        Analytics.screen(screen, if (toolId != null) "tool" else "tab", path, title)
    }

    val navigateTop = remember(navController) {
        { tab: TopDestination ->
            chrome.immersive = false
            navController.navigateTop(tab)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onPreviewKeyEvent(find::shortcut)) {
        val layout = layoutSizeFor(maxWidth)
        CompositionLocalProvider(LocalLayoutSize provides layout) {
            val compact = layout == LayoutSize.COMPACT
            Row(Modifier.fillMaxSize()) {
                if (!compact && !chrome.immersive) {
                    // The expanded rail takes about 200 dp, so it unfolds only in wide windows.
                    val expandedRail = layout == LayoutSize.EXPANDED
                    WideNavigationRail(
                        state = rememberWideNavigationRailState(
                            if (expandedRail) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
                        ),
                    ) {
                        TopDestination.visible.forEach { tab ->
                            WideNavigationRailItem(
                                selected = tab == topDestination,
                                onClick = { navigateTop(tab) },
                                icon = { Icon(if (tab == topDestination) tab.selectedIcon else tab.icon, contentDescription = null) },
                                label = { Text(tab.label.str(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                railExpanded = expandedRail,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentWindowInsets = if (chrome.immersive) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
                    bottomBar = {
                        if (compact) {
                            AnimatedVisibility(
                                visible = !chrome.immersive && topDestination != null,
                                enter = fadeIn(tween(NavFadeIn, delayMillis = NavFadeOut)) +
                                    slideInVertically(tween(NavFadeIn, delayMillis = NavFadeOut)) { it },
                                exit = fadeOut(tween(NavFadeOut + NavFadeIn)) + slideOutVertically(tween(NavFadeOut + NavFadeIn)) { it },
                            ) {
                                ShortNavigationBar {
                                TopDestination.visible.forEach { tab ->
                                    ShortNavigationBarItem(
                                        selected = tab == topDestination,
                                        onClick = { navigateTop(tab) },
                                        icon = { Icon(if (tab == topDestination) tab.selectedIcon else tab.icon, contentDescription = null) },
                                        // At a 2.0 font scale a wrapped label spills out of the bar.
                                        label = { Text(tab.label.str(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                                }
                            }
                        }
                    },
                ) { padding ->
                    var reloading by remember { mutableStateOf(false) }
                    PullToRefreshBox(
                        isRefreshing = reloading,
                        onRefresh = {
                            reloading = true
                            reloadPage()
                        },
                        modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
//                        enabled = pullToReload,
                        enabled = false,
                    ) {
                        AppNavHost(navController, navigateTop, linkRoute, onLinkHandled, onNavHostReady, onRunOnboarding, Modifier.fillMaxSize())
                        if (find.open) FindBar(find, Modifier.align(Alignment.TopEnd).padding(12.dp))
                    }
                }
            }
        }
    }
}

// Material fade-through: the outgoing screen is gone in 90 ms, the incoming one settles from 92 % over the next 210.
private const val NavFadeOut = 90
private const val NavFadeIn = 210

private fun fadeThroughIn() = fadeIn(tween(NavFadeIn, delayMillis = NavFadeOut)) +
    scaleIn(initialScale = 0.92f, animationSpec = tween(NavFadeIn, delayMillis = NavFadeOut))

// The start tab is rebuilt from scratch, every other tab keeps its own back stack between visits.
private fun NavController.navigateTop(tab: TopDestination) {
    val start = tab == TopDestination.start
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) {
            inclusive = start
            saveState = !start
        }
        launchSingleTop = true
        restoreState = !start
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    onNavigateTop: (TopDestination) -> Unit,
    linkRoute: Any?,
    onLinkHandled: () -> Unit,
    onNavHostReady: suspend (NavController) -> Unit,
    onRunOnboarding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openTool: (String) -> Unit = { id -> navController.navigate(ToolRoute(id)) }
    val switchTool: (String) -> Unit = { id ->
        val behind = navController.previousBackStackEntry?.takeIf { it.destination.hasRoute(ToolRoute::class) }?.toRoute<ToolRoute>()
        if (behind?.id == id) navController.popBackStack() else openTool(id)
    }
    var projectsTab by rememberSaveable { mutableStateOf(ProjectsTab.PROJECTS) }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        CompositionLocalProvider(LocalOpenTool provides switchTool) {
            NavHost(
                navController = navController,
                startDestination = TopDestination.start.route,
                enterTransition = { fadeThroughIn() },
                exitTransition = { fadeOut(tween(NavFadeOut)) },
                popEnterTransition = { fadeThroughIn() },
                popExitTransition = { fadeOut(tween(NavFadeOut)) },
            ) {
                composable<MyToolsRoute> {
                    MyToolsScreen(
                        onOpenTool = openTool,
                        onOpenCatalog = { onNavigateTop(TopDestination.TOOLS) },
                        onRunOnboarding = onRunOnboarding,
                    )
                }
                composable<HomeRoute> {
                    HomeScreen(
                        onOpenProjects = { tab ->
                            projectsTab = tab
                            onNavigateTop(TopDestination.PROJECTS)
                        },
                        onOpenResume = { onNavigateTop(TopDestination.RESUME) },
                        onOpenSettings = { onNavigateTop(TopDestination.SETTINGS) },
                    )
                }
                composable<ProjectsRoute> { ProjectsScreen(projectsTab) { projectsTab = it } }
                composable<ResumeRoute> { ResumeScreen() }
                composable<ToolsRoute> { ToolsScreen(onOpenTool = openTool) }
                // A tool rises out of the card that opened it and sinks back on the way out.
                composable<ToolRoute>(
                    enterTransition = {
                        fadeIn(tween(NavFadeIn, delayMillis = NavFadeOut)) +
                            slideInVertically(tween(NavFadeIn, delayMillis = NavFadeOut)) { it / 24 }
                    },
                    popExitTransition = { fadeOut(tween(NavFadeOut)) + slideOutVertically(tween(NavFadeOut)) { it / 24 } },
                ) { entry ->
                    val route = entry.toRoute<ToolRoute>()
                    ToolScreen(
                        toolId = route.id,
                        onBack = { if (!navController.popBackStack()) onNavigateTop(TopDestination.TOOLS) },
                    )
                }
                composable<SettingsRoute> { SettingsScreen(onRunOnboarding = { onRunOnboarding("settings") }) }
            }
        }
        LaunchedEffect(navController) { onNavHostReady(navController) }
        LaunchedEffect(navController, linkRoute) {
            if (linkRoute == null) return@LaunchedEffect
            if (linkRoute is ToolRoute) {
                val shown = navController.currentBackStackEntry?.takeIf { it.destination.hasRoute(ToolRoute::class) }?.toRoute<ToolRoute>()
                if (shown != linkRoute) {
                    ToolRegistry.byId(linkRoute.id)?.let { tool ->
                        Analytics.log(
                            AnalyticsEvent.TOOL_OPEN,
                            mapOf(AnalyticsParam.TOOL to tool.id, AnalyticsParam.CATEGORY to tool.category.id, AnalyticsParam.SOURCE to "link"),
                        )
                    }
                    openTool(linkRoute.id)
                }
            } else {
                TopDestination.entries.firstOrNull { it.route == linkRoute }?.let(onNavigateTop)
            }
            onLinkHandled()
        }
    }
}
