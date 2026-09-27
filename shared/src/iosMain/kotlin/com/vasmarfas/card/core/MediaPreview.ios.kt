@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.audioMix
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.videoComposition
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.kCMTimeZero
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

private const val PREVIEW_SIDE = 1280

private class PlayerView(private val playerLayer: AVPlayerLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

private fun AVPlayer.seekExactly(time: CValue<CMTime>) = seekToTime(time, kCMTimeZero.readValue(), kCMTimeZero.readValue())

@Composable
actual fun MediaPreview(project: MediaProject?, state: PreviewState, modifier: Modifier) {
    val player = remember { AVPlayer() }
    val playerLayer = remember { AVPlayerLayer.playerLayerWithPlayer(player) }
    var built by remember { mutableStateOf<MediaEngine.Built?>(null) }
    val structure = remember(project) { project?.structure() }
    UIKitView(
        factory = { PlayerView(playerLayer) },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = null),
    )
    DisposableEffect(player) {
        onDispose {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            built?.discardStills()
        }
    }
    LaunchedEffect(structure) {
        state.playing = false
        val current = project
        val next = current?.let { runCatching { MediaEngine.build(it, video = true, audio = true, maxSide = PREVIEW_SIDE) }.getOrNull() }
        val previous = built
        built = next
        val item = if (current != null && next != null) {
            AVPlayerItem.playerItemWithAsset(next.composition).apply {
                videoComposition = next.videoComposition(current)
                audioMix = next.audioMix
            }
        } else {
            null
        }
        player.replaceCurrentItemWithPlayerItem(item)
        previous?.discardStills()
        player.seekExactly(cmTime(state.positionMs))
    }
    LaunchedEffect(project, built) {
        val current = project ?: return@LaunchedEffect
        val item = player.currentItem ?: return@LaunchedEffect
        val look = built?.takeIf { it.structure == structure } ?: return@LaunchedEffect
        item.videoComposition = look.videoComposition(current)
        if (!state.playing) player.seekExactly(player.currentTime())
    }
    LaunchedEffect(state.seekRequest) {
        if (!state.playing) player.seekExactly(cmTime(state.positionMs))
    }
    LaunchedEffect(state.playing) {
        if (!state.playing) {
            player.pause()
            return@LaunchedEffect
        }
        player.seekExactly(cmTime(state.positionMs))
        player.play()
        while (player.currentItem != null) {
            delay(33)
            state.report((CMTimeGetSeconds(player.currentTime()) * 1000).toLong())
            if (player.rate == 0f) break
        }
        state.playing = false
    }
}
