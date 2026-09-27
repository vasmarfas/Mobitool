package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
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
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.math.roundToInt

val imageConverterTool = Tool(
    id = "image-converter",
    category = ToolCategory.MEDIA,
    title = Res.string.image_converter,
    description = Res.string.image_converter_description,
    icon = Icons.Filled.PhotoLibrary,
    keywords = listOf(
        "image converter", "png to jpg", "jpg to png", "heic to jpg", "webp to jpg", "webp", "gif", "bmp", "ico", "favicon", "tiff", "resize image",
        "конвертер изображений", "картинки", "png в jpg", "jpg в png", "heic в jpg", "webp в jpg", "иконка", "фавикон", "изменить размер фото", "уменьшить фото",
    ),
) { ImageConverterScreen() }

internal val longSides = listOf(0, 3840, 2560, 1920, 1280, 1024, 512)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageConverterScreen() {
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<PlatformFile>() }
    var target by rememberSaveable { mutableStateOf(ImageTarget.JPEG) }
    var quality by rememberSaveable { mutableStateOf(90) }
    var colors by rememberSaveable { mutableStateOf(0) }
    var white by rememberSaveable { mutableStateOf(true) }
    var longSide by rememberSaveable { mutableStateOf(0) }
    var icons by remember { mutableStateOf(setOf(16, 32, 48, 256)) }
    var outputs by remember { mutableStateOf<List<ImageOutput>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    val task = remember { TaskState() }
    val unreadable = Res.string.image_not_readable.str()

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

    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = ImageTarget.entries, selected = target, onSelect = { target = it }, label = { it.title })
        if (target.lossy) {
            LabeledSlider(Res.string.image_quality.str(), "$quality", quality.toFloat(), 40f..100f) { quality = it.roundToInt() }
        }
    }
    when (target) {
        ImageTarget.PNG -> ToolSection(Res.string.png_colors.str()) {
            ChoiceChips(options = listOf(0, 256, 64, 16), selected = colors, onSelect = { colors = it }, label = { if (it == 0) Res.string.all.str() else "$it" })
        }
        ImageTarget.ICO -> ToolSection(Res.string.icon_sizes.str()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                iconSides.forEach { side ->
                    val on = side in icons
                    FilterChip(
                        selected = on,
                        onClick = { if (!on) icons = icons + side else if (icons.size > 1) icons = icons - side },
                        label = { Text("$side") },
                    )
                }
            }
        }
        else -> Unit
    }
    if (!target.transparent) {
        ToolSection(Res.string.background.str()) {
            ChoiceChips(options = listOf(true, false), selected = white, onSelect = { white = it }, label = { if (it) Res.string.white.str() else Res.string.black.str() })
        }
    }
    if (target != ImageTarget.ICO) {
        ToolSection(Res.string.long_side.str()) {
            ChoiceChips(options = longSides, selected = longSide, onSelect = { longSide = it }, label = { if (it == 0) Res.string.as_source.str() else "$it px" })
        }
    }

    ActionButton(
        text = Res.string.convert.str(),
        icon = Icons.Filled.PhotoLibrary,
        enabled = !task.running,
        onClick = {
            outputs = emptyList()
            failed = emptyList()
            val sources = files.toList()
            val format = target
            val background = if (white) Color.White else Color.Black
            task.launch(scope) { progress ->
                val taken = mutableSetOf<String>()
                val done = ArrayList<ImageOutput>()
                val skipped = ArrayList<String>()
                sources.forEachIndexed { index, file ->
                    val bytes = file.readBytes()
                    val image = decodeImage(bytes)
                    if (image == null) {
                        skipped += file.name
                    } else {
                        val scaled = if (format == ImageTarget.ICO) image else image.limitedTo(longSide).limitedFor(format, colors)
                        val encoded = withContext(Dispatchers.Default) { encodeImage(scaled, format, quality, colors, background, icons.toList()) }
                        done += ImageOutput(uniqueName(renamed(file.name, format.extension), taken), encoded, bytes.size.toLong(), scaled.width, scaled.height)
                    }
                    progress((index + 1f) / sources.size)
                }
                outputs = done
                failed = skipped
            }
        },
    )
    TaskProgress(task, Res.string.converting.str())
    failed.forEach { ErrorText("$it: $unreadable") }
    ImageOutputs(outputs, "images-${target.extension}.zip")
}
