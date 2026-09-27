package com.vasmarfas.card

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.toRoute
import com.vasmarfas.card.core.hideWebSplash
import com.vasmarfas.card.ui.navigation.ToolRoute
import com.vasmarfas.card.ui.navigation.ToolsRoute
import com.vasmarfas.card.ui.navigation.TopDestination
import com.vasmarfas.card.ui.navigation.UrlRoutes
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    var current: NavController? = null
    window.addEventListener("hashchange", { current?.let { navigateToFragment(it, window.location.hash) } })
    ComposeViewport("app") {
        App(onNavHostReady = { navController ->
            current = navController
            hideWebSplash()
            navigateToFragment(navController, window.location.hash)
            navController.bindToBrowserNavigation { entry ->
                val destination = entry.destination
                when {
                    destination.hasRoute(ToolRoute::class) -> UrlRoutes.fragmentForTool(entry.toRoute<ToolRoute>().id)
                    destination.hasRoute(ToolsRoute::class) -> UrlRoutes.fragmentForCatalog(entry.toRoute<ToolsRoute>().category)
                    else -> TopDestination.entries.firstOrNull { destination.hasRoute(it.route::class) }
                        ?.let { UrlRoutes.fragmentFor(it) } ?: ""
                }
            }
        })
    }
}

private fun navigateToFragment(navController: NavController, fragment: String) {
    val route = UrlRoutes.parse(fragment) ?: return
    val current = navController.currentBackStackEntry
    val alreadyThere = current?.destination?.hasRoute(route::class) == true && current.toRoute<Any>(route::class) == route
    if (alreadyThere) return
    when (route) {
        is ToolRoute -> {
            if (current?.destination?.hasRoute(TopDestination.TOOLS.route::class) != true) navController.navigate(TopDestination.TOOLS.route)
            navController.navigate(route)
        }
        else -> navController.navigate(route) { launchSingleTop = true }
    }
}
