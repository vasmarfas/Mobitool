package com.vasmarfas.card.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.pickFiles
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.Link
import com.vasmarfas.card.resources.*
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

val ContentMaxWidth = 1080.dp

val PageMaxWidth = 860.dp

val ImmersiveMaxWidth = 4000.dp

@Composable
fun ContentColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    verticalSpacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    if (scrollable) {
        var reported by rememberSaveable { mutableStateOf(0) }
        LaunchedEffect(scroll) {
            snapshotFlow { if (scroll.maxValue in 1..<Int.MAX_VALUE) scroll.value * 100 / scroll.maxValue else 0 }
                .collect { percent ->
                    Analytics.depthsReached(percent, reported).forEach { depth ->
                        reported = depth
                        Analytics.scrollDepth(depth)
                    }
                }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun SelectableText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    SelectionContainer(modifier) {
        Text(text, style = style, color = if (style.color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color)
    }
}

@Composable
fun rememberCopy(): (String) -> Unit {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val tool = LocalToolId.current
    return remember(clipboard, tool) {
        { text: String ->
            clipboard.setText(AnnotatedString(text))
            tool?.let { Analytics.log(AnalyticsEvent.RESULT_COPY, mapOf(AnalyticsParam.TOOL to it)) }
        }
    }
}

@Composable
fun CopyIconButton(text: String, modifier: Modifier = Modifier) {
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    IconButton(
        onClick = {
            copy(text)
            copied = true
        },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = Res.string.copy.str(),
        )
    }
}

@Composable
fun ToolInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    monospace: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        textStyle = if (monospace) textStyle.copy(fontFamily = monoFamily()) else textStyle,
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter { it.isDigit() }.take(4)
                text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
            },
            label = { Text(label, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            isError = text.toIntOrNull()?.let { it !in range } ?: true,
            modifier = Modifier.width(88.dp),
        )
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

class LoadedFile(val name: String, val bytes: ByteArray)

@Composable
fun LoadedFileCard(file: LoadedFile, onClear: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(formatBytes(file.bytes.size.toLong(), binary = false))
            }
            TextButton(onClick = onClear) { Text(Res.string.clear.str()) }
        }
    }
}

@Composable
fun OpenFileButton(extensions: Set<String> = emptySet(), label: String = Res.string.open_file.str(), onPicked: suspend (PlatformFile) -> Unit) {
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = { scope.launch { runCatching { pickFiles(extensions) }.getOrNull()?.firstOrNull()?.let { onPicked(it) } } },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
    )
}

@Composable
fun ResultCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun AnswerCard(value: String, caption: String, modifier: Modifier = Modifier, copyValue: String = value) =
    AnswerCard(AnnotatedString(value), caption, modifier, copyValue)

@Composable
fun AnswerCard(value: AnnotatedString, caption: String, modifier: Modifier = Modifier, copyValue: String = value.text) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)) {
        Row(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        value,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = MaterialTheme.typography.displaySmall.fontSize),
                    )
                }
                Text(caption, style = MaterialTheme.typography.bodyMedium)
            }
            CopyIconButton(copyValue)
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = true, copyable: Boolean = true, copyValue: String = value) =
    KeyValueRow(label, AnnotatedString(value), mono, copyable, copyValue)

@Composable
fun KeyValueRow(label: String, value: AnnotatedString, mono: Boolean = true, copyable: Boolean = true, copyValue: String = value.text) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    value,
                    style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = monoFamily()) else MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (copyable && value.isNotEmpty()) {
            CopyIconButton(copyValue)
        }
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = monoFamily()))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        content()
    }
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    val tool = LocalToolId.current
    if (tool != null) LaunchedEffect(text) { Analytics.log(AnalyticsEvent.TOOL_ERROR, mapOf(AnalyticsParam.TOOL to tool)) }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingRow(text: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator(modifier = Modifier.size(32.dp))
        Text(text ?: Res.string.loading.str(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val labelSize = TextAutoSize.StepBased(
        minFontSize = 11.sp,
        maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
    )
    // a label that wraps makes its segment taller, the others stretch to it so the outline stays one piece
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.fillMaxHeight(),
                // the filled container marks the selection, a check mark would take 24 dp from the label
                icon = {},
                label = {
                    val text = label(option)
                    Text(text, autoSize = labelSize, maxLines = if (' ' in text) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                leadingIcon = icon?.let { { Icon(it(option), contentDescription = null, modifier = Modifier.size(18.dp)) } },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag -> OutlineLabel(tag) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val tool = LocalToolId.current
    Button(
        onClick = {
            tool?.let { Analytics.log(AnalyticsEvent.TOOL_ACTION, mapOf(AnalyticsParam.TOOL to it)) }
            onClick()
        },
        enabled = enabled,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun linkIcon(type: String): ImageVector = when (type) {
    "telegram" -> Icons.AutoMirrored.Filled.Send
    "github" -> Icons.Filled.Code
    "habr" -> Icons.AutoMirrored.Filled.Article
    "email" -> Icons.Filled.Email
    "googleplay" -> Icons.Filled.Shop
    "appstore" -> Icons.Filled.PhoneIphone
    "rustore", "msstore" -> Icons.Filled.Storefront
    "support" -> Icons.Filled.Favorite
    "channel" -> Icons.Filled.Campaign
    "phone" -> Icons.Filled.Call
    "resume" -> Icons.Filled.Description
    "website" -> Icons.Filled.Language
    else -> Icons.AutoMirrored.Filled.OpenInNew
}

@Composable
fun linkDefaultLabel(type: String): String = when (type) {
    "telegram" -> Res.string.link_telegram.str()
    "github" -> Res.string.link_github.str()
    "habr" -> Res.string.link_habr.str()
    "email" -> Res.string.link_email.str()
    "googleplay" -> Res.string.link_googleplay.str()
    "appstore" -> Res.string.link_appstore.str()
    "rustore" -> Res.string.link_rustore.str()
    "msstore" -> Res.string.link_msstore.str()
    "support" -> Res.string.link_support.str()
    "website" -> Res.string.link_website.str()
    "channel" -> Res.string.link_channel.str()
    else -> type
}

// source is where on the page the link sits, for analytics: resources, project and so on
@Composable
fun LinkChip(link: Link, modifier: Modifier = Modifier, source: String? = null) {
    if (link.type == "email") {
        EmailChip(link.url.removePrefix("mailto:"), modifier, source)
        return
    }
    AssistChip(
        onClick = {
            Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams(link.type, source))
            openUrl(link.url)
        },
        label = { Text(link.label?.str() ?: linkDefaultLabel(link.type)) },
        leadingIcon = {
            Icon(linkIcon(link.type), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

// the address is copied and is the label itself: plenty of visitors have no mail client set up, and
// the canvas gives them nothing to right-click
@Composable
private fun EmailChip(address: String, modifier: Modifier = Modifier, source: String? = null) {
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    AssistChip(
        onClick = {
            copy(address)
            copied = true
            Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams("email", source))
        },
        label = { Text(if (copied) Res.string.copied.str() else address) },
        leadingIcon = {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.Email,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkChips(links: List<Link>, modifier: Modifier = Modifier, source: String? = null) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        links.filter { it.active }.forEach { LinkChip(it, source = source) }
    }
}

fun linkParams(type: String, source: String?): Map<String, String> =
    source?.let { mapOf(AnalyticsParam.LINK to type, AnalyticsParam.SOURCE to it) } ?: mapOf(AnalyticsParam.LINK to type)

@Composable
fun OutlineLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformBadges(platforms: Set<PlatformKind>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlatformKind.entries.filter { it in platforms }.forEach { platform ->
            OutlineLabel(platform.title.str())
        }
    }
}

@Composable
fun ToolSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun SoftDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))
