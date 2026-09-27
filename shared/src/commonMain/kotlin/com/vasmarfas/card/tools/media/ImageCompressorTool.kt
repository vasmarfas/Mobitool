package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.math.roundToInt

val imageCompressorTool = Tool(
    id = "image-compressor",
    category = ToolCategory.MEDIA,
    title = Res.string.image_compressor,
    description = Res.string.image_compressor_description,
    icon = Icons.Filled.Compress,
    keywords = listOf(
        "compress image", "reduce photo size", "optimize", "kb", "jpeg quality", "tinypng", "shrink",
        "сжать фото", "сжать картинку", "уменьшить вес фото", "размер фото", "до 200 кб", "до 1 мб", "оптимизация", "убрать геометку",
    ),
) { ImageCompressorScreen() }

private const val CUSTOM_LIMIT = -1L
private val limits = listOf(50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, CUSTOM_LIMIT)

@Composable
private fun ImageCompressorScreen() {
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<PlatformFile>() }
    var bySize by rememberSaveable { mutableStateOf(true) }
    var limit by rememberSaveable { mutableStateOf(500_000L) }
    var customSize by rememberSaveable { mutableStateOf("300") }
    var customUnit by rememberSaveable { mutableStateOf(1_000L) }
    var quality by rememberSaveable { mutableStateOf(75) }
    var target by rememberSaveable { mutableStateOf(ImageTarget.JPEG) }
    var longSide by rememberSaveable { mutableStateOf(0) }
    var outputs by remember { mutableStateOf<List<ImageOutput>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    val task = remember { TaskState() }
    val unreadable = Res.string.image_not_readable.str()
    val chosenLimit = if (limit == CUSTOM_LIMIT) ((customSize.replace(',', '.').toDoubleOrNull() ?: 0.0) * customUnit).toLong() else limit

    PickButton(Res.string.choose_images.str(), imageExtensions, PickKind.IMAGE, multiple = true, icon = Icons.Filled.AddPhotoAlternate, empty = files.isEmpty()) { picked ->
        files.clear()
        files += picked
        outputs = emptyList()
        failed = emptyList()
    }
    if (files.isEmpty()) return
    Text(
        pluralStringResource(Res.plurals.image_count, files.size, files.size) + " · " + formatBytes(files.sumOf { it.size() }, binary = false),
        style = MaterialTheme.typography.bodyLarge,
    )

    SegmentedChoice(
        options = listOf(true, false),
        selected = bySize,
        onSelect = { bySize = it },
        label = { if (it) Res.string.compress_by_size.str() else Res.string.compress_by_quality.str() },
    )
    if (bySize) {
        ToolSection(Res.string.target_size.str()) {
            ChoiceChips(
                options = limits,
                selected = limit,
                onSelect = { limit = it },
                label = { if (it == CUSTOM_LIMIT) Res.string.custom_size.str() else formatBytes(it, binary = false) },
            )
            if (limit == CUSTOM_LIMIT) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(customSize, { customSize = it }, Res.string.target_size.str(), Modifier.weight(1f), isError = chosenLimit <= 0)
                    SegmentedChoice(
                        options = listOf(1_000L, 1_000_000L),
                        selected = customUnit,
                        onSelect = { customUnit = it },
                        label = { if (it == 1_000L) Res.string.unit_kb.str() else Res.string.unit_mb.str() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else if (target != ImageTarget.PNG) {
        LabeledSlider(Res.string.image_quality.str(), "$quality", quality.toFloat(), 10f..100f) { quality = it.roundToInt() }
    }
    ToolSection(Res.string.format.str()) {
        ChoiceChips(
            options = listOf(ImageTarget.JPEG, ImageTarget.WEBP, ImageTarget.PNG),
            selected = target,
            onSelect = { target = it },
            label = { if (it == ImageTarget.PNG) Res.string.png_palette.str() else it.title },
        )
    }
    ToolSection(Res.string.long_side.str()) {
        ChoiceChips(options = longSides, selected = longSide, onSelect = { longSide = it }, label = { if (it == 0) Res.string.as_source.str() else "$it px" })
    }

    ActionButton(
        text = Res.string.compress.str(),
        icon = Icons.Filled.Compress,
        enabled = !task.running && (!bySize || chosenLimit > 0),
        onClick = {
            outputs = emptyList()
            failed = emptyList()
            val sources = files.toList()
            val format = target
            val size = if (bySize) chosenLimit else 0L
            task.launch(scope) { progress ->
                val taken = mutableSetOf<String>()
                val done = ArrayList<ImageOutput>()
                val skipped = ArrayList<String>()
                sources.forEachIndexed { index, file ->
                    val bytes = file.readBytes()
                    val image = decodeImage(bytes)?.limitedTo(longSide)?.limitedFor(format, colors = 256)
                    if (image == null) {
                        skipped += file.name
                    } else {
                        val result = withContext(Dispatchers.Default) {
                            if (size > 0) compressImage(image, format, size) else Compressed(encodeImage(image, format, quality, colors = 256), image.width, image.height)
                        }
                        done += ImageOutput(uniqueName(renamed(file.name, format.extension), taken), result.bytes, bytes.size.toLong(), result.width, result.height)
                    }
                    progress((index + 1f) / sources.size)
                }
                outputs = done
                failed = skipped
            }
        },
    )
    TaskProgress(task, Res.string.compressing.str())
    failed.forEach { ErrorText("$it: $unreadable") }
    ImageOutputs(outputs, "compressed.zip")
}
