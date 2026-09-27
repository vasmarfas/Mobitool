package com.vasmarfas.card.ui.screens

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentPlatform
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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

private sealed interface Shelf {
    val key: String

    data object Favorites : Shelf {
        override val key = "favorites"
    }

    data object Popular : Shelf {
        override val key = "popular"
    }

    data class Of(val category: ToolCategory) : Shelf {
        override val key = category.id
    }
}

private val shelves: List<Shelf> by lazy {
    val gathered = if (currentPlatform == PlatformKind.WEB) listOf(Shelf.Favorites, Shelf.Popular) else listOf(Shelf.Popular)
    gathered + ToolCategory.entries.filter { ToolRegistry.byCategory(it).isNotEmpty() }.map { Shelf.Of(it) }
}

private val Shelf.icon: ImageVector
    get() = when (this) {
        Shelf.Favorites -> Icons.Filled.Star
        Shelf.Popular -> Icons.Filled.Whatshot
        is Shelf.Of -> category.icon
    }

@Composable
private fun Shelf.title(): String = when (this) {
    Shelf.Favorites -> Res.string.favorites.str()
    Shelf.Popular -> Res.string.popular.str()
    is Shelf.Of -> category.title.str()
}

private fun Shelf.tools(favorites: List<String>): List<Tool> = when (this) {
    Shelf.Favorites -> favorites.mapNotNull { ToolRegistry.byId(it) }
    Shelf.Popular -> ToolRegistry.popular.filter { it.availableHere }
    is Shelf.Of -> ToolRegistry.byCategory(category)
}

@Composable
fun ToolsScreen(category: String?, onCategoryChange: (String?) -> Unit, onOpenTool: (id: String, source: String) -> Unit) {
    val settings = LocalSettings.current
    var query by rememberSaveable { mutableStateOf("") }
    val shelf = shelves.firstOrNull { it.key == category }
    val browsing = query.isBlank() && shelf == null
    val filtered = remember(query, shelf, settings.myTools) {
        val q = query.trim()
        (if (q.isNotEmpty()) ToolRegistry.all else shelf?.tools(settings.myTools) ?: ToolRegistry.all)
            .filter { it.matches(query) }
            .sortedBy { if (q.isNotEmpty() && it.title.matches(q)) 0 else 1 }
    }
    val recent = settings.recent.mapNotNull { ToolRegistry.byId(it) }
    var categoriesExpanded by rememberSaveable { mutableStateOf(false) }
    val compact = LocalLayoutSize.current == LayoutSize.COMPACT
    // a new shelf or query starts from the top, the restored values on the way back from a tool are not a new choice
    val grid = rememberLazyGridState()
    val shownCategory by rememberUpdatedState(category)
    LaunchedEffect(grid) {
        snapshotFlow { shownCategory to query }.drop(1).collect { grid.scrollToItem(0) }
    }
    val results by rememberUpdatedState(filtered.size)
    LaunchedEffect(Unit) {
        snapshotFlow { query }.drop(1).collectLatest { typed ->
            if (typed.isBlank()) return@collectLatest
            delay(1500.milliseconds)
            Analytics.search(typed, results)
        }
    }
    val source = when {
        query.isNotBlank() -> "search"
        shelf == Shelf.Popular -> "popular"
        shelf == Shelf.Favorites -> "favorites"
        shelf is Shelf.Of -> "category"
        else -> "catalog"
    }
    val open = { id: String -> onOpenTool(id, source) }
    val select = { key: String? ->
        onCategoryChange(key)
        key?.let { Analytics.log(AnalyticsEvent.CATALOG_FILTER, mapOf(AnalyticsParam.CATEGORY to it)) }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shelf != null || query.isNotEmpty(),
        onBackCompleted = {
            query = ""
            select(null)
        },
    )

    Column(Modifier.fillMaxSize()) {
        CatalogHeader(
            query = query,
            onQueryChange = { query = it },
            selected = shelf,
            onSelect = { select(it?.key) },
            expanded = categoriesExpanded,
            onToggleExpanded = { categoriesExpanded = !categoriesExpanded },
        )
        LazyVerticalGrid(
            state = grid,
            // Category tiles pair up even on a phone, tool cards keep the width a name and two
            // lines of description need.
            columns = if (browsing && compact) GridCells.Fixed(2) else GridCells.Adaptive(if (browsing) 220.dp else 250.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (browsing && recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                    ChipRow(Res.string.recent.str(), recent) { onOpenTool(it, "recent") }
                }
            }
            if (browsing) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "categories") { GroupHeader(Res.string.categories.str()) }
                items(shelves, key = { "tile-" + it.key }) { item ->
                    ShelfTile(item, item.tools(settings.myTools), onClick = { select(item.key) })
                }
            } else {
                val groups = if (query.isNotBlank() || shelf == null || shelf is Shelf.Of) {
                    filtered.groupBy { it.category }.map { (cat, tools) -> Shelf.Of(cat) to tools }.sortedBy { (group, _) -> if (group == shelf) 0 else 1 }
                } else {
                    listOf(shelf to filtered).filter { it.second.isNotEmpty() }
                }
                if (groups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (shelf == Shelf.Favorites && query.isBlank()) {
                            EmptyState(
                                icon = Icons.Filled.StarBorder,
                                title = Res.string.favorites_empty.str(),
                                description = Res.string.favorites_empty_hint.str(),
                            ) {
                                ActionButton(text = Res.string.popular.str(), onClick = { select(Shelf.Popular.key) })
                            }
                        } else {
                            EmptyState(
                                icon = Icons.Filled.SearchOff,
                                title = Res.string.nothing_found.str(),
                                description = Res.string.nothing_found_hint.str(),
                            ) {
                                ActionButton(
                                    text = Res.string.reset_search.str(),
                                    onClick = {
                                        query = ""
                                        select(null)
                                    },
                                )
                            }
                        }
                    }
                }
                groups.forEach { (group, tools) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header-" + group.key) {
                        GroupHeader("${group.title()} · ${tools.size}", icon = group.icon)
                    }
                    items(tools, key = { it.id }) { ToolCard(it, open) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: Shelf?,
    onSelect: (Shelf?) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val categories = remember { listOf<Shelf?>(null) + shelves }
    val peek = with(LocalDensity.current) { 48.dp.roundToPx() }
    val chips = rememberLazyListState(categories.indexOf(selected))
    val label = selected?.title()
    LaunchedEffect(selected, label) {
        val index = categories.indexOf(selected)
        // the web gets the labels a frame late, a position counted from the narrower chips would drift once they arrive
        if (index >= 0 && label != "") chips.animateScrollToItem(index, -peek)
    }
    var mouse by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                Res.string.tools_short.str(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Row(verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically) {
                if (expanded) {
                    FlowRow(
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { CategoryChip(it, selected, onSelect) }
                    }
                } else {
                    LazyRow(
                        state = chips,
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) mouse = awaitPointerEvent(PointerEventPass.Initial).changes.first().type == PointerType.Mouse
                                }
                            }
                            .scrollable(chips, Orientation.Vertical, enabled = mouse, reverseDirection = true),
                        contentPadding = PaddingValues(start = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(categories, key = { it?.key ?: "all" }) { CategoryChip(it, selected, onSelect) }
                    }
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.padding(end = 4.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) {
                            Res.string.show_fewer_categories.str()
                        } else {
                            Res.string.show_all_categories.str()
                        },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                singleLine = true,
                placeholder = { Text(Res.string.search_tools.str()) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = Res.string.clear.str())
                        }
                    }
                },
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun CategoryChip(option: Shelf?, selected: Shelf?, onSelect: (Shelf?) -> Unit) {
    FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(option?.title() ?: Res.string.all_tools.str(), maxLines = 1) },
        leadingIcon = {
            Icon(option?.icon ?: Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
private fun GroupHeader(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ChipRow(title: String, tools: List<Tool>, onOpenTool: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(tools.firstOrNull()?.id) { state.scrollToItem(0) }
    Column {
        GroupHeader(title)
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tools, key = { it.id }) { tool ->
                AssistChip(
                    onClick = { onOpenTool(tool.id) },
                    label = { Text(tool.title.str()) },
                    leadingIcon = { Icon(tool.icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

@Composable
private fun ShelfTile(shelf: Shelf, tools: List<Tool>, onClick: () -> Unit) {
    val typography = MaterialTheme.typography
    val lines = typography.titleMedium.lineHeight.value * 2 + typography.bodySmall.lineHeight.value * 2
    val textHeight = with(LocalDensity.current) { lines.sp.toDp() }
    val preview = if (tools.isEmpty() && shelf == Shelf.Favorites) {
        Res.string.favorites_tile_hint.str()
    } else {
        tools.take(6).map { it.title.str() }.joinToString(" · ") + if (tools.size > 6) "…" else ""
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            shelf.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (tools.isNotEmpty()) {
                    Text(tools.size.toString(), style = typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var titleLines by remember { mutableStateOf(1) }
            Column(Modifier.height(textHeight + 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    shelf.title(),
                    style = typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLines = it.lineCount },
                )
                Text(
                    preview,
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4 - titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ToolCard(tool: Tool, onOpenTool: (String) -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val available = tool.availableHere
    val typography = MaterialTheme.typography
    val lines = (typography.titleMedium.lineHeight.value + typography.bodySmall.lineHeight.value) * 2 +
        typography.labelSmall.lineHeight.value
    val textHeight = with(LocalDensity.current) { lines.sp.toDp() }
    val uniform = LocalLayoutSize.current != LayoutSize.COMPACT
    val platforms = if (tool.platforms.size < PlatformKind.all.size) {
        PlatformKind.entries.filter { it in tool.platforms }.map { it.title.str() }.joinToString(" · ")
    } else {
        null
    }
    Card(
        onClick = { onOpenTool(tool.id) },
        modifier = modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).alpha(if (available) 1f else 0.6f),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        tool.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).then(if (uniform) Modifier.height(textHeight + 4.dp) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tool.title.str(),
                    style = typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tool.description.str(),
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (platforms != null) {
                    Text(
                        platforms,
                        style = typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
