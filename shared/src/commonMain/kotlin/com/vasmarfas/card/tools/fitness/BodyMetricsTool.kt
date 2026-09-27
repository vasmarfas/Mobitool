package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import org.jetbrains.compose.resources.stringResource

val bodyMetricsTool = Tool(
    id = "body-metrics",
    category = ToolCategory.FITNESS,
    title = Res.string.body_metrics,
    description = Res.string.body_metrics_description,
    icon = Icons.Filled.Accessibility,
    keywords = listOf("waist", "hip", "ffmi", "lean body mass", "bsa", "du bois", "талия", "бёдра", "сухая масса", "площадь тела"),
) { BodyMetricsScreen() }

@Composable
private fun BodyMetricsScreen() {
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
    var heightText by rememberSaveable { mutableStateOf("178") }
    var weightText by rememberSaveable { mutableStateOf("78") }
    var waistText by rememberSaveable { mutableStateOf("84") }
    var hipText by rememberSaveable { mutableStateOf("98") }
    var bodyFatText by rememberSaveable { mutableStateOf("") }

    SegmentedChoice(
        options = Sex.entries,
        selected = sex,
        onSelect = { sex = it },
        label = { if (it == Sex.MALE) Res.string.male.str() else Res.string.female.str() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = heightText,
            onValueChange = { heightText = it },
            label = Res.string.body_height.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_cm.str(),
            isError = heightText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = weightText,
            onValueChange = { weightText = it },
            label = Res.string.weight.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_kg.str(),
            isError = weightText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = waistText,
            onValueChange = { waistText = it },
            label = Res.string.waist.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_cm.str(),
            isError = waistText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = hipText,
            onValueChange = { hipText = it },
            label = Res.string.hips.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_cm.str(),
            isError = hipText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    NumberField(
        value = bodyFatText,
        onValueChange = { bodyFatText = it },
        label = Res.string.body_fat_optional.str(),
        suffix = "%",
        isError = bodyFatText.isNotBlank() && bodyFatText.toDoubleLenient().let { it == null || it < 0 || it >= 70 },
        supportingText = Res.string.used_for_ffmi_when_filled_in.str(),
    )

    val height = heightText.toDoubleLenient()
    val weight = weightText.toDoubleLenient()
    val waist = waistText.toDoubleLenient()
    val hip = hipText.toDoubleLenient()
    val bodyFat = if (bodyFatText.isBlank()) null else bodyFatText.toDoubleLenient()
    if (height == null || height <= 0 || weight == null || weight <= 0 || waist == null || waist <= 0 ||
        hip == null || hip <= 0 || (bodyFatText.isNotBlank() && (bodyFat == null || bodyFat < 0 || bodyFat >= 70))
    ) {
        ErrorText(Res.string.body_all_measurements.str())
        return
    }

    val whtr = BodyMetrics.waistToHeight(waist, height)
    val whr = BodyMetrics.waistToHip(waist, hip)
    ResultCard(Res.string.proportions.str()) {
        KeyValueRow(Res.string.waist_to_height.str(), "${whtr.fmt(3)} · ${BodyMetrics.waistToHeightNote(whtr).str()}", mono = false)
        KeyValueRow(Res.string.waist_to_hip.str(), "${whr.fmt(3)} · ${BodyMetrics.waistToHipNote(sex, whr).str()}", mono = false)
        Text(
            text = Res.string.body_waist_to_height_under.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ResultCard(Res.string.body_surface_area.str()) {
        KeyValueRow("Du Bois", "${BodyMetrics.bsaDuBois(weight, height).fmt(3)} m²")
        KeyValueRow("Mosteller", "${BodyMetrics.bsaMosteller(weight, height).fmt(3)} m²")
        Text(
            text = Res.string.body_bsa_is_what_drug.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val leans = BodyMetrics.leanMasses(sex, weight, height)
    val leanForFfmi = if (bodyFat != null) BodyMetrics.leanFromBodyFat(weight, bodyFat) else leans.first().value
    ResultCard(Res.string.lean_body_mass.str()) {
        SimpleTable(
            header = listOf(
                Res.string.formula.str(),
                Res.string.body_lean_mass.str(),
                Res.string.fat.str(),
            ),
            rows = leans.map {
                listOf(it.name, "${it.value.fmt(1)} ${Res.string.unit_kg.str()}", stringResource(Res.string.percent_value, ((weight - it.value) / weight * 100).fmt(1)))
            },
            weights = listOf(1f, 1.2f, 1f),
            mono = false,
        )
        Text(
            text = Res.string.body_boer_is_the_general.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ResultCard(Res.string.ffmi_title.str()) {
        Hint(Res.string.ffmi_hint.str())
        KeyValueRow(
            Res.string.lean_mass_used.str(),
            "${leanForFfmi.fmt(1)} ${Res.string.unit_kg.str()} · " +
                (if (bodyFat != null) Res.string.from_body_fat.str() else "Boer"),
            mono = false,
        )
        KeyValueRow("FFMI", BodyMetrics.ffmi(leanForFfmi, height).fmt(2))
        KeyValueRow(Res.string.normalized_ffmi.str(), BodyMetrics.ffmiNormalized(leanForFfmi, height).fmt(2))
        KeyValueRow(
            Res.string.interpretation.str(),
            BodyMetrics.ffmiNote(sex, BodyMetrics.ffmiNormalized(leanForFfmi, height)).str(),
            mono = false,
            copyable = false,
        )
        Text(
            text = Res.string.body_normalization_scales_ffmi.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
