package com.vasmarfas.card.tools

import com.vasmarfas.card.tools.calculators.calculatorTools
import com.vasmarfas.card.tools.converters.converterTools
import com.vasmarfas.card.tools.design.designTools
import com.vasmarfas.card.tools.developer.developerTools
import com.vasmarfas.card.tools.device.deviceTools
import com.vasmarfas.card.tools.documents.documentTools
import com.vasmarfas.card.tools.electronics.electronicsTools
import com.vasmarfas.card.tools.everyday.everydayTools
import com.vasmarfas.card.tools.fitness.fitnessTools
import com.vasmarfas.card.tools.measure.measureTools
import com.vasmarfas.card.tools.media.mediaTools
import com.vasmarfas.card.tools.money.moneyTools
import com.vasmarfas.card.tools.network.networkTools
import com.vasmarfas.card.tools.printing.printingTools
import com.vasmarfas.card.tools.security.securityTools
import com.vasmarfas.card.tools.sound.soundTools
import com.vasmarfas.card.tools.text.textTools
import com.vasmarfas.card.tools.time.timeTools

object ToolRegistry {
    val all: List<Tool> by lazy {
        listOf(
            measureTools, calculatorTools, converterTools, moneyTools, mediaTools, documentTools, timeTools, everydayTools,
            textTools, fitnessTools, soundTools, designTools, securityTools, deviceTools, developerTools, networkTools,
            electronicsTools, printingTools,
        ).flatten()
            .also { list ->
                val duplicates = list.groupBy { it.id }.filter { it.value.size > 1 }.keys
                check(duplicates.isEmpty()) { "Duplicate tool ids: $duplicates" }
            }
    }

    private val byId: Map<String, Tool> by lazy { all.associateBy { it.id } }

    // in the order the Popular shelf shows them: everyday jobs a phone does not come with, so no calculator,
    // timer, stopwatch, compass or torch
    val popular: List<Tool> by lazy {
        listOf(
            "pdf-editor", "image-compressor", "currency-converter", "percentage", "qr-generator", "speed-test",
            "document-converter", "video-converter", "unit-converter", "images-to-pdf", "loan-calculator", "image-converter",
            "text-counter", "merge-pdf", "compress-pdf", "number-to-words", "bmi-body", "date-calculator",
            "password-generator", "noise-generator", "photo-editor", "video-editor", "audio-editor", "decision-wheel",
        ).map { id -> checkNotNull(byId(id)) { "Unknown popular tool $id" } }
    }

    private val merged = mapOf("regex-builder" to "regex-tester", "color-palette" to "color-converter")

    fun byId(id: String): Tool? = byId[id] ?: merged[id]?.let { byId[it] }

    fun byCategory(category: ToolCategory): List<Tool> = all.filter { it.category == category }
}
