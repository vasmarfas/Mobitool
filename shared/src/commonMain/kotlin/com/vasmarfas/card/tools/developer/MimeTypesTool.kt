package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadedFile
import com.vasmarfas.card.ui.components.OpenFileButton
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_ROWS = 60

val mimeTypesTool = Tool(
    id = "mime-types",
    category = ToolCategory.DEVELOPER,
    title = Res.string.mime_types,
    description = Res.string.mime_types_description,
    icon = Icons.Filled.Description,
    keywords = listOf("mime", "media type", "content-type", "extension", "file type", "тип файла", "расширение", "content type"),
) { MimeTypesScreen() }

private const val PROBE_BYTES = 2 * 1024 * 1024

@Composable
private fun MimeTypesScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var probed by remember { mutableStateOf<LoadedFile?>(null) }
    OpenFileButton(label = Res.string.mime_detect_file.str()) { picked ->
        val bytes = if (picked.size() > wholeFileLimitMb * 1024L * 1024) ByteArray(0) else picked.readBytes()
        probed = LoadedFile(picked.name, if (bytes.size > PROBE_BYTES) bytes.copyOf(PROBE_BYTES / 2) + bytes.copyOfRange(bytes.size - PROBE_BYTES / 2, bytes.size) else bytes)
    }
    probed?.let { FileTypeCard(it) { probed = null } }
    ToolInputField(
        value = query,
        onValueChange = { query = it },
        label = Res.string.extension_type_or_description.str(),
        placeholder = "docx · image/ · archive",
    )
    val found = remember(query) { MimeTypes.search(query) }
    if (found.isEmpty()) {
        Text(Res.string.nothing_found.str(), style = MaterialTheme.typography.bodyMedium)
        return
    }
    ResultCard(pluralStringResource(Res.plurals.mime_type_count, found.size, found.size)) {
        found.take(MAX_ROWS).forEach { t ->
            val extensions = t.extensions.filter { it.isNotEmpty() }.joinToString(" ") { ".$it" }
            val description = t.description.str()
            KeyValueRow(if (extensions.isEmpty()) description else "$extensions · $description", t.type)
        }
        if (found.size > MAX_ROWS) {
            Text(stringResource(Res.string.mime_first_rows, MAX_ROWS), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileTypeCard(file: LoadedFile, onClear: () -> Unit) {
    val content = remember(file) { FileSignatures.detect(file.bytes) }
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val byName = MimeTypes.all.firstOrNull { t -> extension.isNotEmpty() && extension in t.extensions }
    ResultCard(file.name) {
        KeyValueRow(
            Res.string.mime_by_content.str(),
            content?.let { kind -> MimeTypes.all.firstOrNull { it.type == kind.mime }?.let { "${kind.mime} · ${it.description.str()}" } ?: kind.mime }
                ?: Res.string.mime_not_recognised.str(),
            mono = false,
        )
        KeyValueRow(
            Res.string.mime_by_extension.str(),
            byName?.let { "${it.type} · ${it.description.str()}" } ?: Res.string.mime_not_recognised.str(),
            mono = false,
        )
        if (content != null && byName != null && content.mime != byName.type && content.extension != extension) {
            Hint(Res.string.mime_mismatch.str())
        }
        TextButton(onClick = onClear) { Text(Res.string.clear.str()) }
    }
}
