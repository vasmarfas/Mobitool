package com.vasmarfas.card.tools.printing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

private enum class GuideTab { PICK, TABLE }

private enum class GuideView { SIMPLE, DETAILED }

private const val SHOWN_MATCHES = 8

val filamentGuideTool = Tool(
    id = "filament-guide",
    category = ToolCategory.PRINTING,
    title = Res.string.filament_guide,
    description = Res.string.filament_guide_description,
    icon = Icons.Filled.Checklist,
    keywords = listOf(
        "filament", "material", "plastic", "pla", "petg", "abs", "asa", "tpu", "nylon", "choose", "hdt", "tensile",
        "филамент", "пластик", "материал", "какой пластик", "подобрать", "термостойкий", "прочность", "ударная вязкость",
    ),
    expandable = true,
) { FilamentGuideScreen() }

@Composable
private fun FilamentGuideScreen() {
    var tab by rememberSaveable { mutableStateOf(GuideTab.PICK) }
    var view by rememberSaveable { mutableStateOf(GuideView.SIMPLE) }
    SegmentedChoice(
        options = GuideTab.entries,
        selected = tab,
        onSelect = { tab = it },
        label = { if (it == GuideTab.PICK) Res.string.filament_pick.str() else Res.string.filament_table.str() },
    )
    SegmentedChoice(
        options = GuideView.entries,
        selected = view,
        onSelect = { view = it },
        label = { if (it == GuideView.SIMPLE) Res.string.filament_view_simple.str() else Res.string.filament_view_detailed.str() },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(tab) {
        when (tab) {
            GuideTab.PICK -> FilamentPicker(view)
            GuideTab.TABLE -> FilamentTable(view)
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun FilamentPicker(view: GuideView) {
    var heat by rememberSaveable { mutableStateOf(PartHeat.ROOM) }
    var stiffness by rememberSaveable { mutableStateOf(PartStiffness.RIGID) }
    var load by rememberSaveable { mutableStateOf(PartLoad.EVERYDAY) }
    var outdoor by rememberSaveable { mutableStateOf(false) }
    var chemicals by rememberSaveable { mutableStateOf(false) }
    var printer by rememberSaveable { mutableStateOf(PrinterKind.OPEN) }
    var hardened by rememberSaveable { mutableStateOf(false) }
    var priority by rememberSaveable { mutableStateOf(PickPriority.EASY) }
    Question(Res.string.filament_q_heat.str())
    ChoiceChips(options = PartHeat.entries, selected = heat, onSelect = { heat = it }, label = { it.title.str() })
    Question(Res.string.filament_q_stiffness.str())
    ChoiceChips(options = PartStiffness.entries, selected = stiffness, onSelect = { stiffness = it }, label = { it.title.str() })
    Question(Res.string.filament_q_load.str())
    ChoiceChips(options = PartLoad.entries, selected = load, onSelect = { load = it }, label = { it.title.str() })
    SwitchRow(Res.string.filament_q_outdoor.str(), outdoor, { outdoor = it }, description = stringResource(Res.string.filament_outdoor_hint, SUN_C))
    SwitchRow(Res.string.filament_q_chemicals.str(), chemicals, { chemicals = it })
    Question(Res.string.filament_q_printer.str())
    ChoiceChips(options = PrinterKind.entries, selected = printer, onSelect = { printer = it }, label = { it.title.str() })
    SwitchRow(Res.string.filament_q_hardened.str(), hardened, { hardened = it }, description = Res.string.filament_hardened_hint.str())
    Question(Res.string.filament_q_priority.str())
    ChoiceChips(options = PickPriority.entries, selected = priority, onSelect = { priority = it }, label = { it.title.str() })

    val needs = FilamentNeeds(heat, stiffness, load, outdoor, chemicals, printer, hardened, priority)
    val matches = remember(needs) { Filaments.pick(needs).take(SHOWN_MATCHES) }
    if (matches.isEmpty()) {
        ErrorText(Res.string.filament_nothing_fits.str())
        return
    }
    ResultCard(Res.string.filament_best.str()) {
        val temps = { m: FilamentMaterial -> "${m.nozzleC.first}–${m.nozzleC.last} / ${m.bedC.first}–${m.bedC.last} °C" }
        if (view == GuideView.SIMPLE) {
            SimpleTable(
                header = listOf(Res.string.material.str(), Res.string.filament_why.str(), Res.string.filament_col_temps.str()),
                rows = matches.mapIndexed { index, match ->
                    listOf("${index + 1}. ${match.material.title.str()}", reasons(match.traits, needs).joinToString(", "), temps(match.material))
                },
                weights = listOf(1.4f, 2.8f, 1.2f),
                mono = false,
                highlight = 0,
            )
        } else {
            val noBreak = Res.string.filament_no_break.str()
            val none = Res.string.filament_no_data.str()
            SimpleTable(
                header = listOf(
                    Res.string.material.str(),
                    Res.string.filament_why.str(),
                    Res.string.filament_col_softening.str(),
                    Res.string.filament_col_tensile.str(),
                    Res.string.filament_col_modulus.str(),
                    Res.string.filament_col_impact_kj.str(),
                    Res.string.filament_col_temps.str(),
                ),
                rows = matches.mapIndexed { index, match ->
                    val spec = filamentSpecs[match.material]
                    listOf(
                        "${index + 1}. ${match.material.title.str()}",
                        reasons(match.traits, needs).joinToString(", "),
                        match.traits.softeningC.toString(),
                        spec?.tensileMpa.shown(0, none),
                        spec?.modulusMpa.gigapascals(none),
                        spec?.impactKj.shown(1, none, noBreak),
                        temps(match.material),
                    )
                },
                weights = listOf(1.4f, 2.6f, 0.9f, 0.8f, 0.8f, 0.8f, 1.2f),
                mono = false,
                highlight = 0,
            )
            Hint(Res.string.filament_detailed_legend.str())
        }
    }
}

@Composable
private fun printNotes(t: FilamentTraits): List<String> = buildList {
    when (t.chamber) {
        Chamber.CLOSED -> add(Res.string.filament_why_enclosure.str())
        Chamber.HEATED -> add(Res.string.filament_why_chamber.str())
        Chamber.HOT -> add(Res.string.filament_why_hot_chamber.str())
        Chamber.NONE -> Unit
    }
    if (FilamentFlag.ABRASIVE in t.flags) add(Res.string.filament_why_hardened.str())
    if (FilamentFlag.DRY in t.flags) add(Res.string.filament_why_dry.str())
    if (FilamentFlag.FUMES in t.flags) add(Res.string.filament_why_fumes.str())
    if (FilamentFlag.ANNEAL in t.flags) add(Res.string.filament_why_anneal.str())
    if (FilamentFlag.WATER_SOLUBLE in t.flags) add(Res.string.filament_why_water.str())
    if (FilamentFlag.LIMONENE_SOLUBLE in t.flags) add(Res.string.filament_why_limonene.str())
    if (FilamentFlag.LOW_MELT in t.flags) add(Res.string.filament_why_low_melt.str())
}

@Composable
private fun reasons(t: FilamentTraits, needs: FilamentNeeds): List<String> = buildList {
    add(stringResource(Res.string.filament_why_heat, t.softeningC))
    if (needs.outdoor && t.softeningC < SUN_C + 15) add(Res.string.filament_why_sun_margin.str())
    when (t.flexibility) {
        5, 4 -> add(Res.string.filament_why_rubber.str())
        3 -> add(Res.string.filament_why_springy.str())
    }
    if (needs.load != PartLoad.DECOR) {
        if (t.strength >= 4) add(Res.string.filament_why_strong.str())
        if (t.impact >= 4) add(Res.string.filament_why_tough.str())
        if (t.impact <= 1) add(Res.string.filament_why_brittle.str())
    }
    if (needs.outdoor && t.uv >= 4) add(Res.string.filament_why_sun.str())
    if (needs.chemicals && t.chemical >= 4) add(Res.string.filament_why_chemicals.str())
    if (t.ease >= 4) add(Res.string.filament_why_easy.str())
    if (t.ease <= 1) add(Res.string.filament_why_fussy.str())
    if (t.price <= 1) add(Res.string.filament_why_cheap.str())
    if (t.price >= 5) add(Res.string.filament_why_pricey.str())
    addAll(printNotes(t))
}

@Composable
private fun tableNotes(t: FilamentTraits): List<String> = buildList {
    if (FilamentFlag.OUTDOOR in t.flags) add(Res.string.filament_why_sun.str())
    if (t.uv <= 1) add(Res.string.filament_why_fades.str())
    if (t.chemical >= 4) add(Res.string.filament_why_chemicals.str())
    addAll(printNotes(t))
}

private fun dots(score: Int): String = "●".repeat(score) + "○".repeat(5 - score)

private fun Double?.shown(fraction: Int, none: String, noBreak: String = none): String = when (this) {
    null -> none
    NO_BREAK -> noBreak
    else -> fmt(fraction)
}

private fun Int?.shown(none: String): String = this?.toString() ?: none

private fun Int?.gigapascals(none: String): String = this?.let { (it / 1000.0).fmt(if (it < 100) 3 else 2) } ?: none

@Composable
private fun FilamentTable(view: GuideView) {
    val wideTables = LocalSettings.current.wideTables
    var grid by rememberSaveable { mutableStateOf(wideTables) }
    var group by rememberSaveable { mutableStateOf(FilamentGroup.BASIC) }
    var query by rememberSaveable { mutableStateOf("") }
    SwitchRow(Res.string.filament_grid.str(), grid, { grid = it }, description = Res.string.filament_grid_hint.str())
    ToolInputField(value = query, onValueChange = { query = it }, label = Res.string.filament_search.str())
    ChoiceChips(options = FilamentGroup.entries, selected = group, onSelect = { group = it; query = "" }, label = { it.title.str() })
    val q = query.trim().replace('-', ' ').replace('_', ' ')
    val materials = FilamentMaterial.entries.filter { m ->
        if (q.isEmpty()) Filaments.traits.getValue(m).group == group
        else m.title.str().contains(q, ignoreCase = true) || m.name.replace('_', ' ').contains(q, ignoreCase = true)
    }
    if (materials.isEmpty()) {
        Hint(Res.string.nothing_found.str())
        return
    }
    if (view == GuideView.SIMPLE) {
        SimpleTable(
            header = listOf(
                Res.string.material.str(),
                Res.string.filament_col_nozzle.str(),
                Res.string.filament_col_bed.str(),
                Res.string.filament_col_softening.str(),
                Res.string.filament_col_strength.str(),
                Res.string.filament_col_impact.str(),
                Res.string.filament_col_flexibility.str(),
                Res.string.filament_col_ease.str(),
                Res.string.filament_col_speed.str(),
                Res.string.filament_col_price.str(),
                Res.string.filament_col_notes.str(),
            ),
            rows = materials.map { material ->
                val t = Filaments.traits.getValue(material)
                listOf(
                    material.title.str(),
                    "${material.nozzleC.first}–${material.nozzleC.last}",
                    "${material.bedC.first}–${material.bedC.last}",
                    "${t.softeningC}",
                    dots(t.strength),
                    dots(t.impact),
                    dots(t.flexibility),
                    dots(t.ease),
                    dots(t.speed),
                    dots(t.price),
                    tableNotes(t).joinToString(", "),
                )
            },
            mono = false,
            wide = grid,
        )
        Hint(Res.string.filament_table_hint.str())
        return
    }
    val noBreak = Res.string.filament_no_break.str()
    val none = Res.string.filament_no_data.str()
    val untested = Res.string.filament_not_tested.str()
    SimpleTable(
        header = listOf(
            Res.string.material.str(),
            Res.string.filament_col_density.str(),
            Res.string.filament_col_hdt_low.str(),
            Res.string.filament_col_hdt_high.str(),
            Res.string.filament_col_glass.str(),
            Res.string.filament_col_tensile.str(),
            Res.string.filament_col_modulus.str(),
            Res.string.filament_col_elongation.str(),
            Res.string.filament_col_flexural.str(),
            Res.string.filament_col_impact_kj.str(),
            Res.string.filament_col_hardness.str(),
            Res.string.filament_col_water.str(),
            Res.string.filament_col_drying.str(),
            Res.string.filament_col_flow.str(),
        ),
        rows = materials.map { material ->
            val spec = filamentSpecs[material]
            val drying = if (spec?.dryC != null && spec.dryHours != null) stringResource(Res.string.filament_drying_value, spec.dryC, spec.dryHours.fmt(1)) else none
            val flexible = Filaments.traits.getValue(material).group == FilamentGroup.FLEXIBLE
            listOf(
                material.title.str(),
                material.density.fmt(2),
                spec?.hdtLowC.shown(none),
                spec?.hdtHighC.shown(if (flexible) untested else none),
                spec?.glassC.shown(none),
                spec?.tensileMpa.shown(0, none),
                spec?.modulusMpa.gigapascals(none),
                spec?.elongationPct.shown(1, none),
                spec?.flexuralMpa.shown(0, none, noBreak),
                spec?.impactKj.shown(1, none, noBreak),
                spec?.hardness ?: none,
                spec?.waterPct.shown(2, none),
                drying,
                spec?.flowMm3s.shown(1, none),
            )
        },
        mono = false,
        wide = grid,
    )
    Hint(Res.string.filament_detailed_legend.str())
}
