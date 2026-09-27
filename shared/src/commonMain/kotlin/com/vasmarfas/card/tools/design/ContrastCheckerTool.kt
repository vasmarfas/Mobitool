package com.vasmarfas.card.tools.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.theme.LocalStatusColors

val contrastCheckerTool = Tool(
    id = "contrast-checker",
    category = ToolCategory.DESIGN,
    title = Res.string.contrast_checker,
    description = Res.string.contrast_checker_description,
    icon = Icons.Filled.Contrast,
    keywords = listOf("wcag", "contrast", "accessibility", "aa", "aaa", "контраст", "доступность", "читаемость"),
) { ContrastCheckerScreen() }

@Composable
private fun ContrastCheckerScreen() {
    var fgText by rememberSaveable { mutableStateOf("#767676") }
    var bgText by rememberSaveable { mutableStateOf("#FFFFFF") }
    val fg = remember(fgText) { ColorMath.parse(fgText) }
    val bg = remember(bgText) { ColorMath.parse(bgText) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ToolInputField(
            value = fgText,
            onValueChange = { fgText = it },
            label = Res.string.text.str(),
            modifier = Modifier.weight(1f),
            isError = fg == null,
            monospace = true,
        )
        ToolInputField(
            value = bgText,
            onValueChange = { bgText = it },
            label = Res.string.background.str(),
            modifier = Modifier.weight(1f),
            isError = bg == null,
            monospace = true,
        )
    }
    ActionButton(
        text = Res.string.swap.str(),
        onClick = {
            val t = fgText
            fgText = bgText
            bgText = t
        },
    )
    if (fg == null || bg == null) {
        ErrorText(Res.string.unknown_color_format.str())
        return
    }
    val shown = Wcag.over(fg, bg)
    val check = Wcag.check(shown, bg)
    Preview(fg, bg)
    AnswerCard("${Wcag.rounded(check.ratio).fmt(2)} : 1", Res.string.contrast_ratio.str())
    ResultCard {
        VerdictRow(Res.string.aa_normal_text.str(), check.aaNormal)
        VerdictRow(Res.string.aa_large_text.str(), check.aaLarge)
        VerdictRow(Res.string.aaa_normal_text.str(), check.aaaNormal)
        VerdictRow(Res.string.aaa_large_text.str(), check.aaaLarge)
    }
    if (!check.aaNormal) {
        val fixed = Wcag.nearestPassing(shown, bg, 4.5)
        ResultCard(Res.string.nearest_passing_text_color.str()) {
            if (fixed == null) {
                Text(Res.string.contrast_no_lightness.str())
            } else {
                WideSwatch(fixed, fixed.hex(), "${Wcag.rounded(Wcag.ratio(fixed, bg)).fmt(2)} : 1")
                ActionButton(text = Res.string.use_it.str(), onClick = { fgText = fixed.hex() })
            }
        }
    }
}

@Composable
private fun VerdictRow(label: String, pass: Boolean) {
    val color = if (pass) LocalStatusColors.current.good else LocalStatusColors.current.bad
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(if (pass) Icons.Filled.CheckCircle else Icons.Filled.Cancel, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text((if (pass) Res.string.pass_ else Res.string.fail).str(), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun Preview(fg: Rgba, bg: Rgba) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg.toColor())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = Res.string.large_text_24_sp.str(),
            style = MaterialTheme.typography.headlineSmall,
            color = fg.toColor(),
        )
        Text(
            text = Res.string.contrast_normal_body_text.str(),
            style = MaterialTheme.typography.bodyMedium,
            color = fg.toColor(),
        )
    }
}
