package com.vasmarfas.card.tools.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.media.LabeledSlider
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.tools.media.decodeImage
import com.vasmarfas.card.tools.media.imageExtensions
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val SOURCE_SIDE = 2048
private const val PREVIEW_SIDE = 256

val appIconsTool = Tool(
    id = "app-icons",
    category = ToolCategory.DESIGN,
    title = Res.string.app_icons,
    description = Res.string.app_icons_description,
    icon = Icons.Filled.Apps,
    keywords = listOf(
        "app icon", "adaptive icon", "launcher icon", "mipmap", "appiconset", "contents.json", "favicon", "ico", "play store icon", "themed icon",
        "иконка приложения", "значок приложения", "адаптивная иконка", "фавикон", "иконки android", "иконки ios",
    ),
) { AppIconsScreen() }

@Composable
private fun AppIconsScreen() {
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var logoDp by rememberSaveable { mutableStateOf(AppIcons.SAFE_DP) }
    var backgroundText by rememberSaveable { mutableStateOf("#FFFFFF") }
    val unreadable = Res.string.image_not_readable.str()
    PickButton(Res.string.app_icons_pick.str(), imageExtensions, PickKind.IMAGE, icon = Icons.Filled.AddPhotoAlternate, empty = image == null) { files ->
        error = null
        scope.launch {
            val decoded = runCatching { decodeImage(files.first().readBytes(), SOURCE_SIDE) }.getOrNull()
            if (decoded == null) error = unreadable else image = decoded
        }
    }
    error?.let { ErrorText(it) }
    val source = image ?: return
    val background = ColorMath.parse(backgroundText)?.copy(a = 255)
    ToolInputField(
        value = backgroundText,
        onValueChange = { backgroundText = it },
        label = Res.string.app_icons_background.str(),
        placeholder = "#326773",
        isError = background == null,
        monospace = true,
    )
    LabeledSlider(Res.string.app_icons_logo_size.str(), "$logoDp dp", logoDp.toFloat(), 48f..AppIcons.CANVAS_DP.toFloat()) { logoDp = it.roundToInt() }
    Hint(Res.string.app_icons_safe_hint.str())
    if (background == null) return
    val thumbnail = remember(source) { source.limitedTo(PREVIEW_SIDE) }
    val preview = remember(thumbnail, logoDp, background) { AppIcons.icon(thumbnail, PREVIEW_SIDE, logoDp, background.toColor()) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(preview, contentDescription = null, modifier = Modifier.size(96.dp).clip(CircleShape))
        Image(preview, contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
        Image(preview, contentDescription = null, modifier = Modifier.size(32.dp))
    }
    Hint(Res.string.app_icons_contents.str())
    SaveButton(Res.string.save_zip.str()) {
        val bytes = withContext(Dispatchers.Default) {
            val zip = ZipWriter()
            AppIcons.files(source, logoDp, background).forEach { zip.add(it.path, it.bytes, compress = false) }
            zip.toByteArray()
        }
        saveBytes(bytes, "app-icons.zip")
    }
}
