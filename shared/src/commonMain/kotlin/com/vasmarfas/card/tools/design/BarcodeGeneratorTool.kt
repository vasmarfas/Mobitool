package com.vasmarfas.card.tools.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewWeek
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.expandedHeight
import com.vasmarfas.card.ui.components.monoFamily
import io.github.alexzhirkevich.qrose.oned.BarcodeType
import io.github.alexzhirkevich.qrose.oned.rememberBarcodePainter

val barcodeGeneratorTool = Tool(
    id = "barcode-generator",
    category = ToolCategory.DESIGN,
    title = Res.string.barcode,
    description = Res.string.barcode_generator_description,
    icon = Icons.Filled.ViewWeek,
    keywords = listOf("barcode", "ean13", "ean-13", "upc", "code128", "checksum", "штрихкод", "ean", "контрольная цифра"),
    expandable = true,
) { BarcodeGeneratorScreen() }

@Composable
private fun BarcodeGeneratorScreen() {
    var scanning by rememberSaveable { mutableStateOf(false) }
    CreateOrScan(scanning) { scanning = it }
    if (scanning) CodeScanner() else BarcodeCreator()
}

@Composable
private fun BarcodeCreator() {
    var type by rememberSaveable { mutableStateOf(BarcodeType.EAN13) }
    var data by rememberSaveable { mutableStateOf(BarcodeType.EAN13.sampleData()) }
    ChoiceChips(
        options = supportedBarcodes,
        selected = type,
        onSelect = {
            type = it
            data = it.sampleData()
        },
        label = { it.name },
    )
    ToolInputField(
        value = data,
        onValueChange = { data = it },
        label = Res.string.data.str(),
        placeholder = type.sampleData(),
        isError = validateBarcode(type, data) != null,
        monospace = true,
    )
    val digits = type.digitsRequired()
    if (digits != null && data.trim().length == digits - 1 && data.trim().all { it.isDigit() }) {
        val completed = Ean13.complete(data.trim())
        if (completed != null) {
            ActionButton(
                text = "${Res.string.add_check_digit.str()}: ${completed.last()}",
                onClick = { data = completed },
            )
        }
    }
    val error = validateBarcode(type, data)
    if (error != null) {
        ErrorText(error.str())
        return
    }
    val value = data.trim().let { if (type == BarcodeType.Code39) it.uppercase() else it }
    val painter = rememberBarcodePainter(
        data = value,
        type = type,
        brush = SolidColor(Color.Black),
        onError = { ColorPainter(Color.Transparent) },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painter, contentDescription = null, modifier = Modifier.fillMaxWidth().height(expandedHeight(normal = 120.dp, reserved = 420.dp)))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = monoFamily()),
            color = Color.Black,
            textAlign = TextAlign.Center,
        )
    }
    val check = remember(value, type) { if (digits != null) Ean13.checksum(value.dropLast(1)) else null }
    ResultCard {
        KeyValueRow(Res.string.format.str(), type.name, copyable = false)
        KeyValueRow(Res.string.value_.str(), value)
        KeyValueRow(Res.string.length.str(), value.length.toString(), copyable = false)
        if (check != null) {
            KeyValueRow(Res.string.check_digit.str(), check.toString(), copyable = false)
        }
    }
}
