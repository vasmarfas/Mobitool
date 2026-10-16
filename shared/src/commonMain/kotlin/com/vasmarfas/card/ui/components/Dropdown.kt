package com.vasmarfas.card.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.keyboardDialogProperties
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String,
    text: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    menuText: @Composable (T) -> String = text,
    searchable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = options.map { menuText(it) }
    ExposedDropdownMenuBox(
        expanded = expanded && !searchable,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = text(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        if (!searchable) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(labels[index]) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    if (searchable && expanded) {
        SearchDialog(
            title = label,
            labels = labels,
            selected = options.indexOf(selected),
            onSelect = {
                onSelect(options[it])
                expanded = false
            },
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun SearchDialog(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = labels.indices.filter { query.isBlank() || labels[it].contains(query.trim(), ignoreCase = true) }
        .sortedBy { if (labels[it].startsWith(query.trim(), ignoreCase = true)) 0 else 1 }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.safeDrawingPadding(),
        properties = keyboardDialogProperties(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(Res.string.search.str()) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { shown.firstOrNull()?.let(onSelect) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (shown.isEmpty()) {
                    Text(
                        Res.string.nothing_found.str(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(state = list, modifier = Modifier.heightIn(max = 480.dp)) {
                    items(shown, key = { it }) { index ->
                        DropdownMenuItem(
                            text = { Text(labels[index]) },
                            onClick = { onSelect(index) },
                            trailingIcon = if (index == selected) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
            LaunchedEffect(query) { if (query.isNotEmpty()) list.scrollToItem(0) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Res.string.cancel.str()) } },
    )
}

@Composable
fun MonoTable(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()), softWrap = false)
        }
    }
}
