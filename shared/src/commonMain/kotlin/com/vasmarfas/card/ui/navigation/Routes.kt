package com.vasmarfas.card.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.resources.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
@SerialName("home")
data object HomeRoute

@Serializable
@SerialName("my")
data object MyToolsRoute

@Serializable
@SerialName("projects")
data object ProjectsRoute

@Serializable
@SerialName("resume")
data object ResumeRoute

@Serializable
@SerialName("tools")
data class ToolsRoute(val category: String? = null)

@Serializable
@SerialName("tool")
data class ToolRoute(val id: String)

@Serializable
@SerialName("settings")
data object SettingsRoute

enum class TopDestination(
    val route: Any,
    private val siteLabel: StringResource,
    private val siteIcon: ImageVector,
    private val siteSelectedIcon: ImageVector,
    val urlFragment: String,
) {
    MY_TOOLS(MyToolsRoute, Res.string.home, Icons.Outlined.Home, Icons.Filled.Home, "my"),
    HOME(HomeRoute, Res.string.home, Icons.Outlined.Home, Icons.Filled.Home, "home"),
    PROJECTS(ProjectsRoute, Res.string.projects, Icons.Outlined.Widgets, Icons.Filled.Widgets, "projects"),
    RESUME(ResumeRoute, Res.string.resume_page, Icons.Outlined.Description, Icons.Filled.Description, "resume"),
    TOOLS(ToolsRoute(), Res.string.tools_short, Icons.Outlined.Build, Icons.Filled.Build, "tools"),
    SETTINGS(SettingsRoute, Res.string.settings, Icons.Outlined.Settings, Icons.Filled.Settings, "settings"),
    ;

    private val asAboutPage get() = this == HOME && currentPlatform != PlatformKind.WEB

    val label: StringResource get() = if (asAboutPage) Res.string.about_me else siteLabel
    val icon: ImageVector get() = if (asAboutPage) Icons.Outlined.Person else siteIcon
    val selectedIcon: ImageVector get() = if (asAboutPage) Icons.Filled.Person else siteSelectedIcon

    // the apps' about page and the site's home are different pages for analytics
    val screenName: String get() = if (asAboutPage) "about" else urlFragment

    companion object {
        // the site keeps the personal pages in their old order. Store builds ship as a toolbox: the person's
        // own tools first, projects and the resume reachable by link but off the bar
        val visible: List<TopDestination>
            get() = if (currentPlatform == PlatformKind.WEB) entries - MY_TOOLS else listOf(MY_TOOLS, TOOLS, HOME, SETTINGS)

        val start: TopDestination get() = if (currentPlatform == PlatformKind.WEB) HOME else MY_TOOLS
    }
}

object UrlRoutes {
    const val TOOL_PREFIX = "tools/"
    private const val CATEGORY_PARAM = "category="

    fun fragmentFor(destination: TopDestination): String = "#${destination.urlFragment}"

    fun fragmentForTool(id: String): String = "#$TOOL_PREFIX$id"

    fun fragmentForCatalog(category: String?): String =
        fragmentFor(TopDestination.TOOLS) + category?.let { "?$CATEGORY_PARAM$it" }.orEmpty()

    fun parse(fragment: String): Any? {
        val f = fragment.trim().trimStart('#')
        val path = f.substringBefore('?').trim('/')
        if (path.startsWith(TOOL_PREFIX)) {
            val id = path.removePrefix(TOOL_PREFIX).substringBefore('/')
            if (id.isNotBlank()) return ToolRoute(id)
        }
        return when (val route = TopDestination.entries.firstOrNull { it.urlFragment == path.substringBefore('/') }?.route) {
            is ToolsRoute -> {
                val category = f.substringAfter('?', "").split('&').firstOrNull { it.startsWith(CATEGORY_PARAM) }
                ToolsRoute(category?.removePrefix(CATEGORY_PARAM)?.ifEmpty { null })
            }
            else -> route
        }
    }
}
