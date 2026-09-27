package com.vasmarfas.card.tools.printing

import com.vasmarfas.card.resources.*
import kotlin.math.PI
import org.jetbrains.compose.resources.StringResource

enum class FilamentMaterial(
    val title: StringResource,
    val density: Double,
    val nozzleC: IntRange,
    val bedC: IntRange,
) {
    PLA(Res.string.pla, 1.24, 195..230, 40..60),
    PLA_HT(Res.string.mat_pla_ht, 1.24, 200..230, 35..65),
    PETG(Res.string.petg, 1.27, 230..260, 70..80),
    PCTG(Res.string.mat_pctg, 1.23, 250..270, 80..105),
    PET(Res.string.mat_pet, 1.33, 210..230, 60..80),
    ABS(Res.string.abs, 1.04, 240..270, 80..100),
    ASA(Res.string.asa, 1.07, 250..270, 85..105),
    HIPS(Res.string.mat_hips, 1.05, 230..250, 90..105),
    PVB(Res.string.mat_pvb, 1.10, 200..225, 50..80),
    PP(Res.string.mat_pp, 0.90, 220..240, 60..80),
    PMMA(Res.string.mat_pmma, 1.18, 235..250, 95..105),
    POM(Res.string.mat_pom, 1.42, 215..240, 100..125),
    PHA(Res.string.mat_pha, 1.24, 190..200, 20..25),
    TPU(Res.string.tpu, 1.22, 220..240, 35..50),
    TPU_85A(Res.string.mat_tpu_85a, 1.18, 200..235, 35..40),
    TPE(Res.string.mat_tpe, 1.10, 225..250, 50..60),
    PEBA(Res.string.mat_peba, 1.01, 225..245, 60..90),
    NYLON(Res.string.nylon, 1.12, 250..270, 70..90),
    PA12(Res.string.mat_pa12, 1.01, 245..265, 90..105),
    PC(Res.string.pc, 1.20, 260..280, 90..110),
    PC_ABS(Res.string.mat_pc_abs, 1.10, 260..280, 90..105),
    PVDF(Res.string.mat_pvdf, 1.75, 255..270, 70..100),
    PPS(Res.string.mat_pps, 1.33, 315..345, 110..130),
    PSU(Res.string.mat_psu, 1.24, 360..400, 140..160),
    PPSU(Res.string.mat_ppsu, 1.29, 380..400, 150..220),
    PEI_9085(Res.string.mat_pei_9085, 1.31, 360..380, 150..160),
    PEI_1010(Res.string.mat_pei_1010, 1.28, 370..390, 125..155),
    PEKK(Res.string.mat_pekk, 1.28, 360..380, 115..135),
    PEEK(Res.string.mat_peek, 1.30, 395..445, 150..180),
    PCL(Res.string.mat_pcl, 1.10, 130..170, 30..45),
    PLA_CF_15(Res.string.mat_pla_cf_15, 1.27, 190..230, 35..60),
    PLA_WOOD(Res.string.pla_wood, 1.20, 190..220, 45..60),
    PLA_MARBLE(Res.string.mat_pla_marble, 1.28, 190..225, 45..60),
    PLA_GLOW(Res.string.mat_pla_glow, 1.24, 190..230, 45..60),
    PLA_COPPER(Res.string.mat_pla_copper, 3.40, 190..220, 25..60),
    PLA_BRONZE(Res.string.mat_pla_bronze, 3.50, 195..220, 25..60),
    PLA_STEEL(Res.string.mat_pla_steel, 2.70, 190..210, 25..60),
    CARBON(Res.string.carbon_filled, 1.27, 240..270, 70..80),
    PETG_CF_20(Res.string.mat_petg_cf_20, 1.29, 240..265, 70..90),
    PETG_GF_20(Res.string.mat_petg_gf_20, 1.27, 240..270, 65..75),
    ABS_CF_20(Res.string.mat_abs_cf_20, 1.06, 240..260, 100..110),
    ASA_CF_15(Res.string.mat_asa_cf_15, 1.10, 250..280, 90..110),
    PA6_CF_20(Res.string.mat_pa6_cf_20, 1.17, 260..290, 80..100),
    PA6_GF_30(Res.string.mat_pa6_gf_30, 1.28, 260..285, 65..90),
    PA12_CF_15(Res.string.mat_pa12_cf_15, 1.07, 265..290, 80..100),
    PC_CF_20(Res.string.mat_pc_cf_20, 1.24, 275..295, 100..120),
    PPS_CF_20(Res.string.mat_pps_cf_20, 1.29, 310..350, 90..110),
    PEI_CF_20(Res.string.mat_pei_cf_20, 1.31, 370..395, 145..160),
    PEEK_CF_30(Res.string.mat_peek_cf_30, 1.38, 390..430, 120..160),
    PVA(Res.string.mat_pva, 1.23, 220..225, 35..60),
    BVOH(Res.string.mat_bvoh, 1.14, 200..220, 60..70),
    ;
}

val filamentDiameters = listOf(1.75, 2.85)

object Filament {
    fun volumeCm3(lengthM: Double, diameterMm: Double): Double = PI * diameterMm * diameterMm * lengthM / 4.0

    fun lengthFromVolumeM(volumeCm3: Double, diameterMm: Double): Double = volumeCm3 * 4.0 / (PI * diameterMm * diameterMm)

    fun grams(lengthM: Double, diameterMm: Double, density: Double): Double = volumeCm3(lengthM, diameterMm) * density

    fun lengthM(grams: Double, diameterMm: Double, density: Double): Double =
        lengthFromVolumeM(grams / density, diameterMm)

    fun gramsPerMetre(diameterMm: Double, density: Double): Double = grams(1.0, diameterMm, density)
}
