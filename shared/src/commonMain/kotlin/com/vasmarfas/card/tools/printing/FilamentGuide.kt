package com.vasmarfas.card.tools.printing

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.printing.Chamber.CLOSED
import com.vasmarfas.card.tools.printing.Chamber.HEATED
import com.vasmarfas.card.tools.printing.Chamber.HOT
import com.vasmarfas.card.tools.printing.Chamber.NONE
import com.vasmarfas.card.tools.printing.FilamentFlag.ABRASIVE
import com.vasmarfas.card.tools.printing.FilamentFlag.ANNEAL
import com.vasmarfas.card.tools.printing.FilamentFlag.DRY
import com.vasmarfas.card.tools.printing.FilamentFlag.FUMES
import com.vasmarfas.card.tools.printing.FilamentFlag.LIMONENE_SOLUBLE
import com.vasmarfas.card.tools.printing.FilamentFlag.LOW_MELT
import com.vasmarfas.card.tools.printing.FilamentFlag.OUTDOOR
import com.vasmarfas.card.tools.printing.FilamentFlag.WATER_SOLUBLE
import com.vasmarfas.card.tools.printing.FilamentGroup.BASIC
import com.vasmarfas.card.tools.printing.FilamentGroup.ENGINEERING
import com.vasmarfas.card.tools.printing.FilamentGroup.FILLED
import com.vasmarfas.card.tools.printing.FilamentGroup.FLEXIBLE
import com.vasmarfas.card.tools.printing.FilamentGroup.HIGH_TEMP
import com.vasmarfas.card.tools.printing.FilamentGroup.SUPPORT
import org.jetbrains.compose.resources.StringResource

enum class FilamentGroup(val title: StringResource) {
    BASIC(Res.string.filament_group_basic),
    FLEXIBLE(Res.string.filament_group_flexible),
    ENGINEERING(Res.string.filament_group_engineering),
    HIGH_TEMP(Res.string.filament_group_high_temp),
    FILLED(Res.string.filament_group_filled),
    SUPPORT(Res.string.filament_group_support),
}

enum class FilamentFlag { ABRASIVE, DRY, FUMES, ANNEAL, WATER_SOLUBLE, LIMONENE_SOLUBLE, LOW_MELT, OUTDOOR }

enum class Chamber { NONE, CLOSED, HEATED, HOT }

class FilamentTraits(
    val group: FilamentGroup,
    val softeningC: Int,
    val strength: Int,
    val impact: Int,
    val flexibility: Int,
    val ease: Int,
    val speed: Int,
    val uv: Int,
    val chemical: Int,
    val price: Int,
    val chamber: Chamber,
    val flags: Set<FilamentFlag> = emptySet(),
)

enum class PartHeat(val celsius: Int, val title: StringResource) {
    ROOM(40, Res.string.filament_heat_room),
    WARM(60, Res.string.filament_heat_warm),
    HOT(85, Res.string.filament_heat_hot),
    VERY_HOT(110, Res.string.filament_heat_very_hot),
}

enum class PartStiffness(val title: StringResource) {
    RIGID(Res.string.filament_stiff_rigid),
    SPRINGY(Res.string.filament_stiff_springy),
    RUBBER(Res.string.filament_stiff_rubber),
}

enum class PartLoad(val title: StringResource) {
    DECOR(Res.string.filament_load_decor),
    EVERYDAY(Res.string.filament_load_everyday),
    HEAVY(Res.string.filament_load_heavy),
}

enum class PrinterKind(val maxNozzleC: Int, val chamber: Chamber, val title: StringResource) {
    OPEN(260, NONE, Res.string.filament_printer_open),
    ENCLOSED(300, CLOSED, Res.string.filament_printer_enclosed),
    HEATED(350, Chamber.HEATED, Res.string.filament_printer_heated),
    INDUSTRIAL(450, HOT, Res.string.filament_printer_high),
}

enum class PickPriority(val title: StringResource) {
    EASY(Res.string.filament_priority_easy),
    CHEAP(Res.string.filament_priority_cheap),
    PROPERTIES(Res.string.filament_priority_properties),
}

data class FilamentNeeds(
    val heat: PartHeat = PartHeat.ROOM,
    val stiffness: PartStiffness = PartStiffness.RIGID,
    val load: PartLoad = PartLoad.EVERYDAY,
    val outdoor: Boolean = false,
    val chemicals: Boolean = false,
    val printer: PrinterKind = PrinterKind.OPEN,
    val hardenedNozzle: Boolean = false,
    val priority: PickPriority = PickPriority.EASY,
)

class FilamentMatch(val material: FilamentMaterial, val traits: FilamentTraits, val score: Int)

const val SUN_C = 65

object Filaments {
    val traits: Map<FilamentMaterial, FilamentTraits> = mapOf(
        FilamentMaterial.PLA to FilamentTraits(BASIC, 57, 3, 2, 1, 5, 5, 2, 2, 1, NONE),
        FilamentMaterial.PLA_HT to FilamentTraits(BASIC, 61, 3, 2, 1, 4, 5, 2, 2, 2, NONE, setOf(ANNEAL)),
        FilamentMaterial.PETG to FilamentTraits(BASIC, 69, 3, 3, 2, 4, 5, 4, 3, 1, NONE, setOf(OUTDOOR, DRY)),
        FilamentMaterial.PCTG to FilamentTraits(BASIC, 76, 3, 4, 2, 4, 4, 3, 4, 2, NONE, setOf(OUTDOOR)),
        FilamentMaterial.PET to FilamentTraits(BASIC, 66, 3, 2, 2, 4, 4, 3, 3, 2, NONE, setOf(DRY)),
        FilamentMaterial.ABS to FilamentTraits(BASIC, 94, 2, 4, 2, 3, 5, 2, 2, 1, CLOSED, setOf(FUMES)),
        FilamentMaterial.ASA to FilamentTraits(BASIC, 96, 3, 4, 2, 3, 5, 5, 2, 2, CLOSED, setOf(OUTDOOR, FUMES)),
        FilamentMaterial.HIPS to FilamentTraits(BASIC, 88, 2, 3, 2, 3, 4, 1, 2, 2, CLOSED, setOf(FUMES, LIMONENE_SOLUBLE)),
        FilamentMaterial.PVB to FilamentTraits(BASIC, 63, 3, 3, 2, 4, 4, 2, 1, 3, NONE, setOf(DRY)),
        FilamentMaterial.PP to FilamentTraits(ENGINEERING, 59, 1, 5, 3, 2, 2, 2, 4, 3, NONE),
        FilamentMaterial.PMMA to FilamentTraits(ENGINEERING, 99, 4, 1, 1, 2, 3, 5, 2, 3, CLOSED, setOf(OUTDOOR)),
        FilamentMaterial.POM to FilamentTraits(ENGINEERING, 110, 4, 3, 2, 1, 2, 2, 4, 3, HEATED, setOf(FUMES)),
        FilamentMaterial.PHA to FilamentTraits(BASIC, 55, 2, 2, 2, 3, 2, 2, 2, 4, NONE),
        FilamentMaterial.TPU to FilamentTraits(FLEXIBLE, 80, 2, 5, 4, 3, 2, 2, 3, 2, NONE, setOf(DRY)),
        FilamentMaterial.TPU_85A to FilamentTraits(FLEXIBLE, 70, 2, 5, 5, 2, 1, 2, 3, 3, NONE, setOf(DRY)),
        FilamentMaterial.TPE to FilamentTraits(FLEXIBLE, 55, 1, 5, 5, 2, 2, 4, 3, 3, NONE),
        FilamentMaterial.PEBA to FilamentTraits(FLEXIBLE, 75, 2, 5, 5, 3, 2, 2, 3, 4, NONE, setOf(DRY)),
        FilamentMaterial.NYLON to FilamentTraits(ENGINEERING, 112, 4, 4, 2, 2, 3, 2, 3, 3, CLOSED, setOf(DRY)),
        FilamentMaterial.PA12 to FilamentTraits(ENGINEERING, 110, 3, 4, 3, 3, 2, 3, 4, 4, CLOSED, setOf(OUTDOOR, DRY)),
        FilamentMaterial.PC to FilamentTraits(ENGINEERING, 113, 4, 4, 2, 2, 4, 2, 2, 3, CLOSED, setOf(DRY)),
        FilamentMaterial.PC_ABS to FilamentTraits(ENGINEERING, 112, 3, 5, 2, 3, 3, 2, 2, 3, CLOSED, setOf(DRY, FUMES)),
        FilamentMaterial.PVDF to FilamentTraits(ENGINEERING, 158, 3, 3, 3, 2, 1, 5, 5, 5, CLOSED, setOf(OUTDOOR)),
        FilamentMaterial.PPS to FilamentTraits(HIGH_TEMP, 105, 4, 2, 1, 2, 2, 3, 5, 5, HEATED, setOf(ANNEAL)),
        FilamentMaterial.PSU to FilamentTraits(HIGH_TEMP, 172, 4, 2, 2, 2, 2, 2, 3, 5, HOT, setOf(DRY)),
        FilamentMaterial.PPSU to FilamentTraits(HIGH_TEMP, 210, 4, 4, 2, 1, 2, 2, 4, 5, HOT, setOf(DRY)),
        FilamentMaterial.PEI_9085 to FilamentTraits(HIGH_TEMP, 168, 4, 3, 2, 2, 2, 4, 3, 5, HOT, setOf(OUTDOOR, DRY)),
        FilamentMaterial.PEI_1010 to FilamentTraits(HIGH_TEMP, 211, 5, 2, 1, 1, 2, 4, 4, 5, HOT, setOf(OUTDOOR, DRY)),
        FilamentMaterial.PEKK to FilamentTraits(HIGH_TEMP, 154, 5, 2, 1, 2, 1, 3, 4, 5, HOT, setOf(DRY, ANNEAL)),
        FilamentMaterial.PEEK to FilamentTraits(HIGH_TEMP, 141, 5, 4, 1, 1, 1, 3, 5, 5, HOT, setOf(DRY, ANNEAL)),
        FilamentMaterial.PCL to FilamentTraits(BASIC, 57, 2, 3, 3, 2, 1, 2, 2, 3, NONE, setOf(LOW_MELT)),
        FilamentMaterial.PLA_CF_15 to FilamentTraits(FILLED, 55, 3, 2, 1, 4, 5, 3, 2, 2, NONE, setOf(ABRASIVE)),
        FilamentMaterial.PLA_WOOD to FilamentTraits(FILLED, 60, 2, 1, 1, 4, 3, 2, 2, 2, NONE),
        FilamentMaterial.PLA_MARBLE to FilamentTraits(FILLED, 56, 3, 2, 1, 4, 3, 2, 2, 3, NONE, setOf(ABRASIVE)),
        FilamentMaterial.PLA_GLOW to FilamentTraits(FILLED, 55, 2, 2, 1, 4, 3, 2, 2, 2, NONE, setOf(ABRASIVE)),
        FilamentMaterial.PLA_COPPER to FilamentTraits(FILLED, 55, 1, 2, 1, 3, 4, 2, 2, 4, NONE, setOf(ABRASIVE)),
        FilamentMaterial.PLA_BRONZE to FilamentTraits(FILLED, 55, 1, 2, 1, 3, 4, 2, 2, 4, NONE, setOf(ABRASIVE)),
        FilamentMaterial.PLA_STEEL to FilamentTraits(FILLED, 55, 1, 1, 1, 3, 4, 2, 2, 4, NONE, setOf(ABRASIVE)),
        FilamentMaterial.CARBON to FilamentTraits(FILLED, 71, 4, 2, 1, 4, 4, 3, 3, 3, NONE, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.PETG_CF_20 to FilamentTraits(FILLED, 74, 4, 2, 1, 4, 4, 3, 3, 3, NONE, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.PETG_GF_20 to FilamentTraits(FILLED, 74, 3, 2, 1, 4, 3, 3, 3, 3, NONE, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.ABS_CF_20 to FilamentTraits(FILLED, 99, 3, 4, 1, 3, 2, 2, 3, 3, CLOSED, setOf(ABRASIVE, FUMES)),
        FilamentMaterial.ASA_CF_15 to FilamentTraits(FILLED, 102, 3, 2, 1, 3, 5, 5, 3, 3, CLOSED, setOf(OUTDOOR, ABRASIVE, FUMES)),
        FilamentMaterial.PA6_CF_20 to FilamentTraits(FILLED, 186, 5, 3, 1, 3, 3, 3, 3, 4, CLOSED, setOf(ABRASIVE, DRY)),
        FilamentMaterial.PA6_GF_30 to FilamentTraits(FILLED, 184, 5, 3, 1, 3, 3, 2, 3, 4, CLOSED, setOf(ABRASIVE, DRY)),
        FilamentMaterial.PA12_CF_15 to FilamentTraits(FILLED, 150, 5, 4, 1, 4, 3, 3, 4, 4, CLOSED, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.PC_CF_20 to FilamentTraits(FILLED, 135, 4, 3, 1, 3, 3, 4, 2, 4, CLOSED, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.PPS_CF_20 to FilamentTraits(FILLED, 105, 4, 2, 1, 2, 2, 4, 5, 5, HEATED, setOf(OUTDOOR, ABRASIVE, ANNEAL)),
        FilamentMaterial.PEI_CF_20 to FilamentTraits(FILLED, 185, 5, 2, 1, 1, 2, 4, 4, 5, HOT, setOf(OUTDOOR, ABRASIVE, DRY)),
        FilamentMaterial.PEEK_CF_30 to FilamentTraits(FILLED, 143, 5, 3, 1, 1, 1, 3, 5, 5, HOT, setOf(OUTDOOR, ABRASIVE, DRY, ANNEAL)),
        FilamentMaterial.PVA to FilamentTraits(SUPPORT, 55, 2, 2, 2, 2, 2, 1, 1, 4, NONE, setOf(DRY, WATER_SOLUBLE)),
        FilamentMaterial.BVOH to FilamentTraits(SUPPORT, 60, 2, 2, 2, 3, 2, 1, 1, 5, NONE, setOf(DRY, WATER_SOLUBLE)),
    )

    private fun fits(stiffness: PartStiffness, flexibility: Int): Boolean = when (stiffness) {
        PartStiffness.RIGID -> flexibility <= 2
        PartStiffness.SPRINGY -> flexibility in 2..3
        PartStiffness.RUBBER -> flexibility >= 4
    }

    fun pick(needs: FilamentNeeds): List<FilamentMatch> = traits.mapNotNull { (material, t) ->
        val heat = if (needs.outdoor) maxOf(needs.heat.celsius, SUN_C) else needs.heat.celsius
        val gap = t.chamber.ordinal - needs.printer.chamber.ordinal
        val excluded = t.softeningC < heat ||
            material.nozzleC.first > needs.printer.maxNozzleC ||
            gap >= 2 || t.chamber == HOT && gap > 0 ||
            ABRASIVE in t.flags && !needs.hardenedNozzle ||
            WATER_SOLUBLE in t.flags ||
            needs.outdoor && OUTDOOR !in t.flags ||
            needs.chemicals && t.chemical <= 2 ||
            !fits(needs.stiffness, t.flexibility)
        if (excluded) return@mapNotNull null
        var score = t.ease * (if (needs.priority == PickPriority.EASY) 3 else 1) +
            (6 - t.price) * (if (needs.priority == PickPriority.CHEAP) 3 else 1)
        val sturdiness = when (needs.load) {
            PartLoad.DECOR -> 0
            PartLoad.EVERYDAY -> t.strength + t.impact
            PartLoad.HEAVY -> 2 * (t.strength + t.impact) - if (t.impact <= 2) 4 else 0
        }
        score += sturdiness * (if (needs.priority == PickPriority.PROPERTIES) 2 else 1)
        if (needs.outdoor) score += 2 * t.uv
        if (needs.chemicals) score += 2 * t.chemical
        if (needs.heat == PartHeat.VERY_HOT) score += ((t.softeningC - heat) / 20).coerceAtMost(3)
        if (needs.stiffness == PartStiffness.SPRINGY && t.flexibility == 3) score += 4
        if (gap == 1) score -= 8
        if (DRY in t.flags) score -= 1
        FilamentMatch(material, t, score)
    }.sortedByDescending { it.score }
}
