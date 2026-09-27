package com.vasmarfas.card.core

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val STILL_SIDE = 960

// for players that cannot show a paused frame of several layers
@Composable
internal fun StillFrame(project: MediaProject, atMs: Long, infos: Map<PlatformFile, MediaInfo>, modifier: Modifier = Modifier) {
    val images = remember { HashMap<PlatformFile, ImageBitmap>() }
    var frames by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    val active = project.pictures.mapNotNull { track -> track.clips.firstOrNull { atMs >= it.atMs && atMs < it.endAtMs } }
    fun key(clip: MediaClip) = if (clip.kind == ClipKind.IMAGE) clip.file.toString() else "${clip.file}@${clip.startMs + (atMs - clip.atMs) / 40 * 40}"
    val wanted = active.map(::key)
    LaunchedEffect(wanted) {
        delay(40)
        frames = coroutineScope {
            active.map { clip ->
                async {
                    val image = if (clip.kind == ClipKind.IMAGE) {
                        images[clip.file] ?: runCatching { decodeRawImage(clip.file.readBytes(), STILL_SIDE)?.bitmap?.limitedTo(STILL_SIDE) }.getOrNull()?.also { images[clip.file] = it }
                    } else {
                        frames[key(clip)] ?: MediaEngine.frame(clip.file, clip.startMs + (atMs - clip.atMs), STILL_SIDE)
                    }
                    image?.let { key(clip) to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }
    val (frameW, frameH) = project.spec.frameSize(project.firstPicture?.let { infos[it.file] })
    Canvas(modifier) {
        drawRect(Color.Black)
        val scale = size.width / frameW
        active.forEach { clip ->
            val image = frames[key(clip)] ?: return@forEach
            val box = clip.box.place(image.width, image.height, frameW, frameH, clip.fill)
            val source = if (clip.fill) coverCrop(image.width, image.height, frameW, frameH) else null
            drawImage(
                image,
                srcOffset = source?.let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } ?: IntOffset.Zero,
                srcSize = source?.let { IntSize(it.width.roundToInt(), it.height.roundToInt()) } ?: IntSize(image.width, image.height),
                dstOffset = IntOffset((box.left * scale).roundToInt(), (box.top * scale).roundToInt()),
                dstSize = IntSize((box.width * scale).roundToInt(), (box.height * scale).roundToInt()),
                alpha = (clip.opacity * clip.fadeAt(atMs)).coerceIn(0f, 1f),
            )
        }
    }
}
