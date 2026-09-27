package com.vasmarfas.card.tools.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vasmarfas.card.core.ClipKind
import com.vasmarfas.card.core.MediaClip
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaFormat
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.MediaProject
import com.vasmarfas.card.core.MediaSpec
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.PreviewState
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToLong

val videoFrameTool = Tool(
    id = "video-frame",
    category = ToolCategory.MEDIA,
    title = Res.string.video_frame,
    description = Res.string.video_frame_description,
    icon = Icons.Filled.Screenshot,
    keywords = listOf(
        "frame", "screenshot from video", "still", "thumbnail", "snapshot", "grab frame", "video to image",
        "кадр из видео", "скриншот из видео", "стоп-кадр", "обложка видео", "превью", "видео в картинку",
    ),
) { VideoFrameScreen() }

@Composable
private fun VideoFrameScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf(ImageTarget.PNG) }

    PickButton(Res.string.choose_video.str(), videoExtensions, PickKind.VIDEO, icon = Icons.Filled.VideoLibrary, empty = file == null) { files ->
        val picked = files.first()
        file = picked
        info = null
        loadError = null
        scope.launch {
            runCatching { MediaEngine.probe(picked) }
                .onSuccess { info = it }
                .onFailure { loadError = errorText(it) }
        }
    }
    loadError?.let { ErrorText(it) }
    val source = file ?: return
    val probed = info ?: return
    val duration = probed.durationMs.coerceAtLeast(1)
    val player = remember(source) { PreviewState() }
    val project = remember(source, probed) {
        MediaProject(MediaClip(source, ClipKind.VIDEO, 0, duration), MediaSpec(MediaFormat.MP4, width = probed.width, height = probed.height))
    }
    PreviewFrame(project, player, probed.width.toFloat() / probed.height.coerceAtLeast(1))
    val positionMs = player.positionMs
    val step = if (probed.frameRate > 0) (1000 / probed.frameRate).roundToLong().coerceAtLeast(1) else 40L
    PlayerBar(player, duration)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            player.playing = false
            player.seek((positionMs - step).coerceAtLeast(0))
        }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = Res.string.previous_frame.str())
        }
        Text(formatClock(positionMs), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = {
            player.playing = false
            player.seek((positionMs + step).coerceAtMost(duration - 1))
        }) {
            Icon(Icons.Filled.SkipNext, contentDescription = Res.string.next_frame.str())
        }
        Spacer(Modifier.weight(1f))
        Text("${probed.width} × ${probed.height}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ToolSection(Res.string.format.str()) {
        ChoiceChips(options = listOf(ImageTarget.PNG, ImageTarget.JPEG, ImageTarget.WEBP), selected = target, onSelect = { target = it }, label = { it.title })
    }
    SaveButton(Res.string.save_frame.str()) {
        val at = positionMs
        val full = MediaEngine.frame(source, at, max(probed.width, probed.height)) ?: return@SaveButton false
        val bytes = withContext(Dispatchers.Default) { encodeImage(full, target, 95) }
        saveBytes(bytes, renamed(source.name, target.extension, "-" + formatClock(at).replace(':', '.')))
    }
}
