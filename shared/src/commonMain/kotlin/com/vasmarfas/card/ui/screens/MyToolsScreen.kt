package com.vasmarfas.card.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.ToolRegistry
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ContentMaxWidth
import com.vasmarfas.card.ui.components.EmptyState
import com.vasmarfas.card.ui.components.LayoutSize
import com.vasmarfas.card.ui.components.LocalLayoutSize
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyToolsScreen(onOpenTool: (id: String, source: String) -> Unit, onOpenCatalog: () -> Unit, onRunOnboarding: (entry: String) -> Unit) {
    val settings = LocalSettings.current
    val tools = settings.myTools.mapNotNull { ToolRegistry.byId(it) }
    val sections = ToolCategory.entries.mapNotNull { category ->
        tools.filter { it.category == category }.map { it to it.title.str() }.sortedBy { it.second }.map { it.first }
            .takeIf { it.isNotEmpty() }?.let { category to it }
    }
    var removing by remember { mutableStateOf<Tool?>(null) }
    val compact = LocalLayoutSize.current == LayoutSize.COMPACT
    val open = { id: String -> onOpenTool(id, "home") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(250.dp),
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Res.string.home.str(), style = MaterialTheme.typography.headlineSmall)
                    if (tools.isNotEmpty()) {
                        Text(
                            pluralStringResource(Res.plurals.my_tools_count, tools.size, tools.size, ToolRegistry.all.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (tools.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.Home,
                        title = Res.string.my_tools_empty.str(),
                        description = Res.string.my_tools_empty_hint.str(),
                    ) {
                        ActionButton(text = Res.string.pick_tools.str(), onClick = { onRunOnboarding("home_empty") }, icon = Icons.Filled.AutoAwesome)
                        TextButton(onClick = onOpenCatalog) { Text(Res.string.open_catalog.str()) }
                    }
                }
            }
            sections.forEach { (category, list) ->
                val collapsed = category.id in settings.collapsedSections
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-" + category.id) {
                    SectionHeader(category, list.size, collapsed) {
                        settings.toggleSection(category.id)
                        Analytics.log(
                            AnalyticsEvent.HOME_SECTION_TOGGLE,
                            mapOf(AnalyticsParam.CATEGORY to category.id, AnalyticsParam.STATE to if (collapsed) "expanded" else "collapsed"),
                        )
                    }
                }
                if (!collapsed) {
                    items(list, key = { it.id }) { tool ->
                        ToolCard(tool, open) {
                            IconButton(onClick = { removing = tool }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                                Icon(Icons.Outlined.Delete, contentDescription = Res.string.remove_from_home.str())
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                if (tools.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                Analytics.log(AnalyticsEvent.HOME_ADD_TOOLS)
                                onOpenCatalog()
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(Res.string.add_tools.str())
                        }
                        TextButton(onClick = { onRunOnboarding("home") }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(Res.string.pick_again.str())
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    removing?.let { tool ->
        val params = mapOf(AnalyticsParam.TOOL to tool.id, AnalyticsParam.CATEGORY to tool.category.id)
        AlertDialog(
            onDismissRequest = {
                removing = null
                Analytics.log(AnalyticsEvent.HOME_REMOVE_CANCEL, params)
            },
            title = { Text(Res.string.remove_from_home_title.str()) },
            text = { Text(stringResource(Res.string.remove_from_home_body, tool.title.str())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.unpin(tool.id)
                        removing = null
                        Analytics.log(AnalyticsEvent.HOME_REMOVE, params)
                    },
                ) { Text(Res.string.remove_from_home_confirm.str()) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        removing = null
                        Analytics.log(AnalyticsEvent.HOME_REMOVE_CANCEL, params)
                    },
                ) { Text(Res.string.cancel.str()) }
            },
        )
    }
}

@Composable
private fun SectionHeader(category: ToolCategory, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    val state = if (collapsed) Res.string.section_collapsed.str() else Res.string.section_expanded.str()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(category.title.str(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null)
    }
}
