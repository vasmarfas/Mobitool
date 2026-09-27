package com.vasmarfas.card.tools.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

private const val MAX_EVENTS = 20

// a tenkeyless board is 18.25 keys wide, the numpad adds 4.25 more
private const val TKL_UNITS = 18.25f
private const val FULL_UNITS = 22.5f
private const val KEYBOARD_ROWS = 6.5f
private const val FULL_WIDTH_DP = 600

private class KeyCap(val key: Key, val label: String, val x: Float, val y: Float, val w: Float = 1f, val h: Float = 1f)

private class Cap(val key: Key?, val label: String, val w: Float = 1f)

private class PressedKey(val name: String, val code: Long, val char: String, val modifiers: String)

private fun gap(w: Float) = Cap(null, "", w)

private fun caps(labels: String, keys: List<Key>): List<Cap> = keys.mapIndexed { i, key -> Cap(key, labels[i].toString()) }

private fun row(y: Float, x: Float, caps: List<Cap>): List<KeyCap> {
    var cx = x
    return caps.mapNotNull { cap ->
        val placed = cap.key?.let { KeyCap(it, cap.label, cx, y, cap.w) }
        cx += cap.w
        placed
    }
}

private val functionKeys = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
    .mapIndexed { i, key -> Cap(key, "F${i + 1}") }

private val digitRow = listOf(Key.Grave, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero, Key.Minus, Key.Equals)
private val topRow = listOf(Key.Q, Key.W, Key.E, Key.R, Key.T, Key.Y, Key.U, Key.I, Key.O, Key.P, Key.LeftBracket, Key.RightBracket)
private val homeRow = listOf(Key.A, Key.S, Key.D, Key.F, Key.G, Key.H, Key.J, Key.K, Key.L, Key.Semicolon, Key.Apostrophe)
private val bottomRow = listOf(Key.Z, Key.X, Key.C, Key.V, Key.B, Key.N, Key.M, Key.Comma, Key.Period, Key.Slash)

private val mainKeys: List<KeyCap> = buildList {
    addAll(row(0f, 0f, listOf(Cap(Key.Escape, "Esc"), gap(1f)) + functionKeys.take(4) + gap(0.5f) + functionKeys.subList(4, 8) + gap(0.5f) + functionKeys.drop(8)))
    addAll(row(1.5f, 0f, caps("`1234567890-=", digitRow) + Cap(Key.Backspace, "⌫", 2f)))
    addAll(row(2.5f, 0f, listOf(Cap(Key.Tab, "Tab", 1.5f)) + caps("QWERTYUIOP[]", topRow) + Cap(Key.Backslash, "\\", 1.5f)))
    addAll(row(3.5f, 0f, listOf(Cap(Key.CapsLock, "Caps", 1.75f)) + caps("ASDFGHJKL;'", homeRow) + Cap(Key.Enter, "Enter", 2.25f)))
    addAll(row(4.5f, 0f, listOf(Cap(Key.ShiftLeft, "Shift", 2.25f)) + caps("ZXCVBNM,./", bottomRow) + Cap(Key.ShiftRight, "Shift", 2.75f)))
    addAll(
        row(
            5.5f, 0f,
            listOf(
                Cap(Key.CtrlLeft, "Ctrl", 1.25f), Cap(Key.MetaLeft, "Win", 1.25f), Cap(Key.AltLeft, "Alt", 1.25f), Cap(Key.Spacebar, "", 6.25f),
                Cap(Key.AltRight, "Alt", 1.25f), Cap(Key.MetaRight, "Win", 1.25f), Cap(Key.Menu, "☰", 1.25f), Cap(Key.CtrlRight, "Ctrl", 1.25f),
            ),
        ),
    )
    addAll(row(0f, 15.25f, listOf(Cap(Key.PrintScreen, "PrtSc"), Cap(Key.ScrollLock, "ScrLk"), Cap(Key.Break, "Pause"))))
    addAll(row(1.5f, 15.25f, listOf(Cap(Key.Insert, "Ins"), Cap(Key.MoveHome, "Home"), Cap(Key.PageUp, "PgUp"))))
    addAll(row(2.5f, 15.25f, listOf(Cap(Key.Delete, "Del"), Cap(Key.MoveEnd, "End"), Cap(Key.PageDown, "PgDn"))))
    add(KeyCap(Key.DirectionUp, "↑", 16.25f, 4.5f))
    addAll(row(5.5f, 15.25f, listOf(Cap(Key.DirectionLeft, "←"), Cap(Key.DirectionDown, "↓"), Cap(Key.DirectionRight, "→"))))
}

// laid out from x = 0, placed to the right of the main block on a wide screen and under it on a narrow one
private val numpadKeys: List<KeyCap> = buildList {
    addAll(row(0f, 0f, listOf(Cap(Key.NumLock, "Num"), Cap(Key.NumPadDivide, "/"), Cap(Key.NumPadMultiply, "*"), Cap(Key.NumPadSubtract, "-"))))
    addAll(row(1f, 0f, caps("789", listOf(Key.NumPad7, Key.NumPad8, Key.NumPad9))))
    add(KeyCap(Key.NumPadAdd, "+", 3f, 1f, h = 2f))
    addAll(row(2f, 0f, caps("456", listOf(Key.NumPad4, Key.NumPad5, Key.NumPad6))))
    addAll(row(3f, 0f, caps("123", listOf(Key.NumPad1, Key.NumPad2, Key.NumPad3))))
    add(KeyCap(Key.NumPadEnter, "Enter", 3f, 3f, h = 2f))
    addAll(row(4f, 0f, listOf(Cap(Key.NumPad0, "0", 2f), Cap(Key.NumPadDot, "."))))
}

private val allKeys = (mainKeys + numpadKeys).map { it.key }.toSet()

private val spokenNames = mapOf(
    Key.Backspace to "Backspace", Key.Spacebar to "Space", Key.Menu to "Menu",
    Key.DirectionUp to "Up", Key.DirectionDown to "Down", Key.DirectionLeft to "Left", Key.DirectionRight to "Right",
)

private val keyNames: Map<Key, String> =
    mainKeys.associate { it.key to it.label } + numpadKeys.associate { it.key to "Num ${it.label}" } + spokenNames

// the platforms print a key differently, the web one only as its numeric code
private fun keyName(key: Key): String = keyNames[key] ?: key.toString().removePrefix("Key: ").removePrefix("Key ")

private val textKeys = (digitRow + topRow + homeRow + bottomRow + Key.Backslash + Key.Spacebar).toSet()

private val numpadChars = mapOf(
    Key.NumPad0 to "0", Key.NumPad1 to "1", Key.NumPad2 to "2", Key.NumPad3 to "3", Key.NumPad4 to "4",
    Key.NumPad5 to "5", Key.NumPad6 to "6", Key.NumPad7 to "7", Key.NumPad8 to "8", Key.NumPad9 to "9",
    Key.NumPadDivide to "/", Key.NumPadMultiply to "*", Key.NumPadSubtract to "-", Key.NumPadAdd to "+", Key.NumPadDot to ".",
)

// only the typing keys give a character from the layout: the browser hands out the key code for the rest
private fun charOf(key: Key, codePoint: Int): String = numpadChars[key]
    ?: if (key in textKeys && codePoint in 0x20..0x10FFFF && codePoint != 0x7F) codePoint.toChar().toString() else "—"

private fun eventRow(type: String, key: String, char: String, code: String, modifiers: String): String =
    type.padEnd(8) + key.take(24).padEnd(26) + char.padEnd(11) + code.padEnd(13) + modifiers

val keyboardTesterTool = Tool(
    id = "keyboard-tester",
    category = ToolCategory.DEVICE,
    title = Res.string.keyboard_tester,
    description = Res.string.keyboard_tester_description,
    icon = Icons.Filled.Keyboard,
    keywords = listOf("keyboard", "key", "keycode", "shortcut", "modifiers", "input", "dead key", "клавиатура", "клавиша", "код клавиши", "сочетание", "залипает"),
    platforms = setOf(PlatformKind.DESKTOP, PlatformKind.WEB, PlatformKind.ANDROID),
) { KeyboardTesterScreen() }

// the key handler sits on the whole screen, so keys are caught wherever the focus is inside it, and any tap
// on the screen takes the focus back
@Composable
private fun KeyboardTesterScreen() {
    val events = remember { mutableStateListOf<String>() }
    val pressed = remember { mutableStateListOf<Key>() }
    val tested = remember { mutableStateListOf<Key>() }
    var last by remember { mutableStateOf<PressedKey?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.hasFocus
                if (!it.hasFocus) pressed.clear()
            }
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                // held keys repeat their key down, only the first one counts
                if (down && e.key in pressed) return@onPreviewKeyEvent true
                if (down) {
                    pressed.add(e.key)
                    if (e.key !in tested) tested.add(e.key)
                } else if (e.type == KeyEventType.KeyUp) {
                    pressed.remove(e.key)
                }
                val modifiers = buildList {
                    if (e.isCtrlPressed) add("Ctrl")
                    if (e.isShiftPressed) add("Shift")
                    if (e.isAltPressed) add("Alt")
                    if (e.isMetaPressed) add("Meta")
                }.joinToString("+").ifEmpty { "—" }
                val type = if (down) "down" else if (e.type == KeyEventType.KeyUp) "up" else "unknown"
                val name = keyName(e.key)
                val char = charOf(e.key, e.utf16CodePoint)
                if (down) last = PressedKey(name, e.key.keyCode, char, modifiers)
                events.add(0, eventRow(type, name, char, e.key.keyCode.toString(), modifiers))
                while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
                true
            }
            .focusTarget()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    focusRequester.requestFocus()
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (focused) Res.string.press_any_key.str() else Res.string.click_here_to_capture_keys.str(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= FULL_WIDTH_DP.dp) {
                    val unit = maxWidth / FULL_UNITS
                    Keyboard(mainKeys + numpadKeys.map { KeyCap(it.key, it.label, it.x + TKL_UNITS + 0.25f, it.y + 1.5f, it.w, it.h) }, FULL_UNITS, KEYBOARD_ROWS, unit, pressed, tested)
                } else {
                    val unit = maxWidth / TKL_UNITS
                    Column(verticalArrangement = Arrangement.spacedBy(unit * 0.5f)) {
                        Keyboard(mainKeys, TKL_UNITS, KEYBOARD_ROWS, unit, pressed, tested)
                        Keyboard(numpadKeys, 4f, 5f, unit, pressed, tested)
                    }
                }
            }
        }
        Hint(Res.string.keyboard_system_keys_hint.str())
        ResultCard {
            KeyValueRow(Res.string.keyboard_tested.str(), stringResource(Res.string.keyboard_tested_of, tested.count { it in allKeys }, allKeys.size), copyable = false)
            last?.let {
                KeyValueRow(Res.string.keyboard_last_key.str(), it.name)
                KeyValueRow(Res.string.keyboard_key_code.str(), it.code.toString())
                KeyValueRow(Res.string.keyboard_char.str(), it.char)
                KeyValueRow(Res.string.keyboard_modifiers.str(), it.modifiers)
            }
        }
        ActionButton(
            Res.string.clear.str(),
            onClick = {
                events.clear()
                tested.clear()
                last = null
            },
            enabled = events.isNotEmpty() || tested.isNotEmpty(),
        )
        if (events.isNotEmpty()) {
            ResultCard(Res.string.last_events.str()) {
                val header = eventRow(
                    Res.string.type.str(),
                    Res.string.keyboard_key.str(),
                    Res.string.keyboard_char.str(),
                    Res.string.keyboard_key_code.str(),
                    Res.string.keyboard_modifiers.str(),
                )
                MonoTable(listOf(header) + events.toList())
            }
        }
    }
}

@Composable
private fun Keyboard(keys: List<KeyCap>, widthUnits: Float, heightUnits: Float, unit: Dp, pressed: List<Key>, tested: List<Key>) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.labelSmall
    Canvas(Modifier.width(unit * widthUnits).height(unit * heightUnits)) {
        val u = size.width / widthUnits
        val inset = u * 0.06f
        val style = base.copy(fontSize = (u * 0.3f).coerceIn(7.dp.toPx(), 14.dp.toPx()).toSp())
        for (cap in keys) {
            val down = cap.key in pressed
            val done = cap.key in tested
            val fill = when {
                down -> colors.primary
                done -> colors.primaryContainer
                else -> colors.surfaceContainerHighest
            }
            val ink = when {
                down -> colors.onPrimary
                done -> colors.onPrimaryContainer
                else -> colors.onSurfaceVariant
            }
            val topLeft = Offset(cap.x * u + inset, cap.y * u + inset)
            val box = Size(cap.w * u - inset * 2, cap.h * u - inset * 2)
            drawRoundRect(fill, topLeft, box, CornerRadius(u * 0.14f))
            if (cap.label.isNotEmpty()) {
                val layout = measurer.measure(cap.label, style.copy(color = ink), maxLines = 1)
                drawText(layout, topLeft = Offset(topLeft.x + (box.width - layout.size.width) / 2, topLeft.y + (box.height - layout.size.height) / 2))
            }
        }
    }
}
