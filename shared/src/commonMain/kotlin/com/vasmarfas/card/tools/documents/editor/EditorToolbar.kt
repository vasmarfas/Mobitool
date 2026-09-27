package com.vasmarfas.card.tools.documents.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

private val inkColors = listOf(0xFF000000, 0xFF1565C0, 0xFFD32F2F, 0xFF2E7D32, 0xFFF57C00, 0xFF6A1B9A, 0xFFFFFFFF).map { it.toInt() }
private val markerColors = listOf(0x99FFEB3B, 0x9976FF03, 0x99FF4081, 0x9940C4FF, 0x99FFAB40).map { it.toInt() }
private val stampColors = listOf(0xFF2E7D32, 0xFFC62828, 0xFF1565C0, 0xFF000000).map { it.toInt() }
private val coverColors = listOf(0xFFFFFFFF, 0xFFF5F5F5, 0xFFFFF8E1, 0xFF000000).map { it.toInt() }
private val signatureColors = listOf(0xFF0D2A6B, 0xFF000000, 0xFF1565C0).map { it.toInt() }

internal fun EditTool.icon(): ImageVector = when (this) {
    EditTool.VIEW -> Icons.Filled.PanTool
    EditTool.SELECT -> Icons.Filled.TouchApp
    EditTool.TEXT -> Icons.Filled.TextFields
    EditTool.PEN -> Icons.Filled.Draw
    EditTool.MARKER -> Icons.Filled.BorderColor
    EditTool.SHAPE -> Icons.Filled.Category
    EditTool.SIGN -> Icons.Filled.Gesture
    EditTool.IMAGE -> Icons.Filled.AddPhotoAlternate
    EditTool.STAMP -> Icons.Filled.Approval
    EditTool.NOTE -> Icons.AutoMirrored.Filled.StickyNote2
    EditTool.TEXT_SELECT -> Icons.Filled.FormatColorText
    EditTool.WHITEOUT -> Icons.Filled.FormatColorReset
    EditTool.REDACT -> Icons.Filled.HideSource
    EditTool.ERASER -> Icons.Filled.AutoFixNormal
    EditTool.CROP -> Icons.Filled.Crop
    EditTool.FORM -> Icons.Filled.Checklist
}

internal fun EditTool.label(): StringResource = when (this) {
    EditTool.VIEW -> Res.string.pdf_edit_tool_view
    EditTool.SELECT -> Res.string.pdf_edit_tool_select
    EditTool.TEXT -> Res.string.text
    EditTool.PEN -> Res.string.pdf_edit_tool_pen
    EditTool.MARKER -> Res.string.pdf_edit_tool_marker
    EditTool.SHAPE -> Res.string.pdf_edit_tool_shapes
    EditTool.SIGN -> Res.string.signature
    EditTool.IMAGE -> Res.string.pdf_edit_tool_image
    EditTool.STAMP -> Res.string.pdf_edit_tool_stamp
    EditTool.NOTE -> Res.string.note
    EditTool.TEXT_SELECT -> Res.string.pdf_edit_tool_select_text
    EditTool.WHITEOUT -> Res.string.pdf_edit_tool_whiteout
    EditTool.REDACT -> Res.string.pdf_edit_tool_redact
    EditTool.ERASER -> Res.string.pdf_edit_tool_eraser
    EditTool.CROP -> Res.string.pdf_edit_tool_crop
    EditTool.FORM -> Res.string.pdf_edit_tool_form
}

internal fun Stamp.label(): StringResource = when (this) {
    Stamp.APPROVED -> Res.string.pdf_edit_stamp_approved
    Stamp.PAID -> Res.string.pdf_edit_stamp_paid
    Stamp.COPY -> Res.string.pdf_edit_stamp_copy
    Stamp.DRAFT -> Res.string.pdf_edit_stamp_draft
    Stamp.CONFIDENTIAL -> Res.string.pdf_edit_stamp_confidential
    Stamp.REJECTED -> Res.string.pdf_edit_stamp_rejected
    Stamp.DATE -> Res.string.pdf_edit_stamp_date
    Stamp.CHECK -> Res.string.pdf_edit_stamp_check
    Stamp.CROSS -> Res.string.pdf_edit_stamp_cross
    Stamp.DOT -> Res.string.pdf_edit_stamp_dot
}

@Composable
internal fun ToolStrip(session: EditorSession, tools: List<EditTool>, onSelect: (EditTool) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tool in tools) {
            val selected = session.tool == tool
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colors.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tool) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(tool.icon(), contentDescription = null, tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Text(
                    tool.label().str(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
internal fun OptionsRow(session: EditorSession, input: StageInput, renderer: MarkRenderer, signatures: SignatureStore, onDrawSignature: () -> Unit, onPickImage: () -> Unit, onApplyCrop: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
        val mark = session.mark(session.selected)
        val picked = session.picked
        when {
            mark != null -> MarkOptions(session, renderer, mark)
            picked is PickedText -> Hint(Res.string.pdf_edit_picked_text.str())
            picked is PickedGraphic -> Hint(Res.string.pdf_edit_picked_graphic.str())
            else -> ToolOptions(session, input, signatures, onDrawSignature, onPickImage, onApplyCrop)
        }
    }
}

@Composable
private fun Controls(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun ToolOptions(session: EditorSession, input: StageInput, signatures: SignatureStore, onDrawSignature: () -> Unit, onPickImage: () -> Unit, onApplyCrop: (Boolean) -> Unit) {
    val style = session.style
    val point = Res.string.unit_point.str()
    fun update(change: (ToolStyle) -> ToolStyle) {
        session.style = change(session.style)
    }
    when (session.tool) {
        EditTool.VIEW -> Hint(Res.string.pdf_edit_hint_view.str())
        EditTool.SELECT -> Hint(Res.string.pdf_edit_hint_select.str())
        EditTool.PEN -> Controls {
            ColorDots(inkColors, style.penColor) { c -> update { it.copy(penColor = c) } }
            CompactSlider("${style.penWidth.toDouble().fmt(1)} $point", style.penWidth, 0.5f..12f) { w -> update { it.copy(penWidth = w) } }
        }
        EditTool.MARKER -> Controls {
            ColorDots(markerColors, style.markerColor) { c -> update { it.copy(markerColor = c) } }
            CompactSlider("${style.markerWidth.toDouble().fmt(0)} $point", style.markerWidth, 6f..30f) { w -> update { it.copy(markerWidth = w) } }
        }
        EditTool.SHAPE -> Controls {
            for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.LINE, ShapeKind.ARROW)) {
                IconToggleButton(checked = style.shape == kind, onCheckedChange = { update { it.copy(shape = kind) } }) {
                    Icon(shapeIcon(kind), contentDescription = shapeLabel(kind))
                }
            }
            if (style.shape == ShapeKind.RECTANGLE || style.shape == ShapeKind.ELLIPSE) {
                IconToggleButton(checked = style.shapeFill, onCheckedChange = { f -> update { it.copy(shapeFill = f) } }) {
                    Icon(Icons.Filled.FormatColorFill, contentDescription = Res.string.pdf_edit_fill.str())
                }
            }
            ColorDots(inkColors, style.shapeColor) { c -> update { it.copy(shapeColor = c) } }
            CompactSlider("${style.shapeWidth.toDouble().fmt(1)} $point", style.shapeWidth, 0.5f..12f) { w -> update { it.copy(shapeWidth = w) } }
        }
        EditTool.TEXT -> Controls {
            TextOptions(TextLook(style.textColor, style.textSize, style.textFont, style.bold, style.italic, style.align)) { look -> update { look.applyTo(it) } }
        }
        EditTool.SIGN -> Controls {
            signatures.all.forEachIndexed { i, signature ->
                SignatureThumb(signature, style.signatureColor, selected = i == style.signature, onClick = { update { it.copy(signature = i) } })
            }
            FilledTonalButton(onClick = onDrawSignature) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(Res.string.pdf_edit_draw_signature.str(), modifier = Modifier.padding(start = 6.dp))
            }
            if (signatures.all.isNotEmpty()) {
                IconButton(onClick = { signatures.remove(style.signature) }) { Icon(Icons.Filled.Delete, contentDescription = Res.string.delete.str()) }
            }
            ColorDots(signatureColors, style.signatureColor) { c -> update { it.copy(signatureColor = c) } }
        }
        EditTool.IMAGE -> Controls {
            FilledTonalButton(onClick = onPickImage) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(Res.string.pdf_edit_choose_image.str(), modifier = Modifier.padding(start = 6.dp))
            }
            Text(
                if (session.pendingImage != null) Res.string.pdf_edit_hint_place_image.str() else Res.string.pdf_edit_hint_image.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EditTool.STAMP -> Controls {
            Menu(Stamp.entries, style.stamp, { it.label().str() }) { s -> update { it.copy(stamp = s) } }
            ColorDots(stampColors, style.stampColor) { c -> update { it.copy(stampColor = c) } }
        }
        EditTool.NOTE -> Hint(Res.string.pdf_edit_hint_note.str())
        EditTool.TEXT_SELECT -> Hint(Res.string.pdf_edit_hint_select_text.str())
        EditTool.WHITEOUT -> Controls {
            ColorDots(coverColors, style.coverColor) { c -> update { it.copy(coverColor = c) } }
            Text(Res.string.pdf_edit_hint_whiteout.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EditTool.REDACT -> Hint(Res.string.pdf_edit_hint_redact.str())
        EditTool.ERASER -> Hint(Res.string.pdf_edit_hint_eraser.str())
        EditTool.CROP -> Controls {
            FilledTonalButton(onClick = { onApplyCrop(false) }, enabled = input.cropDraft != null) { Text(Res.string.apply.str()) }
            FilledTonalButton(onClick = { onApplyCrop(true) }, enabled = input.cropDraft != null) { Text(Res.string.pdf_edit_crop_all.str()) }
            TextButton(
                onClick = {
                    input.cropDraft = null
                    session.updatePage(session.page.id) { it.copy(crop = null) }
                },
                enabled = session.page.crop != null || input.cropDraft != null,
            ) { Text(Res.string.reset.str()) }
        }
        EditTool.FORM -> Hint(Res.string.pdf_edit_hint_form.str())
    }
}

@Composable
private fun MarkOptions(session: EditorSession, renderer: MarkRenderer, mark: Mark) {
    val point = Res.string.unit_point.str()
    when (mark) {
        is TextMark -> Controls {
            TextOptions(TextLook(mark.color, mark.size, mark.font, mark.bold, mark.italic, mark.align)) { look ->
                session.replace(renderer.fitted(mark.restyled(look)))
                session.style = look.applyTo(session.style)
            }
        }
        is InkMark -> Controls {
            ColorDots(if (mark.highlighter) markerColors else inkColors, mark.color) { c -> session.replace(mark.copy(color = c)) }
            CompactSlider("${mark.width.toDouble().fmt(1)} $point", mark.width, if (mark.highlighter) 6f..30f else 0.5f..12f) { w -> session.replace(mark.copy(width = w)) }
        }
        is ShapeMark -> Controls {
            ColorDots(inkColors, mark.color) { c -> session.replace(mark.copy(color = c, fill = mark.fill?.let { (c and 0x00FFFFFF) or 0x40000000 })) }
            CompactSlider("${mark.width.toDouble().fmt(1)} $point", mark.width, 0.5f..12f) { w -> session.replace(mark.copy(width = w)) }
        }
        is SignatureMark -> Controls { ColorDots(signatureColors, mark.color) { c -> session.replace(mark.copy(color = c)) } }
        is MarkupMark -> Controls { ColorDots(markerColors.map { it or 0xFF000000.toInt() }, mark.color) { c -> session.replace(mark.copy(color = c)) } }
        is CoverMark -> if (mark.redact) {
            Hint(Res.string.pdf_edit_hint_redact.str())
        } else {
            Controls { ColorDots(coverColors, mark.color) { c -> session.replace(mark.copy(color = c)) } }
        }
        is ImageMark, is NoteMark -> Hint(markLabel(mark))
    }
}

internal data class TextLook(val color: Int, val size: Float, val font: MarkFont, val bold: Boolean, val italic: Boolean, val align: MarkAlign) {
    fun applyTo(style: ToolStyle) = style.copy(textColor = color, textSize = size, textFont = font, bold = bold, italic = italic, align = align)
}

@Composable
private fun RowScope.TextOptions(look: TextLook, onChange: (TextLook) -> Unit) {
    ColorDots(inkColors, look.color) { onChange(look.copy(color = it)) }
    IconButton(onClick = { onChange(look.copy(size = (look.size - 1).coerceAtLeast(4f))) }) { Icon(Icons.Filled.Remove, contentDescription = null) }
    Text(look.size.roundToInt().toString(), style = MaterialTheme.typography.labelLarge)
    IconButton(onClick = { onChange(look.copy(size = (look.size + 1).coerceAtMost(96f))) }) { Icon(Icons.Filled.Add, contentDescription = Res.string.font_size.str()) }
    Menu(MarkFont.entries, look.font, { fontLabel(it) }) { onChange(look.copy(font = it)) }
    IconToggleButton(checked = look.bold, onCheckedChange = { onChange(look.copy(bold = it)) }) { Icon(Icons.Filled.FormatBold, contentDescription = Res.string.pdf_edit_bold.str()) }
    IconToggleButton(checked = look.italic, onCheckedChange = { onChange(look.copy(italic = it)) }) { Icon(Icons.Filled.FormatItalic, contentDescription = Res.string.pdf_edit_italic.str()) }
    for (align in MarkAlign.entries) {
        IconToggleButton(checked = look.align == align, onCheckedChange = { onChange(look.copy(align = align)) }) {
            Icon(
                when (align) {
                    MarkAlign.START -> Icons.AutoMirrored.Filled.FormatAlignLeft
                    MarkAlign.CENTER -> Icons.Filled.FormatAlignCenter
                    MarkAlign.END -> Icons.AutoMirrored.Filled.FormatAlignRight
                },
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun <T> Menu(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label(selected), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ColorDots(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    for (color in colors) {
        val same = (color and 0xFFFFFF) == (selected and 0xFFFFFF)
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(if (same) 3.dp else 1.dp, if (same) primary else outline, CircleShape)
                .clickable { onSelect(color) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(5.dp)
                .clip(CircleShape)
                .background(Color(color or 0xFF000000.toInt())),
        )
    }
}

@Composable
private fun CompactSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.width(140.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.RECTANGLE -> Icons.Filled.CropSquare
    ShapeKind.ELLIPSE -> Icons.Filled.RadioButtonUnchecked
    ShapeKind.LINE -> Icons.Filled.HorizontalRule
    else -> Icons.AutoMirrored.Filled.ArrowRightAlt
}

@Composable
private fun shapeLabel(kind: ShapeKind): String = when (kind) {
    ShapeKind.RECTANGLE -> Res.string.pdf_edit_shape_rectangle.str()
    ShapeKind.ELLIPSE -> Res.string.pdf_edit_shape_ellipse.str()
    ShapeKind.LINE -> Res.string.pdf_edit_shape_line.str()
    else -> Res.string.pdf_edit_shape_arrow.str()
}

@Composable
private fun fontLabel(font: MarkFont): String = when (font) {
    MarkFont.SANS -> Res.string.pdf_edit_font_sans.str()
    MarkFont.SERIF -> Res.string.pdf_edit_font_serif.str()
    MarkFont.MONO -> Res.string.pdf_edit_font_mono.str()
}

@Composable
private fun SignatureThumb(signature: SavedSignature, color: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Canvas(
        Modifier
            .size(width = 88.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        val pad = 5.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val boxW = if (w / h > signature.aspect) (h * signature.aspect).toFloat() else w
        val boxH = if (w / h > signature.aspect) h else (w / signature.aspect).toFloat()
        val left = pad + (w - boxW) / 2
        val top = pad + (h - boxH) / 2
        for (path in signature.paths) {
            drawPath(
                inkPath(path.xy) { x, y -> Offset(left + x.toFloat() * boxW, top + y.toFloat() * boxH) },
                Color(color),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun markLabel(mark: Mark): String = when (mark) {
    is InkMark -> if (mark.highlighter) Res.string.pdf_edit_tool_marker.str() else Res.string.pdf_edit_tool_pen.str()
    is ShapeMark -> when (mark.kind) {
        ShapeKind.CHECK, ShapeKind.CROSS, ShapeKind.DOT -> Res.string.pdf_edit_tool_stamp.str()
        else -> shapeLabel(mark.kind)
    }
    is TextMark -> Res.string.text.str()
    is ImageMark -> Res.string.pdf_edit_tool_image.str()
    is SignatureMark -> Res.string.signature.str()
    is NoteMark -> Res.string.note.str()
    is CoverMark -> if (mark.redact) Res.string.pdf_edit_tool_redact.str() else Res.string.pdf_edit_tool_whiteout.str()
    is MarkupMark -> Res.string.pdf_edit_tool_select_text.str()
}

internal class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
internal fun BoxScope.SelectionBar(rect: Rect, actions: List<BarAction>) {
    if (actions.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = 8.dp.roundToPx()
            val x = (rect.center.x - placeable.width / 2f).roundToInt().coerceIn(gap, (constraints.maxWidth - placeable.width - gap).coerceAtLeast(gap))
            val above = rect.top.roundToInt() - placeable.height - gap
            val y = if (above >= gap) above else (rect.bottom.roundToInt() + gap).coerceAtMost(constraints.maxHeight - placeable.height - gap)
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
        },
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (action in actions) {
                IconButton(onClick = action.onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Icon(action.icon, contentDescription = action.label) }
            }
        }
    }
}

internal fun duplicate(session: EditorSession, mark: Mark): Mark = when (mark) {
    is InkMark -> mark.copy(id = session.id())
    is ShapeMark -> mark.copy(id = session.id())
    is TextMark -> mark.copy(id = session.id())
    is ImageMark -> mark.copy(id = session.id())
    is SignatureMark -> mark.copy(id = session.id())
    is NoteMark -> mark.copy(id = session.id())
    is CoverMark -> mark.copy(id = session.id())
    is MarkupMark -> mark.copy(id = session.id())
}.translated(12.0, -12.0)
